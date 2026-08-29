package app.yxi.agent

import app.yxi.ui.t
import app.yxi.ssh.SshSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 会话状态。语义跟服务器上 `cc-state` 写的一致。 */
enum class SessionState(private val zh: String) {
    NeedsYou("等你"), Working("干活中"), Done("已完成"), Idle("空闲");

    // ⚠️ **label 必须是 get() 而不是构造参数。** enum 常量的参数在**类初始化时求值一次**，
    // 之后换语言它不会跟着变 —— 现象是底部导航栏 / 模式切换条永远停在启动时那种语言，
    // 而同一屏别的字都变了。get() 每次读都重新查表，还能被 Compose 当成状态读取。
    val label: String get() = t(zh)


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

    // ⚠️ 转录时间那段用 `find -printf` + `awk` **一次扫完**：实测 8ms，
    // 而「每个目录 ls+stat」的写法要 230ms —— 看板每 5 秒跑一次，那个代价不能要。
    // `-printf` 是 GNU find 专有；不是 GNU find 就输出空，[lastActivityOf] 自动退回 tmux 的时间（优雅降级）。
    private val SCRIPT = """
        m=$MARKER
        s(){ printf '%s\t%s\n' "${'$'}m" "${'$'}1"; }
        s tmux_begin
        tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_activity}|#{session_attached}|#{pane_current_path}' 2>/dev/null || true
        s tmux_end
        s ev_begin
        tail -n 200 ${'$'}HOME/.yxi/events.jsonl 2>/dev/null || true
        s ev_end
        s cc_begin
        # ⚠️ 用 `awk 1` 不用 `for … cat`：后者**每个文件起一个进程**，
        # 19 个文件实测 352ms，而看板每 5 秒跑一次。`awk 1` 是 25ms，快 14 倍。
        # ⚠️ 也不能用 `cat *.json`：这些 JSON **末尾没有换行**，
        # cat 会把它们**粘成一行**（实测 19 条变 1 条）—— 跟 #140 公钥粘行同一个坑。
        # `awk 1` 每读完一个文件补一个换行，两个问题一起解决。
        awk 1 ${'$'}HOME/.claude/sessions/*.json 2>/dev/null || true
        s cc_end
        s status_begin
        awk 1 ${'$'}HOME/.cloud-status/*.json 2>/dev/null || true
        s status_end
        s tr_begin
        find "${'$'}HOME/.claude/projects" -maxdepth 2 -name '*.jsonl' -printf '%h\t%T@\n' 2>/dev/null | awk -F'\t' '{n=split(${'$'}1,a,"/"); d=a[n]; t=int(${'$'}2); if(t>m[d]) m[d]=t} END{for(k in m) printf "%s\t%d\n", k, m[k]}' 2>/dev/null || true
        s tr_end
        s gp_begin
        # 分组表（手机写、组里的 agent 读）。就一个小文件，几乎不花时间。
        cat ${'$'}HOME/.yxi/groups.json 2>/dev/null || true
        s gp_end
    """.trimIndent()

    /** 一次抓取拿到的全部东西：会话 + 分组表。 */
    data class Snap(val sessions: List<Session>, val groups: Groups.Table)

    /**
     * 只要会话。**七个调用点都只关心这个**，所以保持原样别动它们 ——
     * 要分组表的（只有看板）走 [snapshotFull]。分组表跟会话是同一次抓取里带回来的，
     * 不多花一个来回。
     */
    suspend fun snapshot(session: SshSession): List<Session> = snapshotFull(session).sessions

