package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class GeminiAcpConversationNativeTest {
    @Test fun `real Gemini authenticates to a loopback API and completes an owned ACP turn`(): Unit = runBlocking(Dispatchers.Swing) {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val paths = CopyOnWriteArrayList<String>()
        val issuedTool = java.util.concurrent.atomic.AtomicBoolean()
        val sawToolResult = java.util.concurrent.atomic.AtomicBoolean()
        val marker = File("/sandbox/home/gemini-approved.txt")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                check(exchange.requestHeaders.getFirst("x-goog-api-key") == "fixture-gemini-key")
                check(paths.size < 20); paths.add(path)
                val raw = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val input = if (raw.isBlank()) JSONObject() else JSONObject(raw)
                val tools = input.optJSONArray("tools") ?: JSONArray()
                val declared = (0 until tools.length()).flatMap { i ->
                    val functions = tools.getJSONObject(i).optJSONArray("functionDeclarations") ?: JSONArray()
                    (0 until functions.length()).map { functions.getJSONObject(it).getString("name") }
                }
                if (input.optJSONArray("contents")?.toString()?.contains("\"functionResponse\"") == true) sawToolResult.set(true)
                val invoke = "run_shell_command" in declared && issuedTool.compareAndSet(false, true)
                val part = if (invoke) JSONObject().put("functionCall", JSONObject().put("name", "run_shell_command")
                    .put("args", JSONObject().put("command", "printf approved > ${marker.path}").put("description", "Write isolated verification marker")))
                else JSONObject().put("text", if (sawToolResult.get()) "GEMINI_NATIVE_TURN_CONFIRMED" else "Fixture response")
                val response = when {
                    path.endsWith(":countTokens") -> JSONObject().put("totalTokens", 50)
                    path.endsWith(":generateContent") || path.endsWith(":streamGenerateContent") -> JSONObject()
                        .put("candidates", JSONArray().put(JSONObject().put("index", 0).put("finishReason", "STOP")
                            .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(part)))))
                        .put("usageMetadata", JSONObject().put("promptTokenCount", 50).put("candidatesTokenCount", 5).put("totalTokenCount", 55))
                        .put("modelVersion", path.substringAfter("models/").substringBefore(':'))
                    else -> JSONObject().put("models", JSONArray().put(JSONObject().put("name", "models/gemini-2.5-flash")))
                }
                val streaming = path.endsWith(":streamGenerateContent")
                val bytes = (if (streaming) "data: $response\n\n" else response.toString()).toByteArray()
                exchange.responseHeaders.set("Content-Type", if (streaming) "text/event-stream" else "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            } finally { exchange.close() }
        }
        server.start()
        val environment = System.getenv() + mapOf("GEMINI_API_KEY" to "fixture-gemini-key",
            "GOOGLE_GEMINI_BASE_URL" to "http://127.0.0.1:${server.address.port}", "GEMINI_MODEL" to "gemini-2.5-flash")
        val runtime = LocalRuntimeInstallation("gemini", "official pinned npm", listOf("/opt/gemini/node",
            "/opt/gemini/runtime/node_modules/@google/gemini-cli/dist/index.js"), "/sandbox/home/.gemini", "0.34.0")
        val queue = InstructionQueue(File("/sandbox/tmp/gemini-conversation-queue.json"))
        val index = File("/sandbox/tmp/gemini-conversation-tasks.json")
        try {
            LocalAcpTasks(queue, index) { selected, directory ->
                val client = AcpClient(LocalAcpTransport.start(selected, directory, environment))
                try { client.initialize(terminalAuthentication = true); client } catch (e: Exception) { client.close(); throw e }
            }.use { tasks ->
                tasks.prepare(runtime, "/sandbox/home")
                tasks.authenticate("gemini-api-key")
                val record = tasks.create("Gemini native conversation")
                val controller = tasks.controllers.getValue(record.key)
                try {
                    controller.enqueue("Reply with the verification phrase.")
                    val delivery = controller.dispatchNext()
                    withTimeout(30000) { while (controller.pendingApprovals.isEmpty()) delay(20) }
                    assertFalse(marker.exists(), "Native tool must not execute before approval")
                    val approval = controller.pendingApprovals.values.single()
                    File("/results/gemini-native-approval.json").writeText(approval.toString(2))
                    assertTrue(approval.toString().contains("gemini-approved.txt"))
                    val options = approval.getJSONObject("params").getJSONArray("options")
                    val once = (0 until options.length()).map { options.getJSONObject(it) }.single { it.getString("kind") == "allow_once" }
                    controller.answerPermission(approval.get("id"), once.getString("optionId"))
                    withTimeout(60000) { delivery.join() }
                    assertEquals(RuntimeTurnState.Completed, queue.entries.single().runtimeTurnState)
                    assertTrue(controller.messages.any { it.author == "Assistant" && it.text.contains("GEMINI_NATIVE_TURN_CONFIRMED") })
                    assertTrue(paths.any { it.contains("/models/gemini-2.5-flash:") })
                    assertEquals(record.key, LocalCodexTaskRegistry(index).records.single().key)
                    assertFalse(File("/sandbox/home/.gemini/oauth_creds.json").exists())
                    assertEquals("approved", marker.readText())
                    assertTrue(sawToolResult.get())
                } finally {
                    File("/results/gemini-native-turn.json").writeText(JSONObject().put("paths", JSONArray(paths)).put("note", controller.note)
                        .put("stopReason", controller.lastStopReason).put("model", tasks.registry.records.single().model)
                        .put("issuedTool", issuedTool.get()).put("sawToolResult", sawToolResult.get()).toString(2))
                }
            }
        } finally { server.stop(0) }
    }
}
