package app.yxi.agent

import app.yxi.ssh.SshSession

/**
 * 真正的**订阅额度**：5 小时窗口用了几成、这一周用了几成、什么时候重置。
 *
 * ⚠️ **这个数只有 Claude Code 自己拿得到，转录里没有、本地日志也算不出来。**
 * 它是账号级的订阅配额，来自 Anthropic 服务端。
 * [Usage]（走 ccusage）算的是**另一回事** —— 从本地日志算出的 token 数和花的钱，
 * 那个不知道你的订阅用掉了百分之几。两个数都要，别混。
 *
 * ⚠️ `/usage` 的输出**只在 TUI 上**，不落转录。所以只能刮屏。
 *
 * **代价小到出乎意料**（实测确认过，不是猜的）：
 *   · `/usage` 是本地命令，**不调模型** —— 跑一次 `Total cost: $0.0000`
 *   · **不写转录** —— 临时会话跑完，`~/.claude/projects` 下压根没建目录
 *   · 面板是个浮层，`Esc` 一按就没
 * 所以「点一下就去跑一次」这条路是干净的。
 */
object Quota {

    data class Q(
        /** 5 小时窗口用掉的百分比 */
        val sessionPct: Int,
        val sessionResets: String,
        /** 本周（所有模型）用掉的百分比 */
        val weekPct: Int,
        val weekResets: String,
    )

    /** `   █████                       10% used` → 10 */
    private val PCT = Regex("""(\d{1,3})%\s+used""")
    private val RESETS = Regex("""^\s*Resets\s+(.+?)\s*$""")

    /**
     * 从 `/usage` 面板的屏幕文本里解出额度。
     *
     * ⚠️ **锚点是「Current session」「Current week (all models)」这两行标题，
     * 不是行号。** 面板上还有 `Current week (Fable)` 之类的分项，也带 `% used`；
     * 按顺序数第几个 `% used` 的话，账号一开新模型就全错位。
     *
     * ⚠️ 百分比和重置时间在标题**下面几行**，中间隔着进度条。
     * 所以从标题往下找最近的一个，而不是取标题同一行。
     */
    fun parse(screen: String): Q? {
        val lines = screen.split('\n')
        fun after(title: String): Pair<Int, String>? {
            val i = lines.indexOfFirst { it.trim() == title }
            if (i < 0) return null
            var pct: Int? = null
            var resets = ""
            // 往下最多看 4 行 —— 再远就是下一段了
            for (j in i + 1..minOf(i + 4, lines.lastIndex)) {
                if (pct == null) PCT.find(lines[j])?.let { pct = it.groupValues[1].toIntOrNull() }
                if (resets.isEmpty()) RESETS.find(lines[j])?.let { resets = it.groupValues[1] }
            }
            return pct?.let { it to resets }
        }
        val s = after("Current session") ?: return null
        val w = after("Current week (all models)") ?: return null
        return Q(s.first, s.second, w.first, w.second)
    }

    /**
     * 在一个**已经在跑、而且闲着**的会话里跑一次 `/usage`，把额度刮回来。
     *
     * ⚠️ **必须先确认输入框是空的。** 输入框里有半截草稿的话，`/usage` 会接在后面，
     * 回车就把「用户没写完的话 + /usage」整条发出去了 —— 那是真正会造成损失的一步。
     * 判据交给调用方（[Live] 能看出忙不忙，输入框内容看屏幕）。
     *
     * ⚠️ 跑完**一定要 Esc**，否则那个面板一直盖在会话上，
     * 下一次抓屏（看板每 5 秒一次）看到的就是面板，
     * 会话状态会被判错 —— 表现成列表里那台机器无缘无故变「空闲」。
     */
    suspend fun probe(ssh: SshSession, target: String): Q? {
        ssh.exec("tmux send-keys -t '$target' -l '/usage'")
        ssh.exec("tmux send-keys -t '$target' Enter")
        kotlinx.coroutines.delay(2_500)
        val screen = ssh.exec("tmux capture-pane -p -t '$target'")
        ssh.exec("tmux send-keys -t '$target' Escape")
        return parse(screen)
    }
}