    suspend fun snapshotFull(session: SshSession): Snap {
        val out = session.exec(SCRIPT)
        val tmux = extract(out, "tmux")
        val status = extract(out, "status")
        // 转录最后写入时间 —— **「上次对话」的真来源**（见 [lastActivityOf]）
        val trs = parseTranscriptTimes(extract(out, "tr"))

        // 状态先建索引：会话名 → (state, detail, ts)
        // ⚠️⚠️ **首选 Claude Code 自己维护的那份**（`~/.claude/sessions/*.json`）。
        //
        // 原来只读 `~/.cloud-status/`，而那是 **`cc-state` 写的，`cc-state` 不是 Yxi 装的**
        // —— `server/install.sh` 只装 `yxi-hook`。它只在我们自己的开发机上跑着
        // （那是 remote-dev-station 的一部分），于是**在任何真实用户的服务器上
        // 那个目录是空的 → 每个会话都判成 Idle → 看板首页全是「空闲」，
        // 一个「等你」都没有**。而「一眼看清谁在等你」正是这个 App 存在的理由。
        // 实测：本机 37 个状态文件，另一台普通服务器 0 个。
        //
        // Claude Code 自己那份是**零安装**的，任何装了 Claude Code 的机器上都有，
        // 而且是**水平状态**（当前是什么）不是**边缘事件**（发生过什么）——
        // 后者会漂：hook 写完 `input` 之后用户在终端答完了，没有任何 hook 把它改回来。
        //
        // ⚠️ 只换「谁在等你」这一个来源。**选项解析仍然必须抓屏** ——
        // 实测 AskUserQuestion 的 hook 只给 3 个选项而屏幕上是 5 个
        // （Claude Code 自己插了「Type something」「Chat about this」）。
        // **屏幕上的编号才是契约**，从别处读顺序会静默选错（#47 那一族）。
        val ccStates = HashMap<String, Triple<String, String, Double>>()
        extract(out, "cc").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            runCatching {
                val o = JSONObject(line)
                // `tmux` 字段形如 `cc-Yxi:@28.%28` —— 取冒号前那段就是会话名
                val tm = o.optString("tmux")
                val name = if (tm.isNotEmpty()) tm.substringBefore(':') else ""
                if (name.isEmpty()) return@runCatching
                val state = when (o.optString("status")) {
                    "waiting" -> "input"
                    "busy" -> "work"
                    else -> ""            // idle：留空 → SessionState.of 给 Idle
                }
                if (state.isEmpty()) return@runCatching
                ccStates[name] = Triple(
                    state,
                    // `waitingFor` 是它自己的枚举（dialog open / input needed / …），
                    // ⚠️ 未登记的新类型它默认落到 "permission prompt" —— 失败方向朝
                    // 「更该提醒你」偏，正是我们要的那一侧
                    o.optString("waitingFor"),
                    o.optDouble("statusUpdatedAt", 0.0) / 1000.0,
                )
            }
        }

