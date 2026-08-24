package app.yxi.agent

import app.yxi.ssh.SshSession
import org.json.JSONObject

/** 会话状态。语义跟服务器上 `cc-state` 写的一致。 */
enum class SessionState(val label: String) {
    NeedsYou("等你"), Working("干活中"), Done("已完成"), Idle("空闲");

    companion object {
        fun of(raw: String?) = when (raw) {
            "input" -> NeedsYou
            "work" -> Working
            "done" -> Done
            else -> Idle
        }
    }
}

data class Session(
    val name: String,
    val windows: Int,
    val attached: Boolean,
    val cwd: String,
    val lastActivity: Long,
    val state: SessionState,
    /** cc-state 写的一句话：「运行命令: …」「等待你(决策/输入)」之类 */
    val detail: String,
    val stateTs: Double,
) {
    /** 去掉 `cc-` 前缀的短名，界面上用 */
    val short get() = name.removePrefix("cc-")
}

/**
 * 一次 SSH 往返拿到全部会话信息。
 *
 * **抄 Moshi 的两个做法**（PRD §1.2 / 附录 C.1）：
 *   1. **一次往返拿全部** —— 手机网络下往返成本高，不要一个命令一个连接
 *   2. **带版本号的 marker 分段** —— 主机侧脚本以后改了、App 还是旧的，
 *      能优雅降级而不是解析崩掉
 *
 * 还有第三个：**不假设主机上有 jq / python**，只用 `printf` 和 `cat`。
 * 这是 Moshi 自己在脚本注释里写明的理由，很实在——目标机可能什么都没装。
 */
object SessionProbe {

    private const val MARKER = "__YXI_SNAPSHOT_V1__"

    private val SCRIPT = """
        m=$MARKER
        s(){ printf '%s\t%s\n' "${'$'}m" "${'$'}1"; }
        s tmux_begin
        tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_activity}|#{session_attached}|#{pane_current_path}' 2>/dev/null || true
        s tmux_end
        s status_begin
        for f in ${'$'}HOME/.cloud-status/*.json; do [ -f "${'$'}f" ] && cat "${'$'}f" && echo; done 2>/dev/null || true
        s status_end
    """.trimIndent()

    suspend fun snapshot(session: SshSession): List<Session> {
        val out = session.exec(SCRIPT)
        val tmux = extract(out, "tmux")
        val status = extract(out, "status")

        // 状态先建索引：会话名 → (state, detail, ts)
        val states = HashMap<String, Triple<String, String, Double>>()
        status.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            runCatching {
                val o = JSONObject(line)
                val name = o.optString("session")
                if (name.isNotEmpty()) {
                    states[name] = Triple(
                        o.optString("state"), o.optString("detail"), o.optDouble("ts", 0.0)
                    )
                }
            }
        }

