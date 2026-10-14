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

/** 本机对齐 P3：ACP 控制器接回已有会话（回放并段、就绪时机）和自动排队（正常结束才接着发，取消、失败时暂停，偏好每个连接只套用一次）。 */
class AcpTaskQueueTest {
    @TempDir lateinit var root: File
    /** 回包在 write 里同步写，写端是 EDT（一直活着）；回放和回复都很短，管道不会写满。[stopReason] 返回 null 的那轮不回，等 session/cancel。 */
    private class Fixture(val capabilities: JSONObject = JSONObject().put("loadSession", true), val replay: List<JSONObject> = emptyList()) : AcpTransport {
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val prompts = CopyOnWriteArrayList<String>()
        var stopReason: (Int) -> String? = { "end_turn" }
        private val held = CopyOnWriteArrayList<JSONObject>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text)
            when (request.optString("method")) {
                "initialize" -> reply(request, JSONObject().put("protocolVersion", 1).put("agentCapabilities", capabilities))
                "session/new" -> reply(request, JSONObject().put("sessionId", "session"))
                "session/load" -> { replay.forEach(::update); reply(request, JSONObject()) }
                "session/resume" -> reply(request, JSONObject())
                "session/prompt" -> {
                    val prompt = request.getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text")
                    prompts += prompt
                    update(chunk("agent_message_chunk", "回复：$prompt"))
                    val reason = stopReason(prompts.size)
                    if (reason == null) held += request else reply(request, JSONObject().put("stopReason", reason))
                }
                "session/cancel" -> { held.forEach { reply(it, JSONObject().put("stopReason", "cancelled")) }; held.clear() }
            }
            return true
        }
        private fun emit(message: JSONObject) { pipe.write((message.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
        private fun reply(request: JSONObject, result: JSONObject) = emit(JSONObject().put("id", request.get("id")).put("result", result))
        private fun update(update: JSONObject) = emit(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "session").put("update", update)))
        override fun close() { pipe.close(); output.close() }
    }
    private companion object {
        fun chunk(kind: String, text: String, messageId: String = "") = JSONObject().put("sessionUpdate", kind)
            .put("content", JSONObject().put("type", "text").put("text", text)).apply { if (messageId.isNotEmpty()) put("messageId", messageId) }
        fun tool(kind: String, id: String, title: String, status: String) = JSONObject().put("sessionUpdate", kind).put("toolCallId", id).put("title", title).put("status", status)
    }
    private suspend fun connected(fixture: Fixture, block: suspend (AcpClient) -> Unit) = AcpClient(fixture).use { client ->
        client.initialize(); client.newSession(root.path); block(client)
    }
    private suspend fun settle(until: () -> Boolean) = withTimeout(5000) { while (!until()) delay(10) }

    @Test fun `restore replays history in order and only then allows sending`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture(replay = listOf(
            chunk("user_message_chunk", "看看缓存"), chunk("agent_message_chunk", "先读"), chunk("agent_message_chunk", "配置。"),
            tool("tool_call", "t1", "读取 build.gradle.kts", "pending"), chunk("agent_message_chunk", "原因在时间戳。"),
            tool("tool_call_update", "t1", "", "completed"), chunk("agent_message_chunk", "改成只在发版时写。"),
            chunk("user_message_chunk", "好的"), chunk("agent_message_chunk", "已改。", "m2"), chunk("agent_message_chunk", "另一段。", "m3"),
            JSONObject().put("sessionUpdate", "current_mode_update").put("currentModeId", "plan")))
        val queue = InstructionQueue(File(root, "restore.json"))
        AcpClient(fixture).use { client ->
            client.initialize()
            AcpTaskController("task", "session", client, queue, restoring = true).use { controller ->
                assertFalse(controller.ready); assertEquals("正在接回会话…", controller.note)
                queue.enqueue("task", "接回前不能发", id = "early")
                assertFailsWith<IllegalStateException> { controller.sendNext() }
                controller.applyQueuePreference(true)   // 没就绪：不算套用
                assertFalse(controller.autoDispatch)
                controller.restore(root.path)
                assertTrue(controller.ready); assertEquals("已接回会话", controller.note)
                assertEquals(listOf("User:看看缓存", "Assistant:先读配置。", "读取 build.gradle.kts:", "Assistant:原因在时间戳。改成只在发版时写。",
                    "User:好的", "Assistant:已改。", "Assistant:另一段。"), controller.messages.map { it.author + ":" + it.text })
                assertEquals("completed", controller.messages.single { it.kind == "tool" }.status)
                assertNull(controller.modes, "回放里的模式是历史值，回包没给就不显示")
                assertFailsWith<IllegalStateException> { controller.restore(root.path) }
                controller.applyQueuePreference(true)
                assertTrue(controller.autoDispatch)
                settle { fixture.prompts.isNotEmpty() && !controller.busy }
                assertEquals(listOf("接回前不能发"), fixture.prompts.toList())
                assertEquals("Assistant:回复：接回前不能发", controller.messages.last().let { it.author + ":" + it.text })
            }
        }
    }

    @Test fun `resume without replay says the history stays in the runtime`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture(JSONObject().put("sessionCapabilities", JSONObject().put("resume", JSONObject())))
        AcpClient(fixture).use { client ->
            client.initialize()
            AcpTaskController("task", "session", client, InstructionQueue(File(root, "resume.json")), restoring = true).use { controller ->
                controller.restore(root.path)
                assertTrue(controller.ready); assertTrue(controller.messages.isEmpty())
                assertEquals("已接回会话；此运行器不回放历史，之前的内容请在原运行器查看", controller.note)
            }
        }
        AcpClient(Fixture(JSONObject())).use { client ->
            client.initialize()
            AcpTaskController("task", "session", client, InstructionQueue(File(root, "none.json")), restoring = true).use { controller ->
                assertEquals("此运行器不支持继续已有会话，可以新建对话", assertFailsWith<IllegalStateException> { controller.restore(root.path) }.message)
                assertFalse(controller.ready)
            }
        }
    }

    @Test fun `automatic queue sends queued instructions one after another`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture()
        val queue = InstructionQueue(File(root, "auto.json"))
        connected(fixture) { client ->
            AcpTaskController("task", "session", client, queue).use { controller ->
                assertFalse(controller.autoDispatch)   // 默认关着：经典不打开它
                queue.enqueue("task", "第一条", id = "a"); queue.enqueue("task", "第二条", id = "b")
                controller.applyQueuePreference(true)
                settle { fixture.prompts.size == 2 && !controller.busy }
                assertEquals(listOf("第一条", "第二条"), fixture.prompts.toList())
                assertTrue(controller.autoDispatch)
                assertTrue(queue.entries.all { it.status == InstructionStatus.Accepted && it.runtimeTurnState == RuntimeTurnState.Completed })
                queue.enqueue("task", "第三条", id = "c"); controller.queueChanged()
                settle { fixture.prompts.size == 3 && !controller.busy }
                assertEquals(listOf("第一条", "回复：第一条", "第二条", "回复：第二条", "第三条", "回复：第三条"), controller.messages.map { it.text })
            }
        }
    }

    @Test fun `stopping or an abnormal end pauses the automatic queue`(): Unit = runBlocking(Dispatchers.Swing) {
        val cancelled = Fixture().apply { stopReason = { if (it == 1) null else "end_turn" } }
        val queue = InstructionQueue(File(root, "pause.json"))
        connected(cancelled) { client ->
            AcpTaskController("task", "session", client, queue).use { controller ->
                queue.enqueue("task", "第一条", id = "a"); queue.enqueue("task", "第二条", id = "b")
                controller.applyQueuePreference(true)
                settle { controller.busy && cancelled.prompts.isNotEmpty() }
                controller.cancelTurn()
                assertFalse(controller.autoDispatch)
                settle { !controller.busy }
                assertEquals("本轮已取消", controller.note)
                assertEquals(listOf("第一条"), cancelled.prompts.toList())
                assertEquals(InstructionStatus.Local, queue.entries.single { it.id == "b" }.status)
            }
        }
        val refused = Fixture().apply { stopReason = { "refusal" } }
        val second = InstructionQueue(File(root, "refused.json"))
        connected(refused) { client ->
            AcpTaskController("task", "session", client, second).use { controller ->
                second.enqueue("task", "第一条", id = "a"); second.enqueue("task", "第二条", id = "b")
                controller.applyQueuePreference(true)
                settle { refused.prompts.isNotEmpty() && !controller.busy }
                assertFalse(controller.autoDispatch)
                assertTrue(controller.note.endsWith("；自动排队已暂停"), controller.note)
                assertEquals(listOf("第一条"), refused.prompts.toList())
                controller.applyQueuePreference(true)   // 每个连接只套用一次：暂停后不被偏好盖回去
                assertFalse(controller.autoDispatch)
                controller.setAutoDispatch(true)   // 用户在排队条上重新打开
                settle { refused.prompts.size == 2 && !controller.busy }
                assertFalse(controller.autoDispatch)
            }
        }
    }
}
