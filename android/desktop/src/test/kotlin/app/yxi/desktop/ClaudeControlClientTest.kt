package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ClaudeControlClientTest {
    private class Fixture : ClaudeControlTransport {
        override val output = PipedInputStream(65536)
        private val producer = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            if (request.optString("type") != "control_request") return true
            if (request.getJSONObject("request").getString("subtype") == "initialize") respond(request, JSONObject())
            return true
        }
        fun emit(value: JSONObject) { producer.write((value.toString() + "\n").toByteArray()); producer.flush() }
        fun respond(request: JSONObject, value: JSONObject) = emit(JSONObject().put("type", "control_response").put("response", JSONObject()
            .put("request_id", request.getString("request_id")).put("subtype", "success").put("response", value)))
        override fun close() { producer.close(); output.close() }
    }
    @Test fun `model selection awaits effective settings and excludes concurrent prompts`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            val change = async { client.setModel("third-party-model") }
            withTimeout(2000) { while (fixture.writes.size < 2) delay(10) }
            assertEquals("third-party-model", fixture.writes.last().getJSONObject("request").getString("model"))
            assertFailsWith<IllegalStateException> { client.prompt("must not send") }
            assertFailsWith<IllegalStateException> { client.setModel("second") }
            fixture.respond(fixture.writes.last(), JSONObject())
            withTimeout(2000) { while (fixture.writes.size < 3) delay(10) }
            assertFalse(change.isCompleted)
            assertEquals("get_settings", fixture.writes.last().getJSONObject("request").getString("subtype"))
            fixture.respond(fixture.writes.last(), JSONObject().put("applied", JSONObject().put("model", "resolved-model")))
            assertEquals("resolved-model", change.await().getJSONObject("applied").getString("model"))
            assertTrue(fixture.writes.none { it.optString("type") == "user" })
        }
    }
    @Test fun `permissions require explicit one-time answers and cancelled requests cannot be approved`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            val turn = async { client.prompt("question") }
            withTimeout(2000) { while (fixture.writes.size < 2) delay(10) }
            fun permission(id: String) = JSONObject().put("type", "control_request").put("request_id", id)
                .put("request", JSONObject().put("subtype", "can_use_tool").put("tool_name", "Bash").put("input", JSONObject().put("command", "fixture")))
            fixture.emit(permission("allow"))
            withTimeout(2000) { while (client.pendingPermissions().isEmpty()) delay(10) }
            assertEquals(2, fixture.writes.size)
            client.pendingPermissions().single().getJSONObject("request").getJSONObject("input").put("command", "must-not-change-native-input")
            client.answerPermission("allow", true)
            val reply = fixture.writes.last().getJSONObject("response").getJSONObject("response")
            assertEquals("allow", reply.getString("behavior")); assertEquals("fixture", reply.getJSONObject("updatedInput").getString("command"))
            assertFalse(reply.has("updatedPermissions"))
            assertFailsWith<IllegalStateException> { client.answerPermission("allow", true) }
            fixture.emit(permission("cancel"))
            withTimeout(2000) { while (client.pendingPermissions().isEmpty()) delay(10) }
            fixture.emit(JSONObject().put("type", "control_cancel_request").put("request_id", "cancel"))
            withTimeout(2000) { while (client.pendingPermissions().isNotEmpty()) delay(10) }
            assertFailsWith<IllegalStateException> { client.answerPermission("cancel", true) }
            fixture.emit(JSONObject().put("type", "result").put("uuid", "done").put("session_id", "session").put("is_error", false))
            turn.await()
        }
    }
    @Test fun `only one prompt is active and native results retain the session identity`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            for (index in 1..2) {
                val turn = async { client.prompt("turn-$index") }
                withTimeout(2000) { while (fixture.writes.count { it.optString("type") == "user" } < index) delay(10) }
                assertFailsWith<IllegalStateException> { client.prompt("duplicate") }
                fixture.emit(JSONObject().put("type", "result").put("uuid", "result-$index").put("session_id", "session")
                    .put("subtype", "success").put("is_error", false).put("result", "answer-$index"))
                assertEquals("answer-$index", turn.await().getString("result"))
            }
            assertEquals("session", client.sessionId)
            assertEquals("session", fixture.writes.last().getString("session_id"))
        }
    }
    @Test fun `uncertain prompt closes the connection and cannot be replayed`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            assertFailsWith<TimeoutCancellationException> { client.prompt("one", 100) }
            assertFailsWith<IllegalStateException> { client.prompt("two") }
            assertEquals(1, fixture.writes.count { it.optString("type") == "user" })
        }
    }
    @Test fun `interrupt acknowledgement does not complete the prompt before its result`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            client.initialize(); assertFalse(client.interrupt())
            val turn = async { client.prompt("stop fixture") }
            withTimeout(2000) { while (fixture.writes.size < 2) delay(10) }
            val stopping = async { client.interrupt() }
            withTimeout(2000) { while (fixture.writes.size < 3) delay(10) }
            assertEquals("interrupt", fixture.writes.last().getJSONObject("request").getString("subtype"))
            fixture.respond(fixture.writes.last(), JSONObject())
            assertTrue(stopping.await()); assertFalse(turn.isCompleted); assertFalse(client.interrupt())
            fixture.emit(JSONObject().put("type", "result").put("uuid", "stopped").put("session_id", "session").put("is_error", true))
            turn.await()
            assertEquals(1, fixture.writes.count { it.optJSONObject("request")?.optString("subtype") == "interrupt" })
        }
    }
    @Test fun `control replies correlate by request identity without submitting prompts`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            assertFailsWith<IllegalStateException> { client.settings() }
            client.initialize()
            val first = async { client.settings() }
            withTimeout(2000) { while (fixture.writes.size < 2) delay(10) }
            val second = async { client.settings() }
            withTimeout(2000) { while (fixture.writes.size < 3) delay(10) }
            fixture.respond(fixture.writes[2], JSONObject().put("value", 2))
            fixture.respond(fixture.writes[1], JSONObject().put("value", 1))
            assertEquals(1, first.await().getInt("value")); assertEquals(2, second.await().getInt("value"))
            assertTrue(fixture.writes.all { it.getString("type") == "control_request" })
        }
    }
    @Test fun `native errors never expose payload details`(): Unit = runBlocking {
        val fixture = Fixture()
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            val result = async { runCatching { client.settings() } }
            withTimeout(2000) { while (fixture.writes.size < 2) delay(10) }
            fixture.emit(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "error")
                .put("request_id", fixture.writes.last().getString("request_id")).put("error", "private-fixture-value")))
            val error = result.await().exceptionOrNull(); assertNotNull(error)
            assertFalse(error.message.orEmpty().contains("private-fixture-value"))
        }
    }
    @Test fun `disconnect and unsupported interaction fail pending checks without approving`(): Unit = runBlocking {
        for (interaction in listOf(false, true)) {
            val fixture = Fixture()
            ClaudeControlClient(fixture).use { client ->
                client.initialize()
                val result = async { runCatching { client.settings() } }
                withTimeout(2000) { while (fixture.writes.size < 2) delay(10) }
                if (interaction) fixture.emit(JSONObject().put("type", "control_request").put("request_id", "permission")
                    .put("request", JSONObject().put("subtype", "can_use_tool"))) else fixture.close()
                assertTrue(withTimeout(2000) { result.await() }.isFailure)
                assertEquals(2, fixture.writes.size)
            }
        }
    }
}
