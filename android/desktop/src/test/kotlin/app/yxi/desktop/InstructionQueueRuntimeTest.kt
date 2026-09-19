package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class InstructionQueueRuntimeTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-instructions").toFile()
        try { block(File(dir, "queue.json")) } finally { dir.deleteRecursively() }
    }

    private fun acceptTurn(queue: InstructionQueue, item: QueuedInstruction, turnId: String) {
        val delivering = queue.beginDelivery(item.id, item.revision)
        queue.confirmRuntimeAccepted(delivering.id, delivering.revision, turnId, "receipt-$turnId")
    }

    @Test
    fun `restart preserves in-flight turn and blocks next instruction`() = fixture { file ->
        val queue = InstructionQueue(file)
        val first = queue.enqueue("task", "第一条")
        acceptTurn(queue, first, "turn-1")
        val reopened = InstructionQueue(file)
        val restored = reopened.entries.single()
        assertEquals(InstructionStatus.Accepted, restored.status)
        assertEquals("turn-1", restored.runtimeTurnId)
        assertEquals(RuntimeTurnState.InProgress, restored.runtimeTurnState)
        val next = reopened.enqueue("task", "第二条")
        val blocked = assertFailsWith<IllegalStateException> { reopened.beginDelivery(next.id, next.revision) }
        assertEquals("上一轮尚未结束，指令继续等待", blocked.message)
    }

    @Test
    fun `terminal turn releases the queue and repeated completion is a no-op`() = fixture { file ->
        val queue = InstructionQueue(file)
        val first = queue.enqueue("task", "第一条")
        acceptTurn(queue, first, "turn-1")
        val second = queue.enqueue("task", "第二条")
        assertFailsWith<IllegalStateException> { queue.beginDelivery(second.id, second.revision) }
        assertTrue(queue.completeRuntimeTurn("task", "turn-1", RuntimeTurnState.Completed, "done-1"))
        val finished = queue.entries.single { it.id == first.id }
        assertEquals(RuntimeTurnState.Completed, finished.runtimeTurnState)
        assertEquals("done-1", finished.runtimeCompletion)
        assertEquals(InstructionStatus.Accepted, finished.status)
        // 队首已释放，第二条可发
        assertEquals(InstructionStatus.Delivering, queue.beginDelivery(second.id, second.revision).status)
        // 重复完成：不再匹配 InProgress，返回 false 且不改写任何记录
        val before = queue.entries
        assertFalse(queue.completeRuntimeTurn("task", "turn-1", RuntimeTurnState.Completed, "done-again"))
        assertEquals(before, queue.entries)
        // 持久化核对
        assertEquals(RuntimeTurnState.Completed, InstructionQueue(file).entries.single { it.id == first.id }.runtimeTurnState)
    }

    @Test
    fun `completion ignores other tasks and unknown turns`() = fixture { file ->
        val queue = InstructionQueue(file)
        val a = queue.enqueue("task-a", "A")
        acceptTurn(queue, a, "turn-1")
        val c = queue.enqueue("task-b", "C")
        acceptTurn(queue, c, "turn-2")
        // 跨任务：task-b 的事件不得触碰 task-a 的 turn-1
        assertFalse(queue.completeRuntimeTurn("task-b", "turn-1", RuntimeTurnState.Completed, "x"))
        // 未知轮次：不匹配任何记录
        assertFalse(queue.completeRuntimeTurn("task-a", "turn-unknown", RuntimeTurnState.Completed, "x"))
        assertFails { queue.completeRuntimeTurn("task-a", "", RuntimeTurnState.Completed, "x") }
        assertEquals(RuntimeTurnState.InProgress, queue.entries.single { it.id == a.id }.runtimeTurnState)
        assertEquals(RuntimeTurnState.InProgress, queue.entries.single { it.id == c.id }.runtimeTurnState)
        // 正确配对只更新本任务
        assertTrue(queue.completeRuntimeTurn("task-a", "turn-1", RuntimeTurnState.Completed, "done"))
        assertEquals(RuntimeTurnState.Completed, queue.entries.single { it.id == a.id }.runtimeTurnState)
        assertEquals(RuntimeTurnState.InProgress, queue.entries.single { it.id == c.id }.runtimeTurnState)
    }

    @Test
    fun `steering shares the known turn and never crosses Unknown or other tasks`() = fixture { file ->
        val queue = InstructionQueue(file)
        val a = queue.enqueue("task", "A")
        acceptTurn(queue, a, "turn-1")
        // 有活动轮次时不能开始新的普通投递
        val early = queue.enqueue("task", "早了")
        assertEquals("上一轮尚未结束，指令继续等待",
            assertFailsWith<IllegalStateException> { queue.beginDelivery(early.id, early.revision) }.message)
        queue.cancel(early.id, early.revision) // 撤回它，让引导目标成为队首
        // 跨任务引导：目标轮次不属于 task2
        val foreign = queue.enqueue("task2", "X")
        assertEquals("目标轮次已变化，不能引导",
            assertFailsWith<IllegalStateException> { queue.beginSteering(foreign.id, foreign.revision, "turn-1") }.message)
        // 引导加入同一活动轮次（队首是 Local 的它自己）
        val steering = queue.enqueue("task", "S")
        val steeringItem = queue.beginSteering(steering.id, steering.revision, "turn-1")
        assertEquals(InstructionStatus.Delivering, steeringItem.status)
        val accepted = queue.confirmRuntimeAccepted(steeringItem.id, steeringItem.revision, "turn-1", "receipt-steer")
        assertEquals(RuntimeTurnState.InProgress, accepted.runtimeTurnState)
        // 同一轮结束：两条一起落终态；重复完成是 no-op
        assertTrue(queue.completeRuntimeTurn("task", "turn-1", RuntimeTurnState.Completed, "done"))
        val both = queue.entries.filter { it.id in setOf(a.id, steering.id) }
        assertEquals(2, both.size)
        assertTrue(both.all { it.runtimeTurnState == RuntimeTurnState.Completed && it.runtimeCompletion == "done" })
        assertFalse(queue.completeRuntimeTurn("task", "turn-1", RuntimeTurnState.Completed, "done"))
        // turn 结束后 Unknown 才可能产生（投递失败路径）；它挡住后续普通投递
        val u = queue.enqueue("task", "U")
        val delivering = queue.beginDelivery(u.id, u.revision)
        val unknown = queue.markUnknown(delivering.id, delivering.revision, "未知")
        val next = queue.enqueue("task", "S2")
        assertEquals("请先处理前面的指令",
            assertFailsWith<IllegalStateException> { queue.beginDelivery(next.id, next.revision) }.message)
        // 人工消化 Unknown 后才能继续
        queue.resolveManually(unknown.id, unknown.revision)
        assertEquals(InstructionStatus.Delivering, queue.beginDelivery(next.id, next.revision).status)
    }

    @Test
    fun `legacy v1 records decode and upgrade`() = fixture { file ->
        val legacy = JSONObject().put("version", 1).put("items", JSONArray().put(
            JSONObject().put("id", "old-1").put("taskKey", "task").put("text", "旧记录")
                .put("status", "Local").put("revision", 3).put("detail", "")
                .put("deliveryObservation", "").put("deliveryObservedAt", 0)
                .put("assignmentGroup", "").put("assignmentHost", "").put("sourceTask", "")
                .put("attachments", JSONArray())))
        file.writeText(legacy.toString())
        val queue = InstructionQueue(file)
        assertEquals("old-1", queue.entries.single().id)
        assertEquals(RuntimeTurnState.None, queue.entries.single().runtimeTurnState)
        // 旧记录可以直接走新流程并持久化新字段
        acceptTurn(queue, queue.entries.single(), "turn-9")
        val reopened = InstructionQueue(file)
        assertEquals("turn-9", reopened.entries.single().runtimeTurnId)
        assertEquals(RuntimeTurnState.InProgress, reopened.entries.single().runtimeTurnState)
    }

    @Test
    fun `invalid runtime state keeps the original file unreadable`() = fixture { file ->
        val bad = JSONObject().put("version", 1).put("items", JSONArray().put(
            JSONObject().put("id", "bad-1").put("taskKey", "task").put("text", "坏记录")
                .put("status", "Local").put("revision", 1).put("detail", "")
                .put("deliveryObservation", "").put("deliveryObservedAt", 0)
                .put("assignmentGroup", "").put("assignmentHost", "").put("sourceTask", "")
                .put("runtimeTurnId", "").put("runtimeTurnState", "InProgress").put("runtimeCompletion", "")
                .put("attachments", JSONArray())))
        file.writeText(bad.toString())
        val queue = InstructionQueue(file)
        assertTrue(queue.error.isNotBlank())
        assertTrue(queue.entries.isEmpty())
        // 无 turnId 的 InProgress 属于非法记录：拒绝加载、文件原样保留
        assertFails { queue.enqueue("task", "新指令") }
        assertEquals(bad.toString(), file.readText())
    }
}
