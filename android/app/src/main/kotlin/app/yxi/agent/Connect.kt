package app.yxi.agent

import app.yxi.ssh.SshSession

/**
 * **连接** —— 把第三方服务接给连着那台机器上的 agent（Claude Code / Codex）。
 *
 * 两条路，都不用在电脑上操作：
 *  · **GitHub**：`gh auth login` 的设备码流程。手机上打开 github.com/login/device、输一个码，
 *    服务器上 `gh` 自己收到 token。一次登录，**git 推拉 + GitHub MCP** 一起有
 *    （`gh` 顺手把自己配成 git 的凭据助手，Codex 也就能 push 了）。
 *  · **远程 MCP**（Notion / Linear / Sentry …）：`claude mcp add` + `claude mcp login --no-browser`。
 *    授权页在手机浏览器里开，授权完服务商把浏览器**重定向到 localhost:<端口>/callback** ——
 *    那是服务器上 Claude Code 临时起的回调口。手机这边先用 SSH **本地端口转发**把同一个端口接过去
 *    （[SshSession.forwardLocal]），浏览器打 localhost 就直接到了服务器，一步都不用手动粘。
 *    转发不成（ROM 限制之类）还有退路：把浏览器地址栏那串 localhost 地址粘回来。
 *
 * ⚠️ **全程在服务器的 tmux 会话里跑**，手机只看屏幕（`capture-pane`）和送键 —— 跟看板一个套路。
 * 好处是手机切走、断网，服务器那头照样等着；回来 [status] 一刷就是最新状态。
 *
 * ⚠️ **只在 API key 之外的事上说实话。** Gmail / Slack / 日历这些是 **claude.ai 连接器**，
 * 只有 claude.ai 订阅登录的 Claude Code 才加载；用 API key 的一律没有（官方文档写死的）。
 * 列出来是为了告诉用户「为什么没有」，不是假装能连。
 */
object Connect {

    enum class Kind { GH, MCP, INFO }

    data class Service(
        val key: String,
        val name: String,
        /** 一句话：接上之后 agent 能干什么 */
        val what: String,
        val url: String = "",
        val transport: String = "http",
        val kind: Kind = Kind.MCP,
        /** 不用登录就能用（加上即可） */
        val noAuth: Boolean = false,
    )

    /** ⚠️ 地址都在服务器上探过活（2026-09-02，401 = 在线要认证，200 = 免认证）。 */
    val CATALOG = listOf(
        Service("github", "GitHub", "git 推拉、仓库 / PR / Issue", kind = Kind.GH),
        Service("notion", "Notion", "读写页面和数据库", "https://mcp.notion.com/mcp"),
        Service("linear", "Linear", "工单", "https://mcp.linear.app/mcp"),
        Service("sentry", "Sentry", "错误监控", "https://mcp.sentry.dev/mcp"),
        Service("atlassian", "Jira / Confluence", "Atlassian", "https://mcp.atlassian.com/v1/sse", "sse"),
        Service("asana", "Asana", "任务", "https://mcp.asana.com/sse", "sse"),
        Service("stripe", "Stripe", "支付", "https://mcp.stripe.com"),
        Service("paypal", "PayPal", "支付", "https://mcp.paypal.com/mcp"),
        Service("zapier", "Zapier", "几千个应用的自动化", "https://mcp.zapier.com/api/mcp/mcp"),
        Service("vercel", "Vercel", "部署", "https://mcp.vercel.com"),
        Service("netlify", "Netlify", "部署", "https://netlify-mcp.netlify.app/mcp"),
        Service("cloudflare", "Cloudflare", "域名 / Workers", "https://mcp.cloudflare.com/mcp"),
        Service("supabase", "Supabase", "数据库", "https://mcp.supabase.com/mcp"),
        Service("figma", "Figma", "设计稿", "https://mcp.figma.com/mcp"),
        Service("canva", "Canva", "设计", "https://mcp.canva.com/mcp"),
        Service("intercom", "Intercom", "客服", "https://mcp.intercom.com/mcp"),
        Service("monday", "monday.com", "项目", "https://mcp.monday.com/sse", "sse"),
        Service("box", "Box", "文件", "https://mcp.box.com"),
        Service("huggingface", "Hugging Face", "模型 / 数据集", "https://huggingface.co/mcp", noAuth = true),
        Service("context7", "Context7", "各种库的最新文档", "https://mcp.context7.com/mcp", noAuth = true),
        Service("deepwiki", "DeepWiki", "读任何 GitHub 仓库的文档", "https://mcp.deepwiki.com/mcp", noAuth = true),
        Service("claudeai", "Gmail / 日历 / Slack …", "只有 claude.ai 订阅登录才有（claude.ai 连接器），用 API key 的没有", kind = Kind.INFO),
        Service("chrome", "Claude in Chrome", "浏览器插件只配同一台电脑上的 Claude Code，远程服务器用不了", kind = Kind.INFO),
    )

