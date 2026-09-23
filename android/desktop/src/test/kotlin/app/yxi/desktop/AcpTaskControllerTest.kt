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
                "session/new" -> result(request, JSONObject().put("sessionId", "session").put("models", JSONObject().put("currentModelId", "a")
                    .put("availableModels", org.json.JSONArray().put(JSONObject().put("modelId", "a").put("name", "A"))
                        .put(JSONObject().put("modelId", "b").put("name", "B"))))
                    .put("modes", JSONObject().put("currentModeId", "ask")
                    .put("availableModes", org.json.JSONArray().put(JSONObject().put("id", "ask").put("name", "Ask"))
                        .put(JSONObject().put("id", "plan").put("name", "Plan")))))
                "session/set_mode" -> result(request, JSONObject())
                "session/set_model" -> result(request, JSONObject())
            }
            return true
        }
        fun emit(value: JSONObject) { pipe.write((value.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
        fun result(request: JSONObject, value: JSONObject) = emit(JSONObject().put("id", request.get("id")).put("result", value))
        fun text(text: String) = emit(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "session")
            .put("update", JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", text)))))
        override fun close() { pipe.close(); output.close() }
    }
    @Test fun `local persistence failure after model acknowledgement does not claim rejection`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture()
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession(root.path)
            AcpTaskController("task", "session", client, InstructionQueue(File(root, "persistence.json")),
                onModelChanged = { throw IllegalArgumentException("Invalid index record") }).use { controller ->
                assertFailsWith<IllegalArgumentException> { controller.changeModel("b") }
                assertFalse(controller.ready)
                assertEquals("b", client.models("session")?.getString("currentModelId"))
                assertTrue(controller.note.contains("已确认切换"))
                assertFalse(controller.note.contains("未被接受"))
            }
        }
    }
    @Test fun `two turns preserve separate messages and cancelled notification is not completion`() = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture()
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession(root.path)
            val queue = InstructionQueue(File(root, "queue.json"))
            AcpTaskController("task", "session", client, queue).use { controller ->
                controller.changeMode("plan")
                assertEquals("plan", controller.modes?.getString("currentModeId"))
                assertFalse(controller.changingMode)
                for (index in 1..2) {
                    controller.enqueue("question-$index")
                    val send = async { controller.sendNext() }
                    withTimeout(2000) { while (fixture.writes.count { it.optString("method") == "session/prompt" } < index) delay(10) }
                    assertEquals("", controller.lastStopReason)
                    assertEquals("正在等待运行器回复", controller.note)
                    fixture.text("answer-$index")
                    if (index == 2) {
                        fixture.emit(JSONObject().put("id", "queued-permission").put("method", "session/request_permission").put("params", JSONObject()
                            .put("sessionId", "session").put("options", org.json.JSONArray().put(JSONObject().put("optionId", "allow").put("kind", "allow_once")))))
                        // Keep the UI consumer parked until the reader has queued the approval.
                        val deadline = System.nanoTime() + 2_000_000_000L
                        while (client.pendingPermissions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
                        assertEquals(1, client.pendingPermissions().size)
                        controller.cancelTurn()
                        client.synchronizeEvents()
                        assertTrue(controller.pendingApprovals.isEmpty(), "A queued approval must not reappear after cancellation")
                        assertEquals("cancelled", fixture.writes.single { it.optString("id") == "queued-permission" }
                            .getJSONObject("result").getJSONObject("outcome").getString("outcome"))
                        assertFalse(send.isCompleted)
                        assertEquals(InstructionStatus.Delivering, queue.entries.last().status)
                    }
                    fixture.result(fixture.writes.last { it.optString("method") == "session/prompt" },
                        JSONObject().put("stopReason", if (index == 1) "end_turn" else "cancelled"))
                    withTimeout(2000) { send.await() }
                    assertTrue(controller.messages.any { it.text == "answer-$index" }, "Final streamed text must be consumed before the turn completes")
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
    @Test fun `terminal receipt dismisses obsolete approval without selecting an allow option`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture()
        AcpClient(fixture).use { client ->
            client.initialize(); client.newSession(root.path)
            val queue = InstructionQueue(File(root, "terminal.json"))
            AcpTaskController("task", "session", client, queue).use { controller ->
                controller.enqueue("one")
                val send = async { controller.sendNext() }
                withTimeout(2000) { while (fixture.writes.none { it.optString("method") == "session/prompt" }) delay(10) }
                fixture.emit(JSONObject().put("id", "obsolete").put("method", "session/request_permission").put("params", JSONObject()
                    .put("sessionId", "session").put("options", org.json.JSONArray().put(JSONObject().put("optionId", "allow").put("kind", "allow_once")))))
                fixture.result(fixture.writes.last { it.optString("method") == "session/prompt" }, JSONObject().put("stopReason", "end_turn"))
                withTimeout(2000) { send.await() }
                assertEquals(RuntimeTurnState.Completed, queue.entries.single().runtimeTurnState)
                assertTrue(controller.pendingApprovals.isEmpty())
                assertTrue(client.pendingPermissions().isEmpty())
                val outcome = fixture.writes.single { it.optString("id") == "obsolete" }.getJSONObject("result").getJSONObject("outcome")
                assertEquals("cancelled", outcome.getString("outcome"))
                assertFalse(outcome.has("optionId"))
            }
        }
    }
}
