package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AcpClientTest {
    @Test fun `permission arriving after cancellation receives cancelled without approval`() = runBlocking {
        val fixture = Fixture().apply { setup() }
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession("/fixture")
            client.cancel("fixture-session")
            fixture.emit(JSONObject().put("id", "late-permission").put("method", "session/request_permission").put("params", JSONObject()
                .put("sessionId", "fixture-session").put("options", JSONArray().put(JSONObject().put("optionId", "allow").put("kind", "allow_once")))))
            withTimeout(2000) { while (fixture.writes.none { it.optString("id") == "late-permission" }) delay(10) }
            val reply = fixture.writes.single { it.optString("id") == "late-permission" }
            assertEquals("cancelled", reply.getJSONObject("result").getJSONObject("outcome").getString("outcome"))
            assertTrue(client.pendingPermissions().isEmpty())
        }
    }
    private class Fixture : AcpTransport {
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        var handler: (JSONObject) -> Unit = {}
        override suspend fun write(text: String): Boolean { val message = JSONObject(text); writes += message; handler(message); return true }
        fun emit(message: JSONObject) { pipe.write((message.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
        fun result(request: JSONObject, result: JSONObject) = emit(JSONObject().put("id", request.get("id")).put("result", result))
        override fun close() { pipe.close(); output.close() }
    }
    private fun Fixture.setup() {
        handler = { request -> when (request.optString("method")) {
            "initialize" -> result(request, JSONObject().put("protocolVersion", 1).put("authMethods", JSONArray()))
            "session/new" -> result(request, JSONObject().put("sessionId", "fixture-session"))
        } }
    }
    @Test fun `permission options remain native and responses correlate with string and numeric ids`() = runBlocking {
        val fixture = Fixture().apply { setup() }
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession("/fixture")
            assertTrue(fixture.writes.all { it.getString("jsonrpc") == "2.0" })
            assertEquals(0, fixture.writes.first().getJSONObject("params").getJSONObject("clientCapabilities").length())
            for (id in listOf<Any>(9, "9")) {
                fixture.emit(JSONObject().put("id", id).put("method", "session/request_permission").put("params", JSONObject()
                    .put("sessionId", "fixture-session").put("options", JSONArray().put(JSONObject().put("optionId", "allow-once").put("kind", "allow_once").put("name", "Allow once")))))
            }
            withTimeout(2000) { while (client.pendingPermissions().size < 2) delay(10) }
            assertFailsWith<IllegalStateException> { client.answerPermission(9, "other-session", "allow-once") }
            assertFailsWith<IllegalArgumentException> { client.answerPermission(9, "fixture-session", "invented") }
            client.answerPermission(9, "fixture-session", "allow-once")
            client.answerPermission("9", "fixture-session", null)
            val replies = fixture.writes.filter { it.has("result") }
            assertEquals("selected", replies[0].getJSONObject("result").getJSONObject("outcome").getString("outcome"))
            assertEquals("cancelled", replies[1].getJSONObject("result").getJSONObject("outcome").getString("outcome"))
            assertTrue(client.pendingPermissions().isEmpty())
        }
    }
    @Test fun `prompt completion is its response while cancel is only a notification`() = runBlocking {
        val fixture = Fixture().apply { setup() }
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession("/fixture")
            val turn = async { client.prompt("fixture-session", "hello") }
            withTimeout(2000) { while (fixture.writes.none { it.optString("method") == "session/prompt" }) delay(10) }
            client.cancel("fixture-session")
            assertFalse(turn.isCompleted)
            assertFalse(fixture.writes.last().has("id"))
            fixture.result(fixture.writes.single { it.optString("method") == "session/prompt" }, JSONObject().put("stopReason", "cancelled"))
            assertEquals("cancelled", withTimeout(2000) { turn.await() }.getString("stopReason"))
        }
    }
    @Test fun `timed out prompt blocks a second prompt and is not resent`() = runBlocking {
        val fixture = Fixture().apply { setup() }
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession("/fixture")
            assertFailsWith<TimeoutCancellationException> { client.prompt("fixture-session", "one", 100) }
            assertFailsWith<IllegalStateException> { client.prompt("fixture-session", "two", 100) }
            assertEquals(1, fixture.writes.count { it.optString("method") == "session/prompt" })
        }
    }
    @Test fun `unsupported client tools are rejected rather than executed`() = runBlocking {
        val fixture = Fixture().apply { setup() }
        AcpClient(fixture).use { client ->
            client.initialize()
            fixture.emit(JSONObject().put("id", "file-write").put("method", "fs/write_text_file").put("params", JSONObject().put("path", "/fixture")))
            withTimeout(2000) { while (fixture.writes.none { it.has("error") }) delay(10) }
            assertEquals(-32601, fixture.writes.single { it.has("error") }.getJSONObject("error").getInt("code"))
        }
    }
}
