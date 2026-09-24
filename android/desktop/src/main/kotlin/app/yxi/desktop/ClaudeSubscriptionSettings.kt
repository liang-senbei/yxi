package app.yxi.desktop

import org.json.JSONObject

/** Process-only credential overlay. Not sufficient by itself to verify subscription entitlement
 * or override managed/cloud policy; callers must verify the native provider before enabling send. */
internal object ClaudeSubscriptionSettings {
    /** Credential identity only: entitlement and endpoint policy need separate verification. */
    fun requireOAuthIdentity(status: JSONObject) {
        check(status.opt("loggedIn") == true) { "Claude 尚未确认原生登录，请先完成官方登录" }
        check(status.optString("apiProvider") == "firstParty") { "Claude 当前仍使用其他提供方，请核对云平台或托管配置" }
        check(status.optString("apiKeySource").isBlank()) { "Claude 仍检测到 API 凭据来源，官方订阅未启用；请核对托管或其他认证配置" }
        check(status.optString("authMethod") in setOf("oauth_token", "claude.ai")) { "Claude 当前认证方式尚未确认为 OAuth，官方订阅未启用" }
    }
    private val cloudFlags = setOf("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
    private val replaced = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL") + cloudFlags
    fun environment(inherited: Map<String, String>) = inherited.filterKeys { it.uppercase() !in replaced }
    fun overlay(): JSONObject = JSONObject().put("env", JSONObject()
        .put("ANTHROPIC_API_KEY", "").put("ANTHROPIC_AUTH_TOKEN", "")
        .put("ANTHROPIC_BASE_URL", "https://api.anthropic.com").apply { cloudFlags.forEach { put(it, "") } })
        .put("apiKeyHelper", "")
}
