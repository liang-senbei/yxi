package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real native runtime and tool execution; only the model HTTP response is a local fixture. */
class OpenCodeNativeConversationTest {
    @Test fun `native model turn waits for approval and persists its final reply`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val project = File("/sandbox/home/native-conversation").apply { mkdirs() }
        val marker = File(project, "native-approved.txt")
        val issuedTool = AtomicBoolean()
        val modelRequests = AtomicInteger()
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/v1/chat/completions") { exchange ->
            val input = JSONObject(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            modelRequests.incrementAndGet()
            val tools = input.optJSONArray("tools") ?: JSONArray()
            val hasBash = (0 until tools.length()).any { tools.getJSONObject(it).optJSONObject("function")?.optString("name") == "bash" }
            val invokeTool = hasBash && issuedTool.compareAndSet(false, true)
            val content = if (hasBash) "NATIVE-OPENCODE-REPLY" else "Fixture conversation"
            fun chunk(delta: JSONObject, finish: Any = JSONObject.NULL) = JSONObject().put("id", "chatcmpl_fixture").put("object", "chat.completion.chunk")
                .put("created", 1).put("model", "fixture-model").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish)))
            val output: String
            if (input.optBoolean("stream")) {
                val delta = if (invokeTool) JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call_fixture").put("type", "function")
                    .put("function", JSONObject().put("name", "bash").put("arguments", JSONObject().put("command", "printf approved > native-approved.txt").put("description", "Write isolated approval marker").toString()))))
                else JSONObject().put("content", content)
                output = "data: ${chunk(JSONObject().put("role", "assistant"))}\n\n" +
                    "data: ${chunk(delta)}\n\n" + "data: ${chunk(JSONObject(), if (invokeTool) "tool_calls" else "stop")}\n\n" + "data: [DONE]\n\n"
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
            } else {
                output = JSONObject().put("id", "chatcmpl_fixture").put("object", "chat.completion").put("created", 1).put("model", "fixture-model")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", content))))
                    .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2)).toString()
                exchange.responseHeaders.set("Content-Type", "application/json")
            }
            val bytes = output.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes); exchange.close()
        }
        provider.start()
        File(project, "opencode.json").writeText(JSONObject().put("model", "fixture/fixture-model").put("small_model", "fixture/fixture-model")
            .put("permission", JSONObject().put("bash", "ask"))
            .put("provider", JSONObject().put("fixture", JSONObject().put("npm", "@ai-sdk/openai-compatible").put("name", "Isolated fixture")
                .put("options", JSONObject().put("baseURL", "http://127.0.0.1:${provider.address.port}/v1").put("apiKey", "fixture-key"))
                .put("models", JSONObject().put("fixture-model", JSONObject().put("name", "Fixture model").put("limit", JSONObject().put("context", 8192).put("output", 1024)))))).toString())
        val queueFile = File(project, "yxi-queue.json")
        val registryFile = File(project, "yxi-tasks.json")
        val queue = InstructionQueue(queueFile)
        val shared = SharedMcpRegistry(File(project, "shared-mcp.json"))
        val sharedScript = File(project, "shared_mcp_fixture.py").apply { writeBytes(requireNotNull(OpenCodeNativeConversationTest::class.java.getResourceAsStream("/shared_mcp_fixture.py")).use { it.readBytes() }) }
        val sharedDefinition = SharedMcpDefinition("@local", "shared-fixture", "yxi-test", "1", "shared_echo", listOf("/usr/bin/python3", sharedScript.path))
        shared.save(sharedDefinition, setOf("opencode"), null)
        val runtime = LocalRuntimeInstallation("opencode", "isolated", listOf("/opt/native/claude"), "/sandbox/home/.local/share/opencode", "1.18.32")
        try {
            LocalOpenCodeTasks(queue, registryFile, shared).use { tasks ->
                val models = tasks.models(runtime, project.path)
                val model = models.single { it.providerId == "fixture" && it.modelId == "fixture-model" }
                val record = tasks.create(runtime, project.path, "Native approval fixture", model)
                assertEquals(record, LocalCodexTaskRegistry(registryFile).records.single())
                assertEquals("", tasks.recoverySessionId)
                val binding = File(project, "mcp-bindings").walkTopDown().single { it.isFile && it.name == "${sharedDefinition.key}.json" }
                assertEquals("connected", JSONObject(binding.readText()).getString("phase"))
                val controller = tasks.controllers.getValue(record.key)
                val instruction = controller.enqueue("Run the bash fixture and report completion.")
                try {
                    controller.sendNext()
                    withTimeout(45000) { while (controller.permissions.isEmpty()) { delay(200); controller.refresh() } }
                    assertFalse(marker.exists(), "Native tool must wait for explicit approval")
                    assertTrue(issuedTool.get())
                    controller.replyPermission(controller.permissions.single().getString("id"), OpenCodePermissionReply.Once)
                    withTimeout(45000) { while (queue.entries.single { it.id == instruction.id }.runtimeTurnState == RuntimeTurnState.InProgress) { delay(200); controller.refresh() } }
                    assertEquals(RuntimeTurnState.Completed, queue.entries.single().runtimeTurnState)
                    assertEquals("approved", marker.readText())
                    assertTrue(controller.messages.any { it.toString().contains("NATIVE-OPENCODE-REPLY") })
                    assertEquals(RuntimeTurnState.Completed, InstructionQueue(queueFile).entries.single().runtimeTurnState)
                    println("Real OpenCode 1.18.32: created, requested approval, ran isolated tool after once, persisted completion. Model requests: ${modelRequests.get()}")
                } catch (e: Throwable) {
                    File("/results/opencode-native-diagnostics.json").writeText(JSONObject().put("messages", JSONArray(controller.messages)).put("note", controller.note)
                        .put("modelRequests", modelRequests.get()).put("issuedTool", issuedTool.get()).put("queue", queue.entries.toString()).toString(2))
                    throw e
                }
            }
        } finally { provider.stop(0) }
    }
}
