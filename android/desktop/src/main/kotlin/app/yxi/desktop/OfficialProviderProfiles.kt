package app.yxi.desktop

/** Built-in identities are separate from saved API routes and from 'inherit external config'. */
internal data class OfficialProviderProfile(val id: String, val engine: String, val title: String, val description: String,
    val documentation: String, val subscription: Boolean = true)

internal object OfficialProviderProfiles {
    val entries = listOf(
        OfficialProviderProfile("official:claude", "claude", "Claude 官方订阅", "使用 Claude 原生账号登录；API Key 计费与订阅分开。", "https://code.claude.com/docs/en/authentication"),
        OfficialProviderProfile("official:codex", "codex", "ChatGPT 官方订阅", "使用 Codex 原生 ChatGPT 登录，不需要另填 API Key。", "https://learn.chatgpt.com/docs/auth"),
        OfficialProviderProfile("official:gemini", "gemini", "Gemini 官方企业订阅", "使用 Gemini Code Assist Standard / Enterprise 授权。个人免费、Pro/Ultra 的 Gemini CLI 服务已于 2026-06-18 停止，个人订阅需转用 Antigravity CLI；此处不会自动替换运行器。", "https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/"),
        OfficialProviderProfile("official:gemini-api", "gemini", "Gemini 官方 API", "使用官方付费 API，计费与个人订阅分开；不会因订阅不可用而自动切换到 API。", "https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/", subscription = false),
        OfficialProviderProfile("official:grok", "grok", "Grok 官方订阅", "使用 Grok Build 原生账号登录；实际权益由官方账号返回。", "https://docs.x.ai/build/overview"),
        OfficialProviderProfile("official:opencode-go", "opencode", "OpenCode Go 官方订阅", "OpenCode 官方订阅服务，使用其原生连接流程。", "https://opencode.ai/docs/go/"),
        OfficialProviderProfile("official:opencode-zen", "opencode", "OpenCode Zen 官方服务", "官方按量计费服务，与 Go 订阅分开显示。", "https://opencode.ai/docs/zen/", subscription = false),
        OfficialProviderProfile("official:hermes", "hermes", "Nous Portal 官方订阅", "Hermes 的官方模型与工具订阅入口；分别核对登录和实际提供方。", "https://hermes-agent.nousresearch.com/docs/guides/run-hermes-with-nous-portal"),
    )
    fun forEngine(engine: String) = entries.filter { it.engine == engine }
    fun defaultFor(engine: String) = forEngine(engine).firstOrNull()
}
