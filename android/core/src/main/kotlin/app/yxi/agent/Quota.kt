package app.yxi.agent

import app.yxi.ssh.SshSession

/**
 * 真正的**订阅额度**：5 小时窗口用了几成、这一周用了几成、什么时候重置。
 *
 * ⚠️ **这个数只有 Claude Code 自己拿得到**，转录里没有、本地日志也算不出来 ——
 * 它是账号级的订阅配额，来自 Anthropic 服务端。[Usage]（走 ccusage）算的是
 * **另一回事**：本地日志算出的 token 数和花的钱，不知道订阅用掉了几成。两个都要，别混。
 *
 * ⚠️ **拿法是 `claude -p "/usage"`**（非交互打印模式），不是去某个会话里刮屏。
 * 这条路好在三点，实测确认过：
 *   · **不用借会话** —— 早先的做法要借一个空闲会话跑 `/usage`，
 *     可用户机器上会话全忙 / 输入框都有草稿时借不到，额度就查不了（用户真撞上了）。
 *   · **不打扰任何东西** —— 不往任何会话发键、不动任何 tmux 面板。
 *   · **不花钱** —— `/usage` 是本地命令，`-p` 模式打印完就退，不调模型。
 */
object Quota {

    data class Q(
        /** 5 小时窗口用掉的百分比 */
        val sessionPct: Int,
        val sessionResets: String,
        /** 本周（所有模型）用掉的百分比 */
        val weekPct: Int,
        val weekResets: String,
        /** 订阅档位，如 `Max 20x` / `Max 5x` / `Pro`。读不到就空串。 */
        val plan: String = "",
    )

    /** `Current session: 12% used · resets Aug 24, 3:10pm (UTC)` */
    private val SESSION = Regex("""Current session:\s*(\d{1,3})%\s*used(?:\s*·\s*resets\s*(.+))?""")
    private val WEEK = Regex("""Current week \(all models\):\s*(\d{1,3})%\s*used(?:\s*·\s*resets\s*(.+))?""")

    /**
     * 从 `claude -p "/usage"` 的输出里解出额度。**格式不对就返回 null。**
     *
     * ⚠️ **锚点是两行标题的文字**，不是行号也不是「第几个 %」——
     * 输出里还有 `Current week (Fable)` 之类的分项，也带 `% used`。
     */
    /** `default_claude_max_20x` → `Max 20x`；含 pro → `Pro`；否则拿 subscriptionType 兜底。 */
    private val TIER = Regex(""""rateLimitTier"\s*:\s*"([^"]*)"""")
    private val SUBTYPE = Regex(""""subscriptionType"\s*:\s*"([^"]*)"""")
    private val MAXN = Regex("""max_?(\d+)x""")

    internal fun planOf(credsJson: String): String {
        val tier = TIER.find(credsJson)?.groupValues?.get(1).orEmpty()
        MAXN.find(tier)?.let { return "Max ${it.groupValues[1]}x" }
        if ("pro" in tier) return "Pro"
        // 兜底：subscriptionType（max / pro）
        return when (SUBTYPE.find(credsJson)?.groupValues?.get(1)) {
            "max" -> "Max"; "pro" -> "Pro"; else -> ""
        }
    }

    fun parse(text: String): Q? {
        val s = SESSION.find(text) ?: return null
        val w = WEEK.find(text) ?: return null
        return Q(
            sessionPct = s.groupValues[1].toIntOrNull() ?: return null,
            sessionResets = s.groupValues.getOrNull(2)?.trim().orEmpty(),
            weekPct = w.groupValues[1].toIntOrNull() ?: return null,
            weekResets = w.groupValues.getOrNull(2)?.trim().orEmpty(),
            // 档位藏在同一段输出里（fetch 把 credentials 一起 cat 出来了）
            plan = planOf(text),
        )
    }

    /**
     * 跑一次 `claude -p "/usage"` 把额度拿回来。**不碰任何会话。**
     *
     * ⚠️ PATH 要补上 `~/.local/bin`（claude 在那儿）和 node ——
     * 非交互 SSH 的 PATH 常常是残的，而 `claude` 是个 node 脚本。
     * ⚠️ **别加 `IS_SANDBOX`**：那是给交互式 `--dangerously-skip-permissions` 用的，
     * `-p` 打印模式不需要，加了反而可能改变行为。
     */
    /** 查不到时的**真原因**。原来界面上一律显示「没有空闲会话可借来查额度」——
     *  而这条路根本不借会话（下面 [fetch] 是直接 exec），那句话是错的。
     *  说错原因比不说更糟：用户会去关会话，然后发现没用。 */
    sealed interface Why {
        /** 服务器上没有 `claude` 这个命令 */
        object NoClaude : Why
        /** `claude -p '/usage'` 30 秒没回来（它要连 API，慢是常态） */
        object Timeout : Why
        /** 有输出但认不出来 —— 多半是 Claude Code 换了 `/usage` 的排版 */
        object Unparsable : Why
        data class Failed(val message: String) : Why
    }

    /** 成功给 Q，失败给 [Why]。 */
    suspend fun fetchDetailed(ssh: SshSession): Pair<Q?, Why?> {
        // ⚠️ 用一个**标记**区分「命令没跑成」和「跑了但没输出」——
        // 光看输出空不空分不出「claude 没装」和「超时」。
        val out = runCatching { ssh.exec(probe()) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            return null to Why.Failed(it.message.orEmpty().take(60))
        }
        if (NO_CLAUDE in out) return null to Why.NoClaude
        if (out.isBlank()) return null to Why.Timeout
        val q = parse(out) ?: return null to Why.Unparsable
        return q to null
    }

    /** 服务器上没有 claude 时会打出来的标记。挑一个正常输出里不会出现的串。 */
    private const val NO_CLAUDE = "__YXI_NO_CLAUDE__"

    /** 探测命令。抽出来是为了 [fetchDetailed] 和 [fetch] 用同一份。 */
    private fun probe() =
        "export PATH=\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH; " +
            "for d in /opt/node*/bin; do [ -d \"\$d\" ] && PATH=\$PATH:\$d; done; " +
            "command -v claude >/dev/null 2>&1 || { echo $NO_CLAUDE; exit 0; }; " +
            // ⚠️ 顺手把档位读出来（Max 5x/20x/Pro 藏在 credentials 里，/usage 不给）。
            // 只 grep rateLimitTier/subscriptionType 两个字段，**绝不整个 cat**——
            // 那文件里还有 access/refresh token，不该出现在任何日志或抓屏里。
            "grep -oE '\"(rateLimitTier|subscriptionType)\":\"[^\"]*\"' \$HOME/.claude/.credentials.json 2>/dev/null; " +
            "timeout 30 claude -p '/usage' 2>/dev/null"

    suspend fun fetch(ssh: SshSession): Q? = fetchDetailed(ssh).first
}
