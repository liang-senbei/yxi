package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HermesAcpConversationNativeTest {
    @Test fun `real Hermes creates an owned session and completes a model turn through loopback provider`(): Unit = runBlocking(Dispatchers.Swing) {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val calls = AtomicInteger()
        val observedModels = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            val data = JSONObject().put("object", "list").put("data", JSONArray(listOf("fixture-hermes", "fixture-hermes-alt").map {
                JSONObject().put("id", it).put("object", "model").put("owned_by", "fixture")
            })).toString().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }; exchange.close()
        }
        server.createContext("/v1/chat/completions") { exchange ->
            try {
                check(exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-hermes-key")
                val input = JSONObject(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                check(input.getString("model") in setOf("fixture-hermes", "fixture-hermes-alt"))
                observedModels.add(input.getString("model"))
                check(calls.incrementAndGet() <= 10)
                val answer = "HERMES_NATIVE_TURN_CONFIRMED"
                fun chunk(delta: JSONObject, reason: Any = JSONObject.NULL) = JSONObject().put("id", "fixture-chat").put("object", "chat.completion.chunk")
                    .put("created", 1).put("model", "fixture-hermes").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", reason)))
                val response = if (input.optBoolean("stream")) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    listOf(chunk(JSONObject().put("role", "assistant")), chunk(JSONObject().put("content", answer)), chunk(JSONObject(), "stop"))
                        .joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
                } else {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    JSONObject().put("id", "fixture-chat").put("object", "chat.completion").put("created", 1).put("model", "fixture-hermes")
                        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop")
                            .put("message", JSONObject().put("role", "assistant").put("content", answer))))
                        .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15)).toString()
                }
                val bytes = response.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            } finally { exchange.close() }
        }
        server.start()
        val home = File("/sandbox/home/.hermes").apply { mkdirs() }
        val url = "http://127.0.0.1:${server.address.port}/v1"
        File(home, ".env").writeText("OPENAI_API_KEY=fixture-hermes-key\nOPENAI_BASE_URL=$url\n")
        File(home, "config.yaml").writeText("model:\n  provider: custom:fixture\n  default: fixture-hermes\nproviders:\n  fixture:\n    name: Fixture\n    base_url: $url\n    key_env: OPENAI_API_KEY\n    api_mode: chat_completions\n    models:\n      fixture-hermes: {}\n      fixture-hermes-alt: {}\n")
        val runtime = LocalRuntimeInstallation("hermes", "pinned official source", listOf("/opt/hermes/bin/hermes"), home.path, "0.21.4")
        val queue = InstructionQueue(File("/sandbox/tmp/hermes-queue.json"))
        val index = File("/sandbox/tmp/hermes-tasks.json")
        try {
            LocalAcpTasks(queue, index).use { tasks ->
                val hello = tasks.prepare(runtime, "/sandbox/home")
                val methods = hello.getJSONArray("authMethods")
                val configured = (0 until methods.length()).map { methods.getJSONObject(it) }.single { it.optString("type", "agent") == "agent" }
                tasks.authenticate(configured.getString("id"))
                val record = tasks.create("Hermes native conversation")
                val controller = tasks.controllers.getValue(record.key)
                try {
                    controller.enqueue("Reply with the verification phrase.")
                    val delivery = controller.dispatchNext()
                    withTimeout(60000) { delivery.join() }
                    assertEquals(RuntimeTurnState.Completed, queue.entries.single().runtimeTurnState)
                    assertTrue(controller.messages.any { it.author == "Assistant" && it.text.contains("HERMES_NATIVE_TURN_CONFIRMED") })
                    assertTrue(calls.get() > 0)
                    assertEquals(record.key, LocalCodexTaskRegistry(index).records.single().key)
                    val models = checkNotNull(controller.models)
                    File("/results/hermes-models.json").writeText(models.toString(2))
                    val options = models.getJSONArray("availableModels")
                    val alternative = (0 until options.length()).map { options.getJSONObject(it).getString("modelId") }.single { it == "custom:fixture:fixture-hermes-alt" }
                    withTimeout(60000) { controller.changeModel(alternative) }
                    assertEquals(alternative, LocalCodexTaskRegistry(index).records.single().model)
                    controller.enqueue("Confirm the second model.")
                    withTimeout(60000) { controller.dispatchNext().join() }
                    assertEquals(RuntimeTurnState.Completed, queue.entries.last().runtimeTurnState)
                    assertEquals("fixture-hermes-alt", observedModels.last())
                } finally {
                    File("/results/hermes-native-turn.json").writeText(JSONObject().put("calls", calls.get()).put("note", controller.note)
                        .put("stopReason", controller.lastStopReason).put("model", tasks.registry.records.single().model)
                        .put("observedModels", JSONArray(observedModels)).put("queueStatus", queue.entries.firstOrNull()?.status?.name).toString(2))
                }
            }
        } finally { server.stop(0) }
    }
}