    enum class State { CONNECTED, NEEDS_AUTH, FAILED, ABSENT }

    data class Status(
        val ghUser: String? = null,
        val ghInstalled: Boolean = true,
        val claudeInstalled: Boolean = true,
        /** MCP 名 → 状态 */
        val mcp: Map<String, State> = emptyMap(),
    ) {
        fun of(s: Service): State = when (s.kind) {
            Kind.GH -> if (ghUser != null) State.CONNECTED else State.ABSENT
            Kind.MCP -> mcp[s.key] ?: State.ABSENT
            Kind.INFO -> State.ABSENT
        }
    }

    // MARK: 命令。全是纯字符串，好测。

    /** 一趟拿全部：gh 登了没、Claude Code 的 MCP 各是什么状态。⚠️ `claude mcp list` 会挨个探活，几秒。 */
    const val STATUS_COMMAND =
        "echo __GH__; command -v gh >/dev/null 2>&1 && gh auth status -h github.com 2>&1 || echo NO_GH; " +
            "echo __MCP__; command -v claude >/dev/null 2>&1 && claude mcp list 2>/dev/null || echo NO_CLAUDE; echo __END__"

    fun parseStatus(out: String): Status {
        val gh = out.substringAfter("__GH__", "").substringBefore("__MCP__")
        val mcpText = out.substringAfter("__MCP__", "").substringBefore("__END__")
        val user = Regex("""Logged in to github\.com account (\S+)""").find(gh)?.groupValues?.get(1)
        val mcp = HashMap<String, State>()
        for (line in mcpText.lines()) {
            val m = MCP_LINE.find(line.trim()) ?: continue
            val tail = m.groupValues[3]
            mcp[m.groupValues[1]] = when {
                tail.contains("Needs authentication", ignoreCase = true) -> State.NEEDS_AUTH
                tail.contains("Connected", ignoreCase = true) && !tail.contains("Failed", ignoreCase = true) -> State.CONNECTED
                else -> State.FAILED
            }
        }
        return Status(
            ghUser = user,
            ghInstalled = !gh.contains("NO_GH"),
            claudeInstalled = !mcpText.contains("NO_CLAUDE"),
            mcp = mcp,
        )
    }

    /** `notion: https://mcp.notion.com/mcp (HTTP) - ✓ Connected` */
    private val MCP_LINE = Regex("""^([^:\s]+): (\S+ \(\w+\)) - (.+)$""")

    fun tmuxFor(key: String) = "yxi-auth-" + key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** 看那个 tmux 会话的屏幕。`-J` 把折行接回去 —— 授权 URL 有三百多个字符，必然折。 */
    fun peekCommand(tmux: String) = "tmux capture-pane -p -J -t '$tmux' 2>/dev/null"
    fun enterCommand(tmux: String) = "tmux send-keys -t '$tmux' Enter"
    fun killCommand(tmux: String) = "tmux kill-session -t '$tmux' 2>/dev/null; true"

    /**
     * 起 GitHub 设备码登录。`-p https -w`：https 协议 + 浏览器（设备码）流程。
     * 结束后 `echo __DONE__<退出码>` 留在屏幕上给 [parseDone] 看，`sleep` 是为了让屏幕别立刻消失。
     */
    fun ghLoginStart(): String {
        val t = tmuxFor("github")
        return "tmux kill-session -t '$t' 2>/dev/null; tmux new-session -d -s '$t' -x 120 -y 30 " +
            "'BROWSER=true gh auth login -h github.com -p https -w; echo __DONE__\$?; sleep 900'"
    }

