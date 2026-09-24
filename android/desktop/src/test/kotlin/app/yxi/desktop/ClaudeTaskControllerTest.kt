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
        private var model = "initial"
        private var effort = "low"
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            if (request.optString("type") == "user") {
                assertTrue(JSONObject(disk.readText()).getJSONArray("items").let { rows ->
                    (0 until rows.length()).any { rows.getJSONObject(it).getString("status") == "Delivering" }
                }, "Delivery must be durable before native IO")
            } else if (request.optString("type") == "control_request") {
                val command = request.getJSONObject("request")
                if (command.getString("subtype") == "set_model") model = command.getString("model")
                if (command.getString("subtype") == "apply_flag_settings") effort = command.getJSONObject("settings").getString("effortLevel")
                val result = if (command.getString("subtype") == "get_settings") JSONObject().put("effective", ClaudeSubscriptionSettings.overlay())
                    .put("applied", JSONObject().put("model", model).put("effort", effort)) else JSONObject()
                emit(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
                    .put("request_id", request.getString("request_id")).put("response", result)))
            }
            return true
        }
        fun emit(value: JSONObject) { producer.write((value.toString() + "\n").toByteArray()); producer.flush() }
        override fun close() { producer.close(); output.close() }
    }
    @Test fun `effort changes only to supported levels and never sends a user message`(): Unit = runBlocking(Dispatchers.Swing) {
        val disk = File(root, "effort.json"); val fixture = Fixture(disk)
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            ClaudeTaskController("task", client, InstructionQueue(disk), initialModel = "initial",
                modelEfforts = mapOf("initial" to listOf("low", "high")), initialEffort = "low").use { controller ->
                assertFailsWith<IllegalArgumentException> { controller.selectEffort("max") }
                assertTrue(controller.ready)
                controller.selectEffort("high")
                assertEquals("high", controller.effort); assertFalse(controller.changingModel)
                assertEquals("initial", controller.model)
                assertTrue(fixture.writes.none { it.optString("type") == "user" })
            }
        }
    }
    @Test fun `model selection updates only confirmed choices and persistence failure disables sending`(): Unit = runBlocking(Dispatchers.Swing) {
        for (failSave in listOf(false, true)) {
            val disk = File(root, "model-$failSave.json"); val fixture = Fixture(disk)
            ClaudeControlClient(fixture).use { client ->
                client.initialize()
                val saved = mutableListOf<String>()
                ClaudeTaskController("task", client, InstructionQueue(disk), initialModel = "initial", availableModels = listOf("resolved-model"),
                    onModelChanged = { if (failSave) error("disk unavailable"); saved.add(it) }).use { controller ->
                    assertFailsWith<IllegalArgumentException> { controller.selectModel("unlisted") }
                    assertTrue(controller.ready)
                    if (failSave) {
                        assertFailsWith<IllegalStateException> { controller.selectModel("resolved-model") }
                        assertFalse(controller.ready); assertEquals("initial", controller.model); assertTrue(saved.isEmpty())
                    } else {
                        controller.selectModel("resolved-model")
                        assertEquals("resolved-model", controller.model); assertEquals(listOf("resolved-model"), saved)
                    }
                    assertFalse(controller.changingModel)
                    assertTrue(fixture.writes.none { it.optString("type") == "user" })
                }
            }
        }
    }
    @Test fun `native receipts persist completed or failed turns after messages are processed`(): Unit = runBlocking(Dispatchers.Swing) {
        val disk = File(root, "queue.json"); val queue = InstructionQueue(disk); val fixture = Fixture(disk)
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            val notices = mutableListOf<String>()
            ClaudeTaskController("task", client, queue, onNotification = { notices.add(it); error("notification unavailable") }).use { controller ->
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
                assertEquals(listOf("任务完成", "任务未成功"), notices)
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
    @Test fun `stop acknowledgement keeps delivery pending until native aborted receipt`(): Unit = runBlocking(Dispatchers.Swing) {
        for (requested in listOf(false, true)) {
            val disk = File(root, "stop-$requested.json"); val queue = InstructionQueue(disk); val fixture = Fixture(disk)
            ClaudeControlClient(fixture).use { client ->
                client.initialize()
                ClaudeTaskController("task", client, queue).use { controller ->
                    controller.enqueue("question")
                    val sending = async { controller.sendNext() }
                    withTimeout(2000) { while (fixture.writes.none { it.optString("type") == "user" }) delay(10) }
                    if (requested) {
                        controller.cancelTurn()
                        assertTrue(controller.cancelling); assertTrue(controller.busy)
                        assertEquals(InstructionStatus.Delivering, queue.entries.single().status)
                        assertFalse(sending.isCompleted)
                    }
                    fixture.emit(JSONObject().put("type", "result").put("uuid", "stopped").put("session_id", "session")
                        .put("is_error", true).put("subtype", "error_during_execution").put("terminal_reason", "aborted_streaming"))
                    sending.await()
                    assertEquals(if (requested) RuntimeTurnState.Interrupted else RuntimeTurnState.Failed, InstructionQueue(disk).entries.single().runtimeTurnState)
                    assertFalse(controller.busy); assertFalse(controller.cancelling)
                }
            }
        }
    }
    @Test fun `stopping before delivery leaves the instruction local and never writes a prompt`(): Unit = runBlocking(Dispatchers.Swing) {
        val disk = File(root, "stop-preflight.json"); val queue = InstructionQueue(disk); val fixture = Fixture(disk)
        val checking = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        ClaudeControlClient(fixture).use { client ->
            client.initialize()
            ClaudeTaskController("task", client, queue, beforeSend = { checking.complete(Unit); release.await() }).use { controller ->
                controller.enqueue("question")
                val sending = async { runCatching { controller.sendNext() } }
                checking.await(); controller.cancelTurn(); release.complete(Unit)
                assertTrue(sending.await().isFailure)
                assertEquals(InstructionStatus.Local, InstructionQueue(disk).entries.single().status)
                assertTrue(fixture.writes.none { it.optString("type") == "user" })
                assertTrue(controller.note.contains("发送前已停止"))
            }
        }
    }
}
