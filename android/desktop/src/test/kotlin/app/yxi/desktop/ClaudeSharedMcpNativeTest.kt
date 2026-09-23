package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class ClaudeSharedMcpNativeTest {
    @Test fun `native Claude calls the same shared MCP fixture through generated configuration`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val root = File("/sandbox/home/claude-shared-mcp").apply { mkdirs() }
        fun resource(name: String) = File(root, name).apply { writeBytes(requireNotNull(ClaudeSharedMcpNativeTest::class.java.getResourceAsStream("/$name")).use { it.readBytes() }) }
        val mcp = resource("shared_mcp_fixture.py")
        val stub = resource("claude_mcp_stub.py")
        val provider = ProcessBuilder("python3", stub.path, root.path).redirectErrorStream(true).redirectOutput(File(root, "provider.log")).start()
        var claude: Process? = null
        try {
            withTimeout(10000) { while (!File(root, "port").isFile) delay(50) }
            val definition = SharedMcpDefinition("fixture-machine", "shared-echo", "yxi-test", "0.1.0", "shared_echo", listOf("/usr/bin/python3", mcp.path))
            val config = File(root, "mcp.json").apply { writeText(SharedMcpSettings.forRunner(definition, "claude").toString()) }
            val before = config.readBytes()
            val output = File("/results/claude-shared-mcp.jsonl")
            val process = ProcessBuilder("/opt/native/claude", "-p", "--output-format", "stream-json", "--verbose",
                "--mcp-config", config.path, "--strict-mcp-config", "--allowedTools", "mcp__shared_echo__yxi_echo")
                .directory(root).redirectError(File("/results/claude-shared-mcp.stderr")).redirectOutput(output).apply {
                    environment()["ANTHROPIC_BASE_URL"] = "http://127.0.0.1:${File(root, "port").readText().trim()}"
                    environment()["ANTHROPIC_API_KEY"] = "sk-ant-yxi-container-test-only"
                    environment()["ENABLE_TOOL_SEARCH"] = "false"
                    environment()["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
                    environment()["DISABLE_TELEMETRY"] = "1"
                    environment()["DISABLE_AUTOUPDATER"] = "1"
                }.start().also { claude = it }
            process.outputStream.use { it.write("Call the shared MCP echo tool once with claude-native-call.\n".toByteArray()) }
            withTimeout(60000) { while (process.isAlive) delay(100) }
            assertEquals(0, process.exitValue(), output.readText().takeLast(4000))
            val calls = File(root, "calls.jsonl").readLines().map(::JSONObject)
            assertTrue(calls.any { it.optBoolean("result_confirmed") }, calls.toString())
            assertTrue(output.readText().contains("SHARED_MCP_NATIVE_CONFIRMED"))
            assertContentEquals(before, config.readBytes())
            println("Real Claude loaded generated MCP config and returned the shared echo result to the local model fixture.")
        } finally { claude?.let(LocalRuntimeDiscovery::stopOwnedProcess); LocalRuntimeDiscovery.stopOwnedProcess(provider) }
    }
}
