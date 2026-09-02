package app.yxi.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 从手机上给一台**什么都没装**的服务器一键装机：跑 `server/bootstrap.sh`
 * （tmux · Claude Code · Codex · yxi 服务器侧工具），脚本从公网下载页拿。
 *
 * ⚠️ **不靠 tmux。** 别的长流程（GitHub / MCP 登录）都跑在 tmux 里，但这一条要装的**正是 tmux** ——
 * 所以用 `nohup` 丢后台、输出落到 `~/.yxi/setup.log`，手机每秒 `tail` 一眼。
 * 手机切走、断网，装机照常进行；回来接着看日志。结束时脚本那一层追加 `__DONE__<退出码>`。
 *
 * ⚠️ 命令是纯字符串，好测；解析也在这儿。
 */
object Setup {

    const val URL = "https://yxi.keuury.com/bootstrap.sh"
    private const val LOG = "\$HOME/.yxi/setup.log"

    // ── 探活 ──

    /**
     * 这台机器装了什么、登没登录、什么系统。用户要的「探测 Claude 与 Codex」按钮走它。
     * ⚠️ 别掺 `claude mcp list`（会挨个探活 MCP，几秒）—— 这条只读本地文件，秒回。
     */
    const val PROBE_COMMAND =
        "echo __TMUX__; command -v tmux >/dev/null 2>&1 && tmux -V 2>/dev/null || echo NO; " +
            "echo __CLAUDE__; if command -v claude >/dev/null 2>&1; then claude --version 2>/dev/null | head -1; " +
            "claude auth status --json 2>/dev/null; else echo NO; fi; " +
            "echo __CODEX__; if command -v codex >/dev/null 2>&1; then codex --version 2>/dev/null; codex login status 2>&1; else echo NO; fi; " +
            "echo __NODE__; command -v node >/dev/null 2>&1 && node --version 2>/dev/null || echo NO; " +
            "echo __OS__; (. /etc/os-release 2>/dev/null && echo \"\$PRETTY_NAME\"); uname -m; id -u; echo __END__"

    data class Probe(
        /** 版本串；null = 没装 */
        val tmux: String?,
        val claude: String?,
        /** 登录的账号（邮箱 / 订阅档）；null = 没登录或没装 */
        val claudeUser: String?,
        val codex: String?,
        val codexLogged: Boolean,
        val node: String?,
        val os: String,
        val arch: String,
        val root: Boolean,
    ) {
        val claudeInstalled get() = claude != null
        val codexInstalled get() = codex != null
        val nothing get() = claude == null && codex == null
    }

    /** 读 [PROBE_COMMAND] 的输出。缺结尾标记（连接半断）= null。 */
    fun parseProbe(out: String): Probe? {
        if (!out.contains("__END__")) return null
        fun sec(a: String, b: String) = out.substringAfter(a, "").substringBefore(b).trim()
        fun ver(s: String): String? = s.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeIf { it != "NO" }
        val cl = sec("__CLAUDE__", "__CODEX__")
        val cx = sec("__CODEX__", "__NODE__")
        val osLines = sec("__OS__", "__END__").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val claudeUser = runCatching {
            val j = JSONObject(cl.substring(cl.indexOf('{')))
            if (!j.optBoolean("loggedIn")) null
            else j.optString("email").ifBlank { j.optString("subscriptionType").ifBlank { j.optString("authMethod").ifBlank { "已登录" } } }
        }.getOrNull()
        return Probe(
            tmux = ver(sec("__TMUX__", "__CLAUDE__"))?.removePrefix("tmux "),
            claude = ver(cl)?.removeSuffix(" (Claude Code)"),
            claudeUser = claudeUser,
            codex = ver(cx)?.removePrefix("codex-cli "),
            codexLogged = cx.contains("Logged in", ignoreCase = true) && !cx.contains("Not logged in", ignoreCase = true),
            node = ver(sec("__NODE__", "__OS__")),
            os = osLines.getOrNull(0).orEmpty(),
            arch = osLines.getOrNull(1).orEmpty(),
            root = osLines.getOrNull(2) == "0",
        )
    }

