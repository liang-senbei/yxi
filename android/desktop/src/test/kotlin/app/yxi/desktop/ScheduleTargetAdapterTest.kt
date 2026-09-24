package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class ScheduleTargetAdapterTest {
    @TempDir lateinit var root: File
    private fun task() = ScheduledTask("schedule", "Daily", "inspect", ScheduleTarget("local-session", "Existing", "claude", task = "task"), 1L, "每天", "UTC")
    private fun run() = ScheduleRun("run", "schedule", 1L, "执行中")

    @Test fun `scheduled delivery waits for approval and authoritative completion and is never replayed`(): Unit = runBlocking(Dispatchers.Swing) {
        val queue = InstructionQueue(File(root, "queue.json"))
        var approvals = false
        var dispatched = 0
        val approval = CompletableDeferred<Unit>()
        val notices = mutableListOf<String>()
        val adapter = ScheduleTargetAdapter(queue, {
            ScheduleTargetAdapter.Connection({ true }, { approvals }) { id ->
                dispatched++
                launch {
                    val item = queue.entries.single { it.id == id }
                    val started = queue.beginDelivery(id, item.revision)
                    approvals = true
                    approval.await()
                    approvals = false
                    val accepted = queue.confirmRuntimeAccepted(id, started.revision, "native-turn", "receipt")
                    queue.completeRuntimeTurn("task", "native-turn", RuntimeTurnState.Completed, "completed")
                    assertEquals(InstructionStatus.Accepted, accepted.status)
                }
            }
        }, pollMillis = 5)
        val execution = async { adapter.execute(task(), run()) { notices.add(it) } }
        withTimeout(2000) { while (notices.none { it.contains("等待用户批准") }) delay(5) }
        assertFalse(execution.isCompleted)
        assertEquals(InstructionStatus.Delivering, queue.entries.single().status)
        approval.complete(Unit)
        assertEquals("已完成", withTimeout(2000) { execution.await() }.status)
        assertEquals("已完成", adapter.execute(task(), run()) {}.status)
        assertEquals(1, dispatched)
        assertEquals("task", queue.entries.single().taskKey)
    }
    @Test fun `disconnected busy and existing queue targets skip without creating instructions`(): Unit = runBlocking(Dispatchers.Swing) {
        val queue = InstructionQueue(File(root, "skip.json"))
        assertEquals("跳过", ScheduleTargetAdapter(queue, { null }).execute(task(), run()) {}.status)
        val busy = ScheduleTargetAdapter.Connection({ false }, { false }) { error("must not dispatch") }
        assertEquals("跳过", ScheduleTargetAdapter(queue, { busy }).execute(task(), run()) {}.status)
        assertTrue(queue.entries.isEmpty())
        queue.enqueue("task", "existing user draft")
        val ready = ScheduleTargetAdapter.Connection({ true }, { false }) { error("must not dispatch") }
        assertEquals("跳过", ScheduleTargetAdapter(queue, { ready }).execute(task(), run()) {}.status)
        assertEquals("existing user draft", queue.entries.single().text)
    }
    @Test fun `unknown delivery pauses schedule and cannot dispatch again`(): Unit = runBlocking(Dispatchers.Swing) {
        val queue = InstructionQueue(File(root, "unknown.json"))
        val store = ScheduledTasks(File(root, "schedule.json"))
        store.put(task())
        val (task, run) = assertNotNull(store.claim(2L))
        var count = 0
        val adapter = ScheduleTargetAdapter(queue, { ScheduleTargetAdapter.Connection({ true }, { false }) { id -> launch {
            count++
            val started = queue.beginDelivery(id, queue.entries.single().revision)
            queue.markUnknown(id, started.revision, "disconnected")
        } } }, pollMillis = 5)
        val result = adapter.execute(task, run) { store.progress(run.id, it) }
        store.finish(run.id, result.status, result.detail)
        assertEquals("结果未确认", result.status)
        assertFalse(store.tasks.single().enabled)
        assertNull(store.claim(Long.MAX_VALUE / 2))
        assertEquals("结果未确认", adapter.execute(task, run) {}.status)
        assertEquals(1, count)
    }
    @Test fun `failed and stopped receipts show short status without exposing native payload`(): Unit = runBlocking(Dispatchers.Swing) {
        for (outcome in listOf(RuntimeTurnState.Failed, RuntimeTurnState.Interrupted)) {
            val queue = InstructionQueue(File(root, "receipt-$outcome.json"))
            val queued = queue.enqueue("task", "inspect", id = "run")
            val started = queue.beginDelivery(queued.id, queued.revision)
            queue.confirmRuntimeAccepted(queued.id, started.revision, "turn", "receipt")
            queue.completeRuntimeTurn("task", "turn", outcome, "{\"private_payload\":\"not-for-schedule\"}")
            val result = ScheduleTargetAdapter(queue, { error("must not dispatch") }).execute(task(), run()) {}
            assertEquals("失败", result.status)
            assertTrue(result.detail.contains("指令 run"))
            assertTrue(result.detail.contains(if (outcome == RuntimeTurnState.Interrupted) "已停止" else "本轮失败"))
            assertFalse(result.detail.contains("private_payload"))
            assertFalse(result.detail.contains("not-for-schedule"))
        }
    }
    @Test fun `dispatch that never delivered retracts only scheduled instruction`(): Unit = runBlocking(Dispatchers.Swing) {
        val queue = InstructionQueue(File(root, "unsent.json"))
        val adapter = ScheduleTargetAdapter(queue, { ScheduleTargetAdapter.Connection({ true }, { false }) { launch { } } }, pollMillis = 5)
        assertEquals("跳过", adapter.execute(task(), run()) {}.status)
        assertEquals(InstructionStatus.Cancelled, queue.entries.single().status)
    }
}
