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
        try {
            withTimeout(10000) { while (!File(root, "port").isFile) delay(50) }
            val config = File("/sandbox/home/.codex/config.toml").apply { parentFile.mkdirs(); writeText("""
model = "fixture-model-0"
model_provider = "fixture"
approval_policy = "on-request"
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
            val runtime = LocalRuntimeInstallation("codex", "isolated", listOf("/opt/native/claude"), config.parent, "0.153.4")
            val transport = LocalCodexTransport.start(runtime, SharedMcpSettings.codexArguments(listOf(record), "machine") + "app-server", System.getenv())
            CodexAppServer(transport).use { client ->
                client.initializeLocal()
                val started = client.startThread(root.path).getJSONObject("result")
                val thread = started.getJSONObject("thread")
                val queue = InstructionQueue(File(root, "queue.json"))
                val controller = CodexTaskController("mcp-fixture", thread.getString("id"), client, queue, initialModel = "fixture-model-0")
                try {
                    controller.reconcile(thread)
                    queue.enqueue("mcp-fixture", "Call the shared MCP echo tool with codex-native-call.")
                    controller.sendNext()
                    withTimeout(30000) { while (controller.pendingRequests.isEmpty()) delay(50) }
                    val approval = controller.pendingRequests.values.single()
                    File("/results/codex-mcp-approval.json").writeText(approval.toString(2))
                    assertEquals("mcpServer/elicitation/request", approval.getString("method"))
                    val params = approval.getJSONObject("params")
                    assertEquals("shared_echo", params.getString("serverName"))
                    assertTrue(CodexMcpElicitation.canAcceptEmptyForm(params))
                    controller.answerRequest(approval.get("id"), CodexMcpElicitation.response(params, "accept"))
                    withTimeout(30000) { while (queue.entries.single().runtimeTurnState != RuntimeTurnState.Completed) delay(50) }
                    assertTrue(controller.messages.any { it.text.contains("SHARED_MCP_NATIVE_CONFIRMED") })
                } finally {
                    File("/results/codex-mcp-controller.txt").writeText(controller.note + "\n" + controller.messages.toString() + "\n" + controller.pendingRequests.toString())
                    controller.close()
                }
            }
            val requests = File(root, "requests.jsonl").readLines().map(::JSONObject)
            assertTrue(requests.any { request ->
                val input = request.getJSONArray("input")
                (0 until input.length()).any { input.optJSONObject(it)?.let { item -> item.optString("type") == "function_call_output" && item.opt("output").toString().contains("YXI_SHARED_MCP") } == true }
            })
            assertContentEquals(original, config.readBytes())
            println("Real Codex called the shared MCP fixture with generated process overrides; native global config unchanged.")
        } finally {
            LocalRuntimeDiscovery.stopOwnedProcess(provider)
            File(root, "requests.jsonl").takeIf { it.isFile }?.copyTo(File("/results/codex-shared-mcp-requests.jsonl"), overwrite = true)
        }
    }
}
