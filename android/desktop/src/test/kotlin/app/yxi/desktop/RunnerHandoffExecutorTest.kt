package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class RunnerHandoffExecutorTest {
    @TempDir lateinit var root: File
    @Test fun `failed dispatch retracts only its unsent summary and keeps source draft intact`(): Unit = runBlocking {
        val store = RunnerHandoffStore(File(root, "failed.json")); val queue = InstructionQueue(File(root, "queue.json"))
        val source = queue.enqueue("source", "unsent source draft")
        val draft = store.draft("source", "@local", "claude", "official:claude", "summary")
        val executor = RunnerHandoffExecutor(store, queue, { "target" }, { _, _ -> error("target busy") })
        assertFailsWith<IllegalStateException> { executor.execute(draft.id, draft.revision) }
        assertEquals(InstructionStatus.Cancelled, queue.entries.single { it.id == draft.deliveryId }.status)
        assertEquals(source, queue.entries.single { it.id == source.id })
        assertEquals(RunnerHandoffStage.Unknown, store.records.single().stage)
    }
    @Test fun `existing delivery identity is never dispatched or altered on another task`(): Unit = runBlocking {
        val store = RunnerHandoffStore(File(root, "collision.json")); val queue = InstructionQueue(File(root, "queue.json"))
        val draft = store.draft("source", "@local", "claude", "official:claude", "summary")
        val creating = store.beginCreation(draft.id, draft.revision)
        val created = store.created(draft.id, creating.revision, "target")
        val unrelated = queue.enqueue("other", "different", id = created.deliveryId)
        val executor = RunnerHandoffExecutor(store, queue, { error("must not create") }, { _, _ -> error("must not dispatch") })
        assertFailsWith<IllegalStateException> { executor.execute(created.id, created.revision) }
        assertEquals(unrelated, queue.entries.single())
        assertEquals(RunnerHandoffStage.Created, store.records.single().stage)
    }
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
