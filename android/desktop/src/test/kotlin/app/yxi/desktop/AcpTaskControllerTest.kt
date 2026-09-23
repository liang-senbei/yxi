package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AcpTaskControllerTest {
    @TempDir lateinit var root: File
    private class Fixture : AcpTransport {
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            when (request.optString("method")) {
                "initialize" -> result(request, JSONObject().put("protocolVersion", 1))
                "session/new" -> result(request, JSONObject().put("sessionId", "session"))
            }
            return true
        }
        fun emit(value: JSONObject) { pipe.write((value.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
        fun result(request: JSONObject, value: JSONObject) = emit(JSONObject().put("id", request.get("id")).put("result", value))
        fun text(text: String) = emit(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "session")
            .put("update", JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", text)))))
        override fun close() { pipe.close(); output.close() }
    }
    @Test fun `two turns preserve separate messages and cancelled notification is not completion`() = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture()
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession(root.path)
            val queue = InstructionQueue(File(root, "queue.json"))
            AcpTaskController("task", "session", client, queue).use { controller ->
                for (index in 1..2) {
                    controller.enqueue("question-$index")
                    val send = async { controller.sendNext() }
                    withTimeout(2000) { while (fixture.writes.count { it.optString("method") == "session/prompt" } < index) delay(10) }
                    fixture.text("answer-$index")
                    withTimeout(2000) { while (controller.messages.none { it.text == "answer-$index" }) delay(10) }
                    if (index == 2) {
                        controller.cancelTurn()
                        assertFalse(send.isCompleted)
                        assertEquals(InstructionStatus.Delivering, queue.entries.last().status)
                    }
                    fixture.result(fixture.writes.last { it.optString("method") == "session/prompt" },
                        JSONObject().put("stopReason", if (index == 1) "end_turn" else "cancelled"))
                    withTimeout(2000) { send.await() }
                }
                assertEquals(listOf("answer-1", "answer-2"), controller.messages.filter { it.author == "Assistant" }.map { it.text })
                assertEquals(listOf(RuntimeTurnState.Completed, RuntimeTurnState.Interrupted), queue.entries.map { it.runtimeTurnState })
                assertEquals(queue.entries, InstructionQueue(File(root, "queue.json")).entries)
            }
        }
    }
    @Test fun `timeout persists unknown and prevents resending or skipping`() = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture()
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession(root.path)
            val queueFile = File(root, "unknown.json")
            val queue = InstructionQueue(queueFile)
            AcpTaskController("task", "session", client, queue, promptTimeoutMillis = 100).use { controller ->
                controller.enqueue("first"); controller.enqueue("second")
                assertFailsWith<TimeoutCancellationException> { controller.sendNext() }
                assertEquals(InstructionStatus.Unknown, InstructionQueue(queueFile).entries.first().status)
                assertFailsWith<IllegalStateException> { controller.sendNext() }
                assertEquals(1, fixture.writes.count { it.optString("method") == "session/prompt" })
            }
        }
    }
}
