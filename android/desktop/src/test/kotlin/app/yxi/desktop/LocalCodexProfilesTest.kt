package app.yxi.desktop

import org.json.JSONObject
import kotlin.test.*

class LocalCodexProfilesTest {
    @Test fun `shared MCP verification rejects changed endpoints disabled entries and inherited credentials`() {
        val definition = SharedMcpDefinition("@local", "echo", "test", "1", "echo", listOf("/bin/echo"))
        val records = listOf(SharedMcpRecord(definition, setOf("codex"), 0))
        val config = SharedMcpSettings.forRunner(definition, "codex")
        LocalCodexProfiles.verifySharedMcp(config, records)
        val entry = config.getJSONObject("mcp_servers").getJSONObject("echo")
        entry.put("enabled", false)
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifySharedMcp(config, records) }
        entry.put("enabled", true).put("env", JSONObject().put("SECRET", "fixture"))
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifySharedMcp(config, records) }
        entry.remove("env"); entry.put("command", "/different")
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifySharedMcp(config, records) }
    }
    private fun config() = JSONObject().put("model_provider", "openai").put("openai_base_url", "").put("chatgpt_base_url", LocalCodexProfiles.chatgptBase)
    private fun account(type: String) = JSONObject().put("requiresOpenaiAuth", true).put("account", JSONObject().put("type", type))
    @Test fun `official selection removes inherited credentials without forcing logout or changing data home`() {
        val original = mapOf("PATH" to "/bin", "CODEX_HOME" to "/native", "openai_api_key" to "fixture", "OPENAI_BASE_URL" to "http://wrong", "CODEX_API_KEY" to "fixture2")
        val clean = LocalCodexProfiles.officialEnvironment(original)
        assertEquals(mapOf("PATH" to "/bin", "CODEX_HOME" to "/native"), clean)
        assertEquals(5, original.size)
        assertTrue(LocalCodexProfiles.officialArguments().none { "forced_login_method" in it })
    }
    @Test fun `official label requires both verified routing and ChatGPT authentication`() {
        LocalCodexProfiles.verifyOfficial(config(), account("chatgpt"))
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyOfficial(config(), account("apiKey")) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyOfficial(config().put("model_provider", "glm"), account("chatgpt")) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyOfficial(config().put("openai_base_url", "https://example.com"), account("chatgpt")) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyOfficial(config().apply { remove("openai_base_url") }, account("chatgpt")) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyOfficial(config().put("chatgpt_base_url", "https://example.com"), account("chatgpt")) }
    }
    @Test fun `official process must not silently inherit a third party thread provider`() {
        LocalCodexProfiles.verifyMutation("thread/start", JSONObject())
        LocalCodexProfiles.verifyMutation("thread/fork", JSONObject().put("modelProvider", "openai"))
        LocalCodexProfiles.verifyMutation("thread/start", JSONObject().put("config", JSONObject().put("model_reasoning_effort", "high")))
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyMutation("thread/start", JSONObject().put("config", JSONObject().put("openai_base_url", "https://example.com"))) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyMutation("thread/fork", JSONObject()) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyMutation("thread/start", JSONObject().put("modelProvider", "glm")) }
        assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyMutation("turn/start", JSONObject(), JSONObject().put("modelProvider", "glm")) }
        LocalCodexProfiles.verifyMutation("turn/start", JSONObject(), JSONObject().put("modelProvider", "openai"))
    }
}
