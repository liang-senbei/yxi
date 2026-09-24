package app.yxi.desktop

import org.json.JSONObject
import kotlin.test.*

class ClaudeSubscriptionSettingsTest {
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
