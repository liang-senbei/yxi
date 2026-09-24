package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class RunnerHandoffStoreTest {
    @TempDir lateinit var root: File
    private fun draft(store: RunnerHandoffStore) = store.draft("source-key", "local", "codex", "official:codex", "Reviewed context")
    @Test fun `reviewed context has one target and one delivery identity across restart`() {
        val file = File(root, "handoff.json")
        val source = File(root, "source-history.jsonl").apply { writeText("original history") }
        val original = source.readBytes()
        val store = RunnerHandoffStore(file)
        val draft = draft(store)
        val edited = store.edit(draft.id, draft.revision, "Edited context")
        assertFails { store.beginCreation(draft.id, draft.revision) }
        val creating = store.beginCreation(edited.id, edited.revision)
        val created = store.created(creating.id, creating.revision, "destination-key")
        val reopened = RunnerHandoffStore(file)
        assertEquals(created, reopened.records.single())
        assertFails { reopened.beginCreation(created.id, created.revision) }
        assertFails { reopened.beginDelivery(created.id, created.revision, "another-target") }
        val sending = reopened.beginDelivery(created.id, created.revision, "destination-key")
        assertEquals(draft.deliveryId, sending.deliveryId)
        assertFails { reopened.beginDelivery(created.id, created.revision, "destination-key") }
        assertFails { reopened.completed(sending.id, sending.revision, "wrong-id", "receipt") }
        val completed = reopened.completed(sending.id, sending.revision, sending.deliveryId, "native receipt")
        assertEquals(RunnerHandoffStage.Completed, RunnerHandoffStore(file).records.single().stage)
        assertFails { reopened.beginDelivery(completed.id, completed.revision, "destination-key") }
        assertContentEquals(original, source.readBytes())
    }
    @Test fun `creation and delivery interrupted by restart become unknown and cannot retry`() {
        for (delivery in listOf(false, true)) {
            val file = File(root, "$delivery.json")
            val store = RunnerHandoffStore(file)
            val draft = draft(store)
            val creating = store.beginCreation(draft.id, draft.revision)
            val active = if (delivery) store.created(creating.id, creating.revision, "target").let { store.beginDelivery(it.id, it.revision, "target") } else creating
            val reopened = RunnerHandoffStore(file)
            val unknown = reopened.records.single()
            assertEquals(RunnerHandoffStage.Unknown, unknown.stage)
            assertEquals(active.deliveryId, unknown.deliveryId)
            assertEquals(active.targetTaskKey, unknown.targetTaskKey)
            assertFails { reopened.beginCreation(unknown.id, unknown.revision) }
            assertFails { reopened.beginDelivery(unknown.id, unknown.revision, "target") }
            assertFails { draft(reopened) }
            assertEquals(unknown, RunnerHandoffStore(file).records.single())
        }
    }
    @Test fun `unsupported journal version is retained and writes refused`() {
        val file = File(root, "future.json").apply { writeText("""{"version":2,"records":[]}""") }
        val original = file.readText()
        val store = RunnerHandoffStore(file)
        assertTrue(store.problem.isNotBlank())
        assertFails { draft(store) }
        assertEquals(original, file.readText())
    }
    @Test fun `backup recovery never permits replay from an older draft`() {
        val file = File(root, "recovered.json")
        val store = RunnerHandoffStore(file)
        val draft = draft(store)
        store.beginCreation(draft.id, draft.revision)
        file.writeText("damaged")
        repeat(2) {
            val reopened = RunnerHandoffStore(file)
            assertTrue(reopened.problem.isNotBlank())
            assertFails { reopened.beginCreation(draft.id, draft.revision) }
        }
    }
}