    // ── 装机 ──

    /**
     * 手机**自己**去公网把脚本拿下来，再从 SSH 塞进服务器。
     *
     * ⚠️ **为什么不让服务器自己 curl。** 全新的 Ubuntu 24.04 镜像里 curl、wget、python3 **一个都没有**
     * （容器里实测，#215）—— 「服务器上 curl 一下」这条路在最需要它的机器上恰恰走不通。
     * 手机有网（它刚查过更新），脚本才 6KB，走 heredoc 过去最稳。拿不到（手机没外网）再退回服务器侧 curl / wget。
     * @return 脚本原文；null = 没拿到
     */
    suspend fun fetchScript(): String? = withContext(Dispatchers.IO) {
        runCatching {
            (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 20_000
            }.inputStream.use { it.readBytes().decodeToString() }
        }.getOrNull()?.takeIf { it.startsWith("#!") && it.contains("bootstrap") && !it.contains(EOF) }
    }

    private const val EOF = "YXI_BOOTSTRAP_EOF"

    /**
     * 起装机。有脚本原文就 heredoc 写到 `~/.yxi/bootstrap.sh` 再跑；没有就让服务器自己 curl / wget。
     * [claude] / [codex] = 装哪个（用户的表单：都装 / 只装一个）；tmux 和 Yxi 钩子总是装。
     * ⚠️ `$?` 在单引号里，由内层 bash 求值；退路那条要 `pipefail` —— 否则 curl 都没有、管道右边的 bash 读到空输入
     *    照样退出 0，手机上会看到一个「装好了」（#215 第一次就是这么骗过去的）。
     */
    fun startCommand(script: String?, claude: Boolean = true, codex: Boolean = true): String {
        val env = (if (codex) "" else "YXI_NO_CODEX=1 ") + (if (claude) "" else "YXI_NO_CLAUDE=1 ")
        val run = if (script != null) {
            "cat > \$HOME/.yxi/bootstrap.sh <<'$EOF'\n$script\n$EOF\n" +
                "nohup bash -c '${env}bash \$HOME/.yxi/bootstrap.sh; echo __DONE__\$?' >> $LOG 2>&1 </dev/null & "
        } else {
            "nohup bash -c 'set -o pipefail; ( (command -v curl >/dev/null 2>&1 && curl -fsSL $URL || wget -qO- $URL) | ${env}bash ); " +
                "echo __DONE__\$?' >> $LOG 2>&1 </dev/null & "
        }
        return "mkdir -p \$HOME/.yxi; : > $LOG; ${run}echo started"
    }

    /**
     * 看日志尾巴。**20 分钟没动过的日志当没有** —— 那是上次装机留下的，不是这次的进度。
     * 输出为空 = 没在装（或从没装过）。
     */
    fun tailCommand(): String =
        "f=$LOG; if [ -f \"\$f\" ] && [ -n \"\$(find \"\$f\" -mmin -20 2>/dev/null)\" ]; then tail -n 14 \"\$f\"; fi"

    /** 日志里显示还在跑（有内容、没结束标记）—— 手机切走再回来时**接着看**，别再起一个。 */
    fun running(tail: String): Boolean = tail.isNotBlank() && !tail.contains("__DONE__")

    /** null = 还在装；true = 成功；false = 有几项没装成 */
    fun done(tail: String): Boolean? =
        Regex("""__DONE__(\d+)""").find(tail)?.let { it.groupValues[1] == "0" }

    /** 给人看的日志：去掉结束标记那行、去掉空行 */
    fun pretty(tail: String): String =
        tail.lines().filter { it.isNotBlank() && !it.contains("__DONE__") }.joinToString("\n")
}