        return tmux.lineSequence().filter { it.contains('|') }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 5) return@mapNotNull null
            val name = p[0]
            val st = states[name]
            Session(
                name = name,
                windows = p[1].toIntOrNull() ?: 1,
                lastActivity = p[2].toLongOrNull() ?: 0L,
                attached = p[3] != "0",
                cwd = p[4],
                state = SessionState.of(st?.first),
                detail = st?.second.orEmpty(),
                stateTs = st?.third ?: 0.0,
            )
        }.toList()
    }

    /** 只要 `<marker>\tX_begin` 和 `<marker>\tX_end` 之间的内容。缺段就当空，不抛异常。 */
    private fun extract(out: String, name: String): String {
        val begin = "$MARKER\t${name}_begin"
        val end = "$MARKER\t${name}_end"
        val a = out.indexOf(begin).takeIf { it >= 0 } ?: return ""
        val b = out.indexOf(end, a).takeIf { it >= 0 } ?: return ""
        return out.substring(a + begin.length, b).trim('\n', '\r')
    }

    /** 给某个会话发一句话。`hub say` 已经验证过这条路走得通。 */
    suspend fun send(session: SshSession, target: String, text: String) {
        // 分两步：先送文本、再单独送回车。合成一条时，文本里若含特殊字符
        // 会让 send-keys 把它当按键名解析（比如 "Enter" 三个字就会变成回车键）
        val q = text.replace("'", "'\\''")
        session.exec("tmux send-keys -t '$target' -l '$q'")
        session.exec("tmux send-keys -t '$target' Enter")
    }

    /**
     * 这个会话**此刻**是不是在等人选。
     *
     * ⚠️ **只有屏幕知道这件事。** 实测：Claude Code 的 `tool_use` 块要等工具跑完
     * 才写进转录 JSONL —— 问题挂着等你的那段时间，转录里根本没有它。
     * 所以：**转录是权威的历史，屏幕是唯一的「此刻」**（见 [Prompt] 的类注释）。
     */
    suspend fun pending(session: SshSession, target: String): Pending? =
        Prompt.parse(peek(session, target, 60))

    /**
     * 一次抓屏，把「等你选」和「此刻在忙什么 / 排队的输入」一起解出来。
     *
     * ⚠️ 合成一次是有意的：两边都要抓屏，分两次不但多一个来回，
     * 还会**看到两个不同时刻的屏幕** —— 状态和待答对不上，
     * 表现成偶发的闪烁，非常难查。
     *
     */
    suspend fun snapshot(session: SshSession, target: String): Pair<Pending?, Live> {
        val screen = peek(session, target, 60)
        return Prompt.parse(screen) to Live.parse(screen)
    }

    /** 允许送的按键。⚠️ 白名单，因为 [key] 最终会拼进 shell 命令。 */
    private val SAFE_KEY = Regex("""^([0-9]{1,2}|Up|Down|Left|Right|Enter|Escape)$""")

    /**
     * 送**一个按键**（不带回车）—— 点选项就靠它。
     *
     * 协议是实测出来的：单选送数字即选中并确认；多选送数字是切换勾选，
     * 要再送 `Right` 跳到 Submit 页、送 `1` 才算提交。
     */
    suspend fun sendKey(session: SshSession, target: String, key: String): Boolean {
        if (!SAFE_KEY.matches(key)) return false
        session.exec("tmux send-keys -t '$target' '$key'")
        return true
    }

    /**
     * 把**排队中还没轮到**的输入全部收回来，让人在手机上改了再发。
     *
     * 协议是在真会话上实测出来的（TUI 的脚注自己写着 `Press up to edit queued messages`）：
     *   · `Up`   —— 把排队的**全部**弹回输入框，拼成一段多行文本
     *   · `C-u`  —— 把输入框清空（脚注变成 `Ctrl+Y to paste deleted text`，说明是 kill 不是删）
     *
     * ⚠️ **`Up` 是全有全无的，收不了单独一条。** 排了三条按一次 `Up`，三条一起回来。
     * 所以界面上不能做成「撤回这一条」—— 得说清楚是把排队的都收回来。
     *
     * ⚠️ **文本不要从屏幕上刮。** 输入框会按宽度折行，拼回原文要处理折行、
     * 还要跟提示行区分（[Live] 的类注释记着当初就是在这儿栽的）。
     * 转录里的 `queue-operation` 有原文，调用方拿那个。
     *
     * ⚠️ 收回之后转录里落的是 **`popAll`**，不是 `remove` —— [Transcript] 那边认这个词，
     * 认错了气泡就永远挂着。
     */
    suspend fun popQueue(session: SshSession, target: String) {
        session.exec("tmux send-keys -t '$target' Up")
        session.exec("tmux send-keys -t '$target' C-u")
    }

    /** 抓某个会话最近 n 行屏幕，看板上做预览。 */
    suspend fun peek(session: SshSession, target: String, lines: Int = 40): String =
        session.exec("tmux capture-pane -p -t '$target' 2>/dev/null | tail -$lines")
}
