package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import org.json.JSONArray
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ClaudeTaskControllerTest {
    @TempDir lateinit var root: File
    private class Fixture(val disk: File) : ClaudeControlTransport {
        override val output = PipedInputStream(65536)
        private val producer = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            if (request.optString("type") == "user") {
                assertTrue(JSONObject(disk.readText()).getJSONArray("items").let { rows ->
                    (0 until rows.length()).any { rows.getJSONObject(it).getString("status") == "Delivering" }
                }, "Delivery must be durable before native IO")
            } else if (request.optString("type") == "control_request") {
                val result = if (request.getJSONObject("request").getString("subtype") == "get_settings") JSONObject().put("effective", ClaudeSubscriptionSettings.overlay()) else JSONObject()
                emit(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
                    .put("request_id", request.getString("request_id")).put("response", result)))
            }
            return true
        }
        fun emit(value: JSONObject) { producer.write((value.toString() + "\n").toByteArray()); producer.flush() }
        override fun close() { producer.close(); output.close() }
    }
    @Test fun `native receipts persist completed or failed turns after messages are processed`(): Unit = runBlocking(Dispatchers.Swing) {
        val disk = File(root, "queue.json"); val queue = InstructionQueue(disk); val fixture = Fixture(disk)
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            ClaudeTaskController("task", client, queue).use { controller ->
                for (index in 1..2) {
                    controller.enqueue("question-$index")
                    val send = async { controller.sendNext() }
                    withTimeout(2000) { while (fixture.writes.count { it.optString("type") == "user" } < index) delay(10) }
                    fixture.emit(JSONObject().put("type", "assistant").put("uuid", "message-$index").put("message", JSONObject().put("content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "answer-$index")))))
                    fixture.emit(JSONObject().put("type", "result").put("uuid", "turn-$index").put("session_id", "session")
                        .put("is_error", index == 2).put("subtype", if (index == 1) "success" else "error_during_execution"))
                    send.await()
                    assertTrue(controller.messages.any { it.text == "answer-$index" })
                }
                val saved = InstructionQueue(disk).entries
                assertTrue(saved.all { it.status == InstructionStatus.Accepted })
                assertEquals(listOf(RuntimeTurnState.Completed, RuntimeTurnState.Failed), saved.map { it.runtimeTurnState })
            }
        }
    }
    @Test fun `preflight failure keeps local instruction unsent`(): Unit = runBlocking(Dispatchers.Swing) {
        val disk = File(root, "preflight.json"); val queue = InstructionQueue(disk); val fixture = Fixture(disk)
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            ClaudeTaskController("task", client, queue, beforeSend = { error("线路冲突") }).use { controller ->
                controller.enqueue("question")
                assertFailsWith<IllegalStateException> { controller.sendNext() }
                assertEquals(InstructionStatus.Local, InstructionQueue(disk).entries.single().status)
                assertTrue(fixture.writes.none { it.optString("type") == "user" })
                assertTrue(controller.note.contains("线路冲突"))
            }
        }
    }
    @Test fun `disconnect leaves durable unknown and prevents another delivery`(): Unit = runBlocking(Dispatchers.Swing) {
        val disk = File(root, "unknown.json"); val queue = InstructionQueue(disk); val fixture = Fixture(disk)
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            ClaudeTaskController("task", client, queue).use { controller ->
                controller.enqueue("one"); controller.enqueue("two")
                val send = async { runCatching { controller.sendNext() } }
                withTimeout(2000) { while (fixture.writes.none { it.optString("type") == "user" }) delay(10) }
                fixture.close()
                assertTrue(withTimeout(2000) { send.await() }.isFailure)
                assertEquals(InstructionStatus.Unknown, InstructionQueue(disk).entries.first().status)
                assertFailsWith<IllegalStateException> { controller.sendNext() }
                assertEquals(1, fixture.writes.count { it.optString("type") == "user" })
            }
        }
    }
}
