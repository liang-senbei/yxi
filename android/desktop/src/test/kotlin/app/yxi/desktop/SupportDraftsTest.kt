package app.yxi.desktop

import app.yxi.agent.SupportApi
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SupportDraftsTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("support-drafts").toFile()
        try { block(File(dir, "support.json")) } finally { dir.deleteRecursively() }
    }
    @Test fun `account and ticket drafts restore independently with no implicit duplicates`() = fixture { file ->
        val store = SupportDrafts(file)
        val a = store.open("a")
        assertEquals(a, store.open("a"))
        assertNotEquals(a.id, store.open("b").id)
        assertNotEquals(a.id, store.open("a", "ticket-1").id)
        val edited = store.edit(a.id, a.revision, "question", "bug", "visible version", "visible device")
        assertEquals(edited, SupportDrafts(file).open("a"))
        val sending = store.begin(edited.id, edited.revision)
        assertFails { store.edit(edited.id, edited.revision, "stale text", "bug", "", "") }
        val restored = SupportDrafts(file)
        val unknown = restored.open("a")
        assertEquals(SupportSendState.Unknown, unknown.state)
        assertEquals(sending.text, unknown.text)
        assertFails { restored.begin(unknown.id, unknown.revision) }
        val checked = restored.reconcile(unknown.id, unknown.revision, "ticket-42")
        assertEquals(SupportSendState.Confirmed, checked.state)
        assertNotEquals(checked.id, restored.open("a").id)
    }
    @Test fun `rejection keeps draft while an unknown result requires explicit reconciliation`() = fixture { file ->
        val store = SupportDrafts(file)
        val a = store.open("a", "ticket-1")
        val edited = store.edit(a.id, a.revision, "follow up", "", "", "")
        val sending = store.begin(edited.id, edited.revision)
        val rejected = store.failed(sending.id, sending.revision, SupportApi.Failure("limited", false))
        assertEquals(SupportSendState.Editing, rejected.state); assertEquals(edited.text, rejected.text)
        val retry = store.begin(rejected.id, rejected.revision)
        val unknown = store.failed(retry.id, retry.revision, SupportApi.Failure("offline", true))
        assertFails { store.begin(unknown.id, unknown.revision) }
        assertEquals(edited.text, store.reconcile(unknown.id, unknown.revision, null).text)
    }
    @Test fun `older backup cannot silently make a submitted message retryable`() = fixture { file ->
        val store = SupportDrafts(file)
        val a = store.open("a")
        val edit = store.edit(a.id, a.revision, "question", "other", "", "")
        store.begin(edit.id, edit.revision)
        file.writeText("broken")
        val recovered = SupportDrafts(file).open("a")
        assertEquals(SupportSendState.Unknown, recovered.state)
        assertEquals("question", recovered.text)
    }
}