        // ⚠️ **把 hook 算好的 `preview` 搬到看板上。**
        //
        // `yxi-hook` 早就在算它了（`last_assistant()` 读转录末 64KB，**零 token 零 API**），
        // 但一直只用在通知里。而一行「它到底卡在哪」比一张缩略图有用得多 ——
        // 缩略图在手机尺寸上基本读不出内容，还要每 5 秒多跑几次 capture-pane。
        // 第一方 Claude Code 的 agent view 那句「Haiku 生成的摘要」就是这个东西，
        // 而我们不用花 Haiku 的钱。
        //
        // ⚠️ 只取**每个会话最后一条**：events.jsonl 是追加写的流水，
        // 前面那些是历史，拿来当「此刻卡在哪」会是陈年旧事。
        val evPreview = HashMap<String, String>()
        extract(out, "ev").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            runCatching {
                val o = JSONObject(line)
                val name = o.optString("session")
                val pv = o.optString("preview").ifEmpty { o.optString("arg") }
                if (name.isNotEmpty() && pv.isNotEmpty()) evPreview[name] = pv
            }
        }

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

        return Snap(
            tmux.lineSequence().filter { it.contains('|') }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 5) return@mapNotNull null
            val name = p[0]
            // Claude Code 自己那份优先；没有才退回 cc-state（我们自己机器上才有）
            val st = ccStates[name] ?: states[name]
            Session(
                name = name,
                windows = p[1].toIntOrNull() ?: 1,
                lastActivity = lastActivityOf(p[2].toLongOrNull() ?: 0L, p[4], trs),
                attached = p[3] != "0",
                cwd = p[4],
                state = SessionState.of(st?.first),
                // 状态源给的 detail 优先（它更「此刻」）；空了才用 hook 那句摘要
                detail = st?.second?.takeIf { it.isNotBlank() } ?: evPreview[name].orEmpty(),
                stateTs = st?.third ?: 0.0,
            )
        }.toList(),
            Groups.parse(extract(out, "gp")),
        )
    }

    /** `<项目目录名>\t<unix秒>` 一行一条 → map。解析不了的行忽略。 */
    internal fun parseTranscriptTimes(raw: String): Map<String, Long> {
        val m = HashMap<String, Long>()
        raw.lineSequence().forEach { ln ->
            val i = ln.indexOf('\t')
            if (i <= 0) return@forEach
            val ts = ln.substring(i + 1).trim().toLongOrNull() ?: return@forEach
            if (ts > 0) m[ln.substring(0, i).trim()] = ts
        }
        return m
    }

    /**
     * 这个会话**上次真正对话**是什么时候。
     *
     * ⚠️ **不能只信 `tmux session_activity`。** 实测（用户报「为什么显示那么久之前，很多不是刚对话吗」）：
     * `cc-claude_desktop` 的 tmux 活动写着 **2 天前**、cc-state 的 ts 更离谱写着 **7 天前**，
     * 而它的转录**一分钟前**还在写 —— 那个会话正聊着。两个信号都会陈旧（tmux 那个不随
     * 无人 attach 的输出更新，cc-state 那个要靠钩子写）。
     *
     * **转录文件的 mtime 才是权威**：Claude Code 每说一句都在写它。取两者较大的，
     * 转录读不到（不是 Claude 会话 / 目录对不上）就退回 tmux 那个。
     */
    internal fun lastActivityOf(tmuxTs: Long, cwd: String, transcripts: Map<String, Long>): Long {
        val tr = transcripts[Transcript.projectDirOf(cwd)] ?: 0L
        return maxOf(tmuxTs, tr)
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
    /**
     * 给某个会话发一句话。
     *
     * ⚠️ **整段不可取消（[NonCancellable]）。** 它是两步：先送文本、再单独送回车
     * （合成一条时文本里的特殊字符会被 send-keys 当按键名解析，比如 "Enter" 三个字
     * 就真变成回车）。中间被取消的话，**字打进去了但回车没送** ——
     * 那句话就卡在对方输入框里没提交，用户以为发了、其实没发。
     * 用户原话：「点了向上的箭头然后切出去，容易没发送给 agent，要在对话里等几秒再返回才算发出去」。
     */
    suspend fun send(session: SshSession, target: String, text: String) = withContext(NonCancellable) {
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
    private const val SCR_MARK = "__YXI_SCR__"

    /**
     * **盯屏幕：变了才推。** 一条长连通道，服务器侧自己比对，没变就不过网。
     *
     * ⚠️ 为什么不轮询：轮询是「每次都要问一遍」，每问一次就是一个 SSH 往返。
     * 实测抓屏本身 **0 毫秒**，3 秒延迟几乎全是往返 + 轮询间隔 + 被 `busy` 停住的等待。
     * 改成推之后，延迟 ≈ 服务器侧的检测间隔（0.2s）+ 单程网络，点完选项下一题基本立刻就到。
     *
     * ⚠️ `|| exit` 不能省：会话没了 / 手机断了要让远端这个循环自己退，
     * 否则每次重连都在服务器上留一个空转的壳（[SshSession.follow] 踩过同样的坑）。
     */
    fun watchScreen(session: SshSession, target: String, lines: Int = 60): kotlinx.coroutines.flow.Flow<Pair<Pending?, Live>> =
        kotlinx.coroutines.flow.flow {
            val q = target.replace("'", "'\\''")
            val script = "trap 'exit' PIPE HUP TERM INT; prev=''; while :; do " +
                "cur=\$(tmux capture-pane -pt '$q' -S -$lines 2>/dev/null) || exit; " +
                "if [ \"\$cur\" != \"\$prev\" ]; then " +
                "printf '%s\\n$SCR_MARK\\n' \"\$cur\" || exit; prev=\$cur; fi; " +
                "sleep 0.2; done"
            val shell = session.openExecStream(script)
            val onCancel = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                ?.invokeOnCompletion { runCatching { shell.close() } }
            try {
                val reader = shell.output.bufferedReader()
                val buf = StringBuilder()
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break
                    if (line == SCR_MARK) {
                        // ⚠️ **在 IO 上就把屏幕解析完**再交给界面。放到主线程去解析的话，
                        // 它一忙起来屏幕每 0.2 秒变一次，主线程就一直在解析 + 重组 → ANR。
                        val screen = buf.toString()
                        emit(Prompt.parse(screen) to Live.parse(screen))
                        buf.setLength(0)
                    } else {
                        buf.append(line).append('\n')
                        if (buf.length > 200_000) buf.setLength(0)   // 兜底，别把内存撑爆
                    }
                }
            } finally {
                onCancel?.dispose()
                shell.close()
            }
        // ⚠️ **必须 flowOn(IO)。** `readLine()` 是阻塞调用，flow 体默认跑在**收集方的线程**上
        // ——收集方是 Compose 的 LaunchedEffect（主线程），于是整个界面卡死弹 ANR。
        // 实测就是这么撞出来的；[TranscriptStream] 那条流早就这么写了，我漏了。
        }
            // 主线程要是没跟上，丢掉中间那些帧只取最新的 —— 排队只会越积越卡
            .conflate()
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

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
