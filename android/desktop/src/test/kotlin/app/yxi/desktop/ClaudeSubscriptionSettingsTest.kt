package app.yxi.desktop

import org.json.JSONObject
import kotlin.test.*

class ClaudeSubscriptionSettingsTest {
    private fun route() = JSONObject().put("effective", ClaudeSubscriptionSettings.overlay())
    @Test fun `effective route rejects alternate authorities paths and credentials without disclosing them`() {
        for (url in listOf("http://api.anthropic.com", "https://api.anthropic.com.attacker.invalid", "https://api.anthropic.com@attacker.invalid",
            "https://private-value@api.anthropic.com", "https://api.anthropic.com:8443", "https://api.anthropic.com/proxy", "https://api.anthropic.com/?key=private-value")) {
            val snapshot = route().apply { getJSONObject("effective").getJSONObject("env").put("ANTHROPIC_BASE_URL", url) }
            val failure = assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOfficialRoute(snapshot) }
            assertFalse(failure.message.orEmpty().contains("private-value"))
        }
        for (url in listOf("https://api.anthropic.com", "https://api.anthropic.com/", "https://api.anthropic.com:443")) {
            ClaudeSubscriptionSettings.requireOfficialRoute(route().apply { getJSONObject("effective").getJSONObject("env").put("ANTHROPIC_BASE_URL", url) })
        }
    }
    @Test fun `effective managed authentication overrides and missing data are rejected`() {
        for (key in listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_PROFILE", "ANTHROPIC_CUSTOM_HEADERS", "CLAUDE_CODE_USE_VERTEX")) {
            assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOfficialRoute(route().apply {
                getJSONObject("effective").getJSONObject("env").put(key, "private-value")
            }) }
        }
        for (key in listOf("apiKeyHelper", "forceLoginGatewayUrl", "forceLoginMethod")) {
            assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOfficialRoute(route().apply { getJSONObject("effective").put(key, "private-value") }) }
        }
        assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOfficialRoute(JSONObject()) }
        assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOfficialRoute(route().apply { getJSONObject("effective").getJSONObject("env").put("ANTHROPIC_API_KEY", false) }) }
        ClaudeSubscriptionSettings.requireOfficialRoute(route().apply { getJSONObject("effective").put("forceLoginMethod", "claudeai") })
    }
    private fun oauth() = JSONObject().put("loggedIn", true).put("apiProvider", "firstParty").put("authMethod", "oauth_token")
    @Test fun `mixed native status never counts as subscription identity`() {
        val mixed = oauth().put("apiKeySource", "ANTHROPIC_API_KEY")
        val failure = assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOAuthIdentity(mixed) }
        assertTrue(failure.message.orEmpty().contains("API"))
    }
    @Test fun `missing unknown and nonofficial native identities fail closed`() {
        for (status in listOf(JSONObject(), oauth().put("loggedIn", false), oauth().put("loggedIn", "true"),
            oauth().put("apiProvider", "bedrock"), oauth().put("authMethod", "api_key"), oauth().put("authMethod", "new-unknown-method"))) {
            assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOAuthIdentity(status) }
        }
        ClaudeSubscriptionSettings.requireOAuthIdentity(oauth())
        ClaudeSubscriptionSettings.requireOAuthIdentity(oauth().put("authMethod", "claude.ai"))
    }
    @Test fun `subscription overlay preserves unrelated settings and inherited environment`() {
        val inherited = mapOf("PATH" to "kept", "CLAUDE_CONFIG_DIR" to "native-home", "CLAUDE_CODE_OAUTH_TOKEN" to "fixture",
            "ANTHROPIC_API_KEY" to "conflict", "CLAUDE_CODE_USE_VERTEX" to "1")
        val selected = ClaudeSubscriptionSettings.environment(inherited)
        assertEquals("conflict", inherited["ANTHROPIC_API_KEY"])
        assertFalse(selected.containsKey("ANTHROPIC_API_KEY")); assertFalse(selected.containsKey("CLAUDE_CODE_USE_VERTEX"))
        assertEquals("native-home", selected["CLAUDE_CONFIG_DIR"]); assertEquals("kept", selected["PATH"])
        assertEquals("fixture", selected["CLAUDE_CODE_OAUTH_TOKEN"])
        val overlay = ClaudeSubscriptionSettings.overlay()
        assertEquals(setOf("env", "apiKeyHelper"), overlay.keySet())
        assertEquals("https://api.anthropic.com", overlay.getJSONObject("env").getString("ANTHROPIC_BASE_URL"))
    }
}
