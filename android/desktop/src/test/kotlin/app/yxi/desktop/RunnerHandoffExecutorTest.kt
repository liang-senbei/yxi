package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class RunnerHandoffExecutorTest {
    @TempDir lateinit var root: File
    @Test fun `creation and delivery intents are durable before side effects and duplicate execution is rejected`(): Unit = runBlocking {
        val file = File(root, "handoff.json"); val store = RunnerHandoffStore(file)
        val queue = InstructionQueue(File(root, "queue.json"))
        val draft = store.draft("source", "@local", "claude", "official:claude", "reviewed summary")
        var creates = 0; var sends = 0
        val executor = RunnerHandoffExecutor(store, queue, create = {
            creates++; assertTrue(file.readText().contains("Creating")); "target"
        }, deliver = { target, id ->
            sends++; assertTrue(file.readText().contains("Delivering"))
            val item = queue.entries.single(); assertEquals(id, item.id); assertEquals("source", item.sourceTask)
            val started = queue.beginDelivery(id, item.revision)
            queue.confirmRuntimeAccepted(id, started.revision, "turn", "ok")
            queue.completeRuntimeTurn(target, "turn", RuntimeTurnState.Completed, "ok")
        })
        assertEquals(RunnerHandoffStage.Completed, executor.execute(draft.id, draft.revision).stage)
        assertFailsWith<IllegalStateException> { executor.execute(draft.id, draft.revision) }
        assertEquals(1, creates); assertEquals(1, sends)
    }
    @Test fun `lost creation receipt becomes unknown and never invokes creation twice`(): Unit = runBlocking {
        val store = RunnerHandoffStore(File(root, "unknown.json")); val queue = InstructionQueue(File(root, "queue.json"))
        val draft = store.draft("source", "@local", "codex", "official:codex", "summary")
        var count = 0
        val executor = RunnerHandoffExecutor(store, queue, { count++; error("receipt lost") }, { _, _ -> error("must not send") })
        assertFailsWith<IllegalStateException> { executor.execute(draft.id, draft.revision) }
        assertEquals(RunnerHandoffStage.Unknown, store.records.single().stage)
        assertFailsWith<IllegalStateException> { executor.execute(draft.id, store.records.single().revision) }
        assertEquals(1, count); assertTrue(queue.entries.isEmpty())
    }
}
