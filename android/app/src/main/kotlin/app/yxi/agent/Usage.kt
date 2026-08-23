package app.yxi.agent

import app.yxi.ssh.SshSession
import org.json.JSONObject

/**
 * 用量：**读那台机器自己的 `~/.claude`**，所以天然按服务器分开
 * —— 你在 station 上烧掉的额度不会算进本机（PRD 附录 K）。
 *
 * ⚠️ **探测不到 `ccusage` 就什么都不显示。** 不显示 0，不显示「未知」，
 * 整块藏起来 —— 额度这种东西，**显示一个假的比不显示危险得多**：
 * 你会照着那个数字安排今天要不要开大活。
 */
data class Usage(
    /** 5 小时窗口还剩多少分钟 */
    val remainingMinutes: Int,
    /** 这个窗口已经过去的百分比（0–100） */
    val elapsedPercent: Int,
    val tokens: Long,
    val costUSD: Double,
    val tokensPerMinute: Double,
) {
    val remainText: String get() = "${remainingMinutes / 60}h${(remainingMinutes % 60).toString().padStart(2, '0')}m"
    val tokenText: String get() = when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1e6)
        tokens >= 1_000 -> "%.0fK".format(tokens / 1e3)
        else -> tokens.toString()
    }

    companion object {
        /**
         * @return null = 这台机器上没有 ccusage / 拿不到数据。**调用方必须把整块藏掉。**
         *
         * schema 不是我猜的，是照着本机 `~/.local/bin/cc-quota` 里那段能跑的 python 抄的
         * （它读的就是 `ccusage blocks --active --json`）。
         */
        suspend fun probe(ssh: SshSession): Usage? {
            // PATH 要补上 ~/.local/bin 和 npm 的全局目录 —— 非交互 shell 的 PATH 常常是残的
            val out = runCatching {
                ssh.exec(
                    "export PATH=\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH; " +
                        "command -v ccusage >/dev/null 2>&1 || exit 0; " +
                        "ccusage blocks --active --json 2>/dev/null"
                )
            }.getOrNull().orEmpty().trim()
            if (out.isEmpty() || !out.startsWith("{")) return null

            return runCatching {
                val b = JSONObject(out).optJSONArray("blocks")?.optJSONObject(0) ?: return null
                val proj = b.optJSONObject("projection")
                val tc = b.optJSONObject("tokenCounts")
                val br = b.optJSONObject("burnRate")
                val remain = proj?.optInt("remainingMinutes", -1) ?: -1
                // 5 小时 = 300 分钟；剩多少反推过了多少
                val pct = if (remain in 0..300) ((300 - remain) * 100 / 300) else 0
                Usage(
                    remainingMinutes = remain.coerceAtLeast(0),
                    elapsedPercent = pct,
                    tokens = b.optLong("totalTokens", 0L).takeIf { it > 0 }
                        ?: ((tc?.optLong("inputTokens") ?: 0L) + (tc?.optLong("outputTokens") ?: 0L)),
                    costUSD = b.optDouble("costUSD", 0.0),
                    tokensPerMinute = br?.optDouble("tokensPerMinute", 0.0) ?: 0.0,
                )
            }.getOrNull()
        }
    }
}
