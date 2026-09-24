package app.yxi.desktop

import org.json.JSONObject

/** Process-only credential overlay. Not sufficient by itself to verify subscription entitlement
 * or override managed/cloud policy; callers must verify the native provider before enabling send. */
internal object ClaudeSubscriptionSettings {
    private val cloudFlags = setOf("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
    private val replaced = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL") + cloudFlags
    fun environment(inherited: Map<String, String>) = inherited.filterKeys { it.uppercase() !in replaced }
    fun overlay(): JSONObject = JSONObject().put("env", JSONObject()
        .put("ANTHROPIC_API_KEY", "").put("ANTHROPIC_AUTH_TOKEN", "")
        .put("ANTHROPIC_BASE_URL", "https://api.anthropic.com").apply { cloudFlags.forEach { put(it, "") } })
        .put("apiKeyHelper", "")
}
