package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.io.File
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalOfficialCodexNativeTest {
    @Test fun `official process overrides third party configuration but preserves mismatched native login`() = runBlocking {
        check(System.getProperty("user.home") == "/sandbox/home")
        val home = File("/sandbox/home/.codex").apply { mkdirs() }
        val config = File(home, "config.toml").apply { writeText("""
model_provider = "fixture"
openai_base_url = "http://127.0.0.1:9/incorrect"
chatgpt_base_url = "http://127.0.0.1:9/incorrect-login"
cli_auth_credentials_store = "file"
[model_providers.fixture]
name = "Fixture"
base_url = "http://127.0.0.1:9/third-party"
wire_api = "responses"
requires_openai_auth = false
""".trimIndent()) }
        val auth = File(home, "auth.json").apply { writeText("""{"OPENAI_API_KEY":"fixture-native-key"}""") }
        val beforeConfig = config.readBytes(); val beforeAuth = auth.readBytes()
        val runtime = LocalRuntimeInstallation("codex", "fixture", listOf("/opt/native/claude"), home.path, "0.153.4")
        val environment = LocalCodexProfiles.officialEnvironment(System.getenv() + mapOf("OPENAI_API_KEY" to "wrong-key", "OPENAI_BASE_URL" to "http://127.0.0.1:9/env"))
        val transport = LocalCodexTransport.start(runtime, LocalCodexProfiles.officialArguments(), environment)
        CodexAppServer(transport).use { client ->
            client.initializeLocal()
            val effective = client.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config")
            assertEquals("openai", effective.getString("model_provider"))
            assertEquals("", effective.getString("openai_base_url"))
            assertEquals(LocalCodexProfiles.chatgptBase, effective.getString("chatgpt_base_url"))
            val account = client.request("account/read", JSONObject().put("refreshToken", false)).getJSONObject("result")
            assertEquals("apiKey", account.getJSONObject("account").getString("type"))
            assertFailsWith<IllegalStateException> { LocalCodexProfiles.verifyOfficial(effective, account) }
        }
        val denied = assertFailsWith<IllegalStateException> { LocalCodexProfiles.connectOfficial(runtime) }
        assertTrue(denied.message.orEmpty().contains("ChatGPT"))
        assertContentEquals(beforeConfig, config.readBytes())
        assertContentEquals(beforeAuth, auth.readBytes(), "Selecting official mode must not log out an existing API account")
    }
}
