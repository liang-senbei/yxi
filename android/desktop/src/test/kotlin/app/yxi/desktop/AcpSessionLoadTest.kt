package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** 本机对齐 P3：AcpClient 接回已有会话。session/load 先回放再回包、回放期间背压；session/resume 不回放；能力没声明就不发。 */
class AcpSessionLoadTest {
    /** 回包和回放都在 write 里同步写；写的是测试线程（一直活着），消费者在 Default 上，管道写满也不会互等。 */
    private class Fixture(val capabilities: JSONObject = JSONObject(), val replay: List<JSONObject> = emptyList(), val result: Any = JSONObject()) : AcpTransport {
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes += request
            when (request.optString("method")) {
                "initialize" -> reply(request, JSONObject().put("protocolVersion", 1).put("agentCapabilities", capabilities))
                "session/load" -> {
                    val session = request.getJSONObject("params").getString("sessionId")
                    replay.forEach { emit(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", session).put("update", it))) }
                    reply(request, result)
                }
                "session/resume" -> reply(request, result)
                "session/prompt" -> reply(request, JSONObject().put("stopReason", "end_turn"))
            }
            return true
        }
        fun emit(message: JSONObject) { pipe.write((message.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
        fun reply(request: JSONObject, result: Any) = emit(JSONObject().put("id", request.get("id")).put("result", result))
        override fun close() { pipe.close(); output.close() }
    }
    private fun options(model: String) = JSONArray().put(JSONObject().put("id", "model").put("name", "Model").put("category", "model").put("type", "select")
        .put("currentValue", model).put("options", JSONArray().put(JSONObject().put("value", "old").put("name", "Old")).put(JSONObject().put("value", "new").put("name", "New"))))
    private fun modes(current: String) = JSONObject().put("currentModeId", current)
        .put("availableModes", JSONArray().put(JSONObject().put("id", "ask").put("name", "Ask")).put(JSONObject().put("id", "plan").put("name", "Plan")))
    private fun chunk(text: String) = JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", text))
    private fun CoroutineScope.consume(client: AcpClient, handler: suspend (JSONObject) -> Unit = {}): Pair<Job, CompletableDeferred<Unit>> {
        val ready = CompletableDeferred<Unit>()
        return launch(Dispatchers.Default) { client.consumeEvents(onReady = { ready.complete(Unit) }, handler = handler) } to ready
    }

    @Test fun `load and resume are refused unless the runtime declares them`() = runBlocking {
        val fixture = Fixture()
        AcpClient(fixture).use { client ->
            client.initialize()
            assertFalse(client.supportsLoadSession()); assertFalse(client.supportsResume())
            assertFailsWith<IllegalStateException> { client.loadSession("old-session", "/fixture") }
            assertFailsWith<IllegalStateException> { client.resumeSession("old-session", "/fixture") }
            assertTrue(fixture.writes.none { it.optString("method").startsWith("session/") })
        }
    }

    @Test fun `load needs an event consumer before anything is sent`() = runBlocking {
        val fixture = Fixture(JSONObject().put("loadSession", true))
        AcpClient(fixture).use { client ->
            client.initialize()
            assertTrue(client.supportsLoadSession())
            assertFailsWith<IllegalStateException> { client.loadSession("old-session", "/fixture") }
            assertTrue(fixture.writes.none { it.optString("method") == "session/load" })
        }
    }

    @Test fun `load replays more updates than the event channel holds and the reply sets current values`() = runBlocking {
        val replay = (1..150).map { chunk("片段$it") } +
            JSONObject().put("sessionUpdate", "config_option_update").put("configOptions", options("old")) +
            JSONObject().put("sessionUpdate", "current_mode_update").put("currentModeId", "plan")
        val fixture = Fixture(JSONObject().put("loadSession", true), replay, JSONObject().put("configOptions", options("new")).put("modes", modes("ask")))
        AcpClient(fixture).use { client ->
            client.initialize()
            val seen = CopyOnWriteArrayList<String>()
            // 消费比读慢：通道只有 64 格，没有背压时读线程会因为塞不进而断开连接
            val (consumer, ready) = consume(client) { event -> delay(1); seen += event.getJSONObject("params").getJSONObject("update").getString("sessionUpdate") }
            ready.await()
            val result = client.loadSession("old-session", "/fixture")
            assertEquals(152, seen.size, "回包前的回放都该在 loadSession 返回前消费完")
            assertEquals("new", acpConfigSelectors(client.configOptions("old-session")).single { it.id == "model" }.current)
            assertEquals("ask", client.modes("old-session")?.getString("currentModeId"))
            assertEquals("ask", result.getJSONObject("modes").getString("currentModeId"))
            val params = fixture.writes.single { it.optString("method") == "session/load" }.getJSONObject("params")
            assertEquals("/fixture", params.getString("cwd")); assertEquals(0, params.getJSONArray("mcpServers").length())
            assertEquals("end_turn", client.prompt("old-session", "接着来").getString("stopReason"))   // 接回后会话已登记
            assertFailsWith<IllegalStateException> { client.loadSession("old-session", "/fixture") }   // 同一连接上不能再接一次
            consumer.cancel()
        }
    }

    @Test fun `resume does not replay and accepts a null result`() = runBlocking {
        val fixture = Fixture(JSONObject().put("sessionCapabilities", JSONObject().put("resume", JSONObject())), result = JSONObject.NULL)
        AcpClient(fixture).use { client ->
            client.initialize()
            assertFalse(client.supportsLoadSession()); assertTrue(client.supportsResume())
            val (consumer, ready) = consume(client)
            ready.await()
            assertEquals(0, client.resumeSession("old-session", "/fixture").length())
            val params = fixture.writes.single { it.optString("method") == "session/resume" }.getJSONObject("params")
            assertEquals("old-session", params.getString("sessionId")); assertEquals(0, params.getJSONArray("mcpServers").length())
            assertNull(client.modes("old-session")); assertEquals(0, client.configOptions("old-session").length())
            assertEquals("end_turn", client.prompt("old-session", "接着来").getString("stopReason"))
            consumer.cancel()
        }
    }
}
