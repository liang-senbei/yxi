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
        // Synthetic offline identity validates the positive protocol path, not a real subscription request.
        fun encoded(value: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val idToken = encoded("""{"alg":"none"}""") + "." + encoded("""{"sub":"fixture-user","exp":4102444800,"https://api.openai.com/auth":{"chatgpt_account_id":"fixture-account","chatgpt_plan_type":"pro"}}""") + ".fixture"
        auth.writeText(JSONObject().put("auth_mode", "chatgpt").put("tokens", JSONObject().put("id_token", idToken)
            .put("access_token", "fixture-access").put("refresh_token", "fixture-refresh").put("account_id", "fixture-account"))
            .put("last_refresh", java.time.Instant.now().toString()).toString())
        val chatgptBefore = auth.readBytes()
        var nativeModel = ""
        LocalCodexProfiles.connectOfficial(runtime).use { client ->
            assertTrue(client.authenticationSummary().contains("ChatGPT"))
            val models = LocalOfficialModels.load { client.listModels(it) }
            assertTrue(models.isNotEmpty())
            nativeModel = models.first().id
            assertTrue(models.none { it.id == "fixture" }, "Native model discovery must not substitute a configured provider ID")
            val overrideDenied = assertFailsWith<IllegalStateException> { client.request("thread/start", JSONObject()
                .put("cwd", "/sandbox/home").put("config", JSONObject().put("openai_base_url", "http://127.0.0.1:9/wrong"))) }
            assertTrue(overrideDenied.message.orEmpty().contains("未经核对"))
            val resumeDenied = assertFailsWith<IllegalStateException> { client.request("thread/resume", JSONObject().put("threadId", "not-a-real-thread")) }
            assertTrue(resumeDenied.message.orEmpty().contains("显式核对"))
        }
        val index = File("/sandbox/tmp/official-local-tasks.json")
        val queue = InstructionQueue(File("/sandbox/tmp/official-local-queue.json"))
        val shared = SharedMcpRegistry(File("/sandbox/tmp/official-shared-mcp.json"))
        val fixture = File("/sandbox/home/shared_mcp_fixture.py").apply { writeBytes(requireNotNull(LocalOfficialCodexNativeTest::class.java.getResourceAsStream("/shared_mcp_fixture.py")).use { it.readBytes() }) }
        val definition = SharedMcpDefinition("@local", "echo", "fixture", "1", "shared_echo", listOf("/usr/bin/python3", fixture.path), environmentNames = setOf("PATH"))
        shared.save(definition, setOf("codex"), null)
        LocalCodexProfiles.connectOfficialWithSharedMcp(runtime, shared.forHost("@local"), "/sandbox/home").use { client ->
            val configWithMcp = client.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config")
            LocalCodexProfiles.verifySharedMcp(configWithMcp, shared.forHost("@local"))
            assertEquals("openai", configWithMcp.getString("model_provider"))
        }
        LocalCodexTasks(queue, index, shared).use { tasks ->
            val record = tasks.create(runtime, "/sandbox/home", "Local official fixture", nativeModel)
            assertEquals(record, LocalCodexTaskRegistry(index).records.single())
            assertTrue(tasks.controllers.getValue(record.key).ready)
            assertEquals("", tasks.recoveryThreadId)
        }
        assertContentEquals(beforeConfig, config.readBytes())
        assertContentEquals(chatgptBefore, auth.readBytes())
        val project = File("/sandbox/home/project-with-mcp").apply { mkdirs() }
        val git = ProcessBuilder("git", "init", project.path).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        check(git.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && git.exitValue() == 0)
        val projectConfig = File(project, ".codex/config.toml").apply {
            parentFile.mkdirs(); writeText("[mcp_servers.shared_echo]\ncommand = \"/bin/false\"\nargs = []\nenabled = false\n")
        }
        config.appendText("\n[projects.\"${project.path}\"]\ntrust_level = \"trusted\"\n")
        val configWithTrust = config.readBytes()
        val projectBefore = projectConfig.readBytes()
        LocalCodexProfiles.connectOfficial(runtime).use { client ->
            val effective = client.request("config/read", JSONObject().put("includeLayers", false).put("cwd", project.path)).getJSONObject("result").getJSONObject("config")
            assertEquals("/bin/false", effective.getJSONObject("mcp_servers").getJSONObject("shared_echo").getString("command"))
        }
        val collision = assertFailsWith<IllegalStateException> { LocalCodexProfiles.connectOfficialWithSharedMcp(runtime, shared.forHost("@local"), project.path) }
        assertTrue(collision.message.orEmpty().contains("同名"))
        assertContentEquals(configWithTrust, config.readBytes())
        assertContentEquals(projectBefore, projectConfig.readBytes())
        assertContentEquals(chatgptBefore, auth.readBytes())
    }
}
