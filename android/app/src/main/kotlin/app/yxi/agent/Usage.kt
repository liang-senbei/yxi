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
/** 「趋势」里的一天。[costUSD] 是**美元**，ccusage 自己按当天的价目表算好的。 */
data class Day(
    /** `YYYY-MM-DD`，服务器自己的日期 */
    val date: String,
    val tokens: Long,
    val costUSD: Double,
    /** 那天各个模型分别烧了多少 —— 按钱从多到少排给界面用 */
    val models: List<ModelCost>,
) {
    val tokenText: String get() = when {
        tokens >= 1_000_000_000 -> "%.1fB".format(tokens / 1e9)
        tokens >= 1_000_000 -> "%.0fM".format(tokens / 1e6)
        tokens >= 1_000 -> "%.0fK".format(tokens / 1e3)
        else -> tokens.toString()
    }
    /** `09-04` —— 图上的横坐标标签 */
    val short: String get() = date.removePrefix(date.take(5))
}

/** 一天里某个模型烧掉的量。 */
data class ModelCost(val name: String, val tokens: Long, val costUSD: Double)

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
                        // ⚠️ node 常常装在 /opt/node-*/bin 这种地方，既不在 PATH 里也不在 npm 的全局目录下。
                        // 本机实测就是这样：`command -v ccusage` 找不到，于是用量整块**默默不显示** ——
                        // 「探不到就藏起来」是对的，但**探得不够狠**就变成了功能形同虚设。
                        "for d in /opt/node*/bin /usr/lib/node_modules/.bin; do [ -d \"\$d\" ] && PATH=\$PATH:\$d; done; " +
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

        /**
         * 按天的用量曲线 —— 「趋势」那一页的数据（用户 2026-09-04 要的：
         * 「按天为横坐标记录每天的 token 用量变化，最好还能记录用的模型和花费的美元」）。
         *
         * 走 `ccusage daily --json`。schema **不是猜的**，是在本机真跑一遍对着看的：
         * 每天一条，`period` = `YYYY-MM-DD`、`totalTokens`、`totalCost`（美元）、
         * `modelsUsed`（那天用过哪些模型）、`modelBreakdowns`（每个模型各自的 token 和钱）。
         *
         * ⚠️ 跟这个文件里别的函数同一条规矩：**探不到 ccusage 就返回 null，调用方整块藏掉**。
         * ⚠️ 钱不自己算 —— 价目表随时在变，抄进 App 就会过期（见 [today] 的注释）。
         */
        suspend fun daily(ssh: SshSession, days: Int = 30): List<Day>? {
            val out = runCatching {
                ssh.exec(
                    "export PATH=\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH; " +
                        "for d in /opt/node*/bin /usr/lib/node_modules/.bin; do [ -d \"\$d\" ] && PATH=\$PATH:\$d; done; " +
                        "command -v ccusage >/dev/null 2>&1 || exit 0; " +
                        "ccusage daily --json 2>/dev/null"
                )
            }.getOrNull().orEmpty().trim()
            if (out.isEmpty() || !out.startsWith("{")) return null
            return runCatching {
                val arr = JSONObject(out).optJSONArray("daily") ?: return null
                val list = ArrayList<Day>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val date = o.optString("period").takeIf { it.isNotBlank() } ?: continue
                    val models = ArrayList<ModelCost>()
                    o.optJSONArray("modelBreakdowns")?.let { mb ->
                        for (j in 0 until mb.length()) {
                            val m = mb.optJSONObject(j) ?: continue
                            models += ModelCost(
                                name = m.optString("modelName").removePrefix("claude-"),
                                tokens = m.optLong("inputTokens") + m.optLong("outputTokens") +
                                    m.optLong("cacheCreationTokens") + m.optLong("cacheReadTokens"),
                                costUSD = m.optDouble("cost", 0.0),
                            )
                        }
                    }
                    list += Day(date, o.optLong("totalTokens"), o.optDouble("totalCost"), models)
                }
                list.takeLast(days)
            }.getOrNull()
        }

        /**
         * **今天**烧了多少（那台机器自己的当天，按它的时区算）。
         *
         * ⚠️ **日期在服务器上算**（`$(date +%Y%m%d)`），不是手机上算。
         * 手机和服务器不在同一个时区是常态 —— 用手机的日期会在跨零点前后
         * 取到隔壁那一天，数字忽大忽小，还查不出为什么。
         *
         * ⚠️ 跟 [probe] 一样：**探不到 ccusage 就返回 null，调用方整块藏掉**。
         * 额度和花费显示一个假的比不显示危险得多。
         *
         * ⚠️ 为什么不自己数转录：token 数好数，**钱不好算** ——
         * 要一张随时在变的模型价目表，抄进 app 里就会过期，
         * 然后你照着一个过时的价格决定今天要不要开大活。ccusage 自己维护那张表。
         */
        suspend fun today(ssh: SshSession): Today? {
            val out = runCatching {
                ssh.exec(
                    "export PATH=\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH; " +
                        // ⚠️ node 常常装在 /opt/node-*/bin 这种地方，既不在 PATH 里也不在 npm 的全局目录下。
                        // 本机实测就是这样：`command -v ccusage` 找不到，于是用量整块**默默不显示** ——
                        // 「探不到就藏起来」是对的，但**探得不够狠**就变成了功能形同虚设。
                        "for d in /opt/node*/bin /usr/lib/node_modules/.bin; do [ -d \"\$d\" ] && PATH=\$PATH:\$d; done; " +
                        "command -v ccusage >/dev/null 2>&1 || exit 0; " +
                        "ccusage daily --json --since \$(date +%Y%m%d) 2>/dev/null"
                )
            }.getOrNull().orEmpty().trim()
            if (!out.startsWith("{")) return null
            return runCatching {
                val tot = JSONObject(out).optJSONObject("totals") ?: return null
                Today(tot.optLong("totalTokens"), tot.optDouble("totalCost"))
            }.getOrNull()?.takeIf { it.tokens > 0 }
        }
    }
}

/** 今天的总量。⚠️ 拿不到就是 null —— 见 [Usage.today]。 */
data class Today(val tokens: Long, val costUSD: Double) {
    val tokenText: String get() = when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1e6)
        tokens >= 1_000 -> "%.0fK".format(tokens / 1e3)
        else -> tokens.toString()
    }
    /** ⚠️ 两位小数：一天烧掉个位数美元是常态，只显示整数会看见一串 `$0`。 */
    val costText: String get() = "$" + "%.2f".format(costUSD)
}
