package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class CodexSharedMcpNativeTest {
    @Test fun `native Codex invokes the shared MCP with process-only generated overrides`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val root = File("/sandbox/home/codex-shared-mcp").apply { mkdirs() }
        fun resource(name: String) = File(root, name).apply { writeBytes(requireNotNull(CodexSharedMcpNativeTest::class.java.getResourceAsStream("/$name")).use { it.readBytes() }) }
        val mcp = resource("shared_mcp_fixture.py")
        val stub = resource("codex_mcp_responses_stub.py")
        val provider = ProcessBuilder("python3", stub.path, root.path, "fixture-key-0").redirectErrorStream(true).redirectOutput(File(root, "provider.log")).start()
        var codex: Process? = null
        try {
            withTimeout(10000) { while (!File(root, "port").isFile) delay(50) }
            val config = File("/sandbox/home/.codex/config.toml").apply { parentFile.mkdirs(); writeText("""
model = "fixture-model-0"
model_provider = "fixture"
approval_policy = "never"
sandbox_mode = "read-only"
[model_providers.fixture]
name = "Fixture"
base_url = "http://127.0.0.1:${File(root, "port").readText().trim()}/v1"
wire_api = "responses"
requires_openai_auth = false
http_headers = { Authorization = "Bearer fixture-key-0" }
""".trimIndent()) }
            val original = config.readBytes()
            val definition = SharedMcpDefinition("machine", "shared-echo", "yxi-test", "0.1.0", "shared_echo", listOf("/usr/bin/python3", mcp.path))
            val record = SharedMcpRecord(definition, setOf("codex"), 0)
            val output = File("/results/codex-shared-mcp.jsonl")
            val process = ProcessBuilder(listOf("/opt/native/claude") + SharedMcpSettings.codexArguments(listOf(record), "machine") +
                listOf("exec", "--skip-git-repo-check", "--json", "-"))
                .directory(root).redirectOutput(output).redirectError(File("/results/codex-shared-mcp.stderr")).start().also { codex = it }
            process.outputStream.use { it.write("Call the shared MCP echo tool with codex-native-call.\n".toByteArray()) }
            withTimeout(60000) { while (process.isAlive) delay(100) }
            File(root, "requests.jsonl").takeIf { it.isFile }?.copyTo(File("/results/codex-shared-mcp-requests.jsonl"), overwrite = true)
            assertEquals(0, process.exitValue(), output.readText().takeLast(4000))
            val requests = File(root, "requests.jsonl").readLines().map(::JSONObject)
            assertTrue(requests.any { request ->
                val input = request.getJSONArray("input")
                (0 until input.length()).any { input.optJSONObject(it)?.let { item -> item.optString("type") == "function_call_output" && item.opt("output").toString().contains("YXI_SHARED_MCP") } == true }
            })
            assertTrue(output.readText().contains("SHARED_MCP_NATIVE_CONFIRMED"))
            assertContentEquals(original, config.readBytes())
            println("Real Codex called the shared MCP fixture with generated process overrides; native global config unchanged.")
        } finally { codex?.let(LocalRuntimeDiscovery::stopOwnedProcess); LocalRuntimeDiscovery.stopOwnedProcess(provider) }
    }
}
