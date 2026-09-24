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
            if (request.getJSONObject("request").getString("subtype") == "initialize") respond(request, JSONObject())
            return true
        }
        fun emit(value: JSONObject) { producer.write((value.toString() + "\n").toByteArray()); producer.flush() }
        fun respond(request: JSONObject, value: JSONObject) = emit(JSONObject().put("type", "control_response").put("response", JSONObject()
            .put("request_id", request.getString("request_id")).put("subtype", "success").put("response", value)))
        override fun close() { producer.close(); output.close() }
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