    /** 屏幕上是不是还在问「Authenticate Git with your GitHub credentials? (Y/n)」—— 要回一个 Enter（= Yes，让 git 也用它） */
    fun ghAsksGit(pane: String) = pane.contains("(Y/n)")
    /** 一次性码 */
    fun ghCode(pane: String): String? = Regex("""one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})""").find(pane)?.groupValues?.get(1)
    /** 「Press Enter to open … in your browser」—— 服务器上没浏览器，回个 Enter 让它继续等 */
    fun ghAsksOpen(pane: String) = pane.contains("Press Enter to open")
    const val GH_DEVICE_URL = "https://github.com/login/device"

    /**
     * 登录成功之后：把 gh 配成 git 的凭据助手（`-p https` 时通常已经配了，再跑一遍无害），
     * 再把 GitHub 的官方远程 MCP 加给 Claude Code，token 用 gh 的 —— 一次登录两样都有。
     * ⚠️ 已经有同名 MCP 就先删再加，`claude mcp add` 遇到同名直接报错。
     */
    const val GH_AFTER_COMMAND =
        "gh auth setup-git >/dev/null 2>&1; T=\$(gh auth token 2>/dev/null); " +
            "if [ -n \"\$T\" ] && command -v claude >/dev/null 2>&1; then " +
            "claude mcp remove -s user github >/dev/null 2>&1; " +
            "claude mcp add --transport http -s user github https://api.githubcopilot.com/mcp/ " +
            "--header \"Authorization: Bearer \$T\" >/dev/null 2>&1; fi; echo ok"

    fun ghLogout() = "gh auth logout -h github.com >/dev/null 2>&1; claude mcp remove -s user github >/dev/null 2>&1; echo ok"

    /** 加一个远程 MCP（用户级，所有项目都有）。已存在就当成功。 */
    fun mcpAdd(s: Service): String =
        "claude mcp get ${shq(s.key)} >/dev/null 2>&1 || claude mcp add --transport ${s.transport} -s user ${shq(s.key)} ${shq(s.url)} 2>&1 | tail -1"

    fun mcpRemove(key: String) = "claude mcp remove -s user ${shq(key)} 2>&1 | tail -1"

    /** 起授权流程。`--no-browser`：把授权 URL 打在屏幕上而不是开浏览器（服务器上没浏览器）。 */
    fun mcpLoginStart(key: String): String {
        val t = tmuxFor(key)
        return "tmux kill-session -t '$t' 2>/dev/null; tmux new-session -d -s '$t' -x 220 -y 40 " +
            "'claude mcp login ${shq(key)} --no-browser; echo __DONE__\$?; sleep 900'"
    }

    /** 屏幕上的授权 URL（含 redirect_uri 那条），没出来就 null。 */
    fun loginUrl(pane: String): String? =
        Regex("""https://\S+redirect_uri\S*""").find(pane)?.value?.trimEnd('.', ',', ')')

    /** 回调端口：`redirect_uri=http%3A%2F%2Flocalhost%3A64202%2Fcallback` */
    fun callbackPort(url: String): Int? =
        Regex("""localhost(?:%3A|:)(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** 退路：把浏览器地址栏那串 localhost 地址粘回去（Claude Code 提示 `Or paste the redirect URL here:`）。 */
    fun pasteCommand(key: String, redirectUrl: String): String {
        val t = tmuxFor(key)
        // ⚠️ 文本和回车分两条、中间 sleep：连着送会被当成粘贴块，回车吞进去（#174）
        return "tmux send-keys -t '$t' -l ${shq(redirectUrl)}; sleep 0.4; tmux send-keys -t '$t' Enter"
    }

    /** 结束了没：null = 还在等；true = 成功；false = 失败（原因见 [failReason]）。 */
    fun parseDone(pane: String): Boolean? {
        val m = Regex("""__DONE__(\d+)""").find(pane) ?: return null
        return m.groupValues[1] == "0"
    }

    fun failReason(pane: String): String =
        pane.lines().lastOrNull { it.contains("Couldn't complete") || it.contains("error", ignoreCase = true) }
            ?.trim()?.take(160) ?: "没成功"

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    suspend fun status(ssh: SshSession?): Status? {
        val out = ssh?.exec(STATUS_COMMAND) ?: return null
        return parseStatus(out)
    }
}
