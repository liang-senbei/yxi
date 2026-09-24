package app.yxi.desktop

import org.json.JSONObject
import java.net.URI

/** Process-only credential overlay. Not sufficient by itself to verify subscription entitlement
 * or override managed/cloud policy; callers must verify the native provider before enabling send. */
internal object ClaudeSubscriptionSettings {
    /** Consumes get_settings.response, never raw settings files or a claimed provider label. */
    fun requireOfficialRoute(snapshot: JSONObject) {
        val effective = snapshot.optJSONObject("effective") ?: error("Claude 未返回有效配置，无法确认官方线路")
        val env = effective.optJSONObject("env") ?: error("Claude 未返回有效线路字段，无法确认官方线路")
        val endpoint = env.opt("ANTHROPIC_BASE_URL") as? String ?: error("Claude 请求地址尚未确认")
        val uri = runCatching { URI(endpoint) }.getOrNull()
        check(uri != null && uri.scheme == "https" && uri.host.equals("api.anthropic.com", true) &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath in setOf("", "/")) { "Claude 有效请求地址不是官方端点，请核对托管或线路配置" }
        fun absentOrEmpty(value: Any?) = value == null || value is String && value.isEmpty()
        val conflicts = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_PROFILE",
            "ANTHROPIC_FEDERATION_RULE_ID", "ANTHROPIC_IDENTITY_TOKEN_FILE", "ANTHROPIC_ORGANIZATION_ID") + cloudFlags
        check(conflicts.all { absentOrEmpty(env.opt(it)) }) { "Claude 有效配置仍包含其他认证或云平台字段，官方订阅未启用" }
        check(absentOrEmpty(effective.opt("apiKeyHelper"))) { "Claude 有效配置仍启用密钥助手，官方订阅未启用" }
        check(absentOrEmpty(effective.opt("forceLoginGatewayUrl"))) { "Claude 受网关登录策略约束，无法应用直连订阅配置" }
        val method = effective.opt("forceLoginMethod")
        check(absentOrEmpty(method) || method == "claudeai") { "Claude 登录策略与订阅配置不一致" }
    }
    /** Credential identity only: entitlement and endpoint policy need separate verification. */
    fun requireOAuthIdentity(status: JSONObject) {
        check(status.opt("loggedIn") == true) { "Claude 尚未确认原生登录，请先完成官方登录" }
        check(status.optString("apiProvider") == "firstParty") { "Claude 当前仍使用其他提供方，请核对云平台或托管配置" }
        check(status.optString("apiKeySource").isBlank()) { "Claude 仍检测到 API 凭据来源，官方订阅未启用；请核对托管或其他认证配置" }
        check(status.optString("authMethod") in setOf("oauth_token", "claude.ai")) { "Claude 当前认证方式尚未确认为 OAuth，官方订阅未启用" }
    }
    private val cloudFlags = setOf("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
    private val replaced = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL") + cloudFlags
    internal fun removedEnvironmentKeys() = replaced.toList()
    fun environment(inherited: Map<String, String>) = inherited.filterKeys { it.uppercase() !in replaced }
    fun overlay(): JSONObject = JSONObject().put("env", JSONObject()
        .put("ANTHROPIC_API_KEY", "").put("ANTHROPIC_AUTH_TOKEN", "")
        .put("ANTHROPIC_BASE_URL", "https://api.anthropic.com").apply { cloudFlags.forEach { put(it, "") } })
        .put("apiKeyHelper", "")
}
