package app.yxi.desktop

import kotlin.test.*

class ExitProtectionTest {
    @Test fun `clean workspace does not need a prompt`() {
        assertFalse(PendingWork(emptyList(), 0, 0, 0).needsReview)
    }
    @Test fun `each kind of unsaved work requires review`() {
        assertTrue(PendingWork(listOf("/project/PRD.md"), 0, 0, 0).needsReview)
        assertTrue(PendingWork(emptyList(), 1, 0, 0).needsReview)
        assertTrue(PendingWork(emptyList(), 0, 1, 0).needsReview)
    }
    @Test fun `in flight operation cannot be discarded`() {
        val busy = PendingWork(emptyList(), 0, 0, 1)
        assertTrue(busy.needsReview); assertFalse(busy.canDiscard)
        assertTrue(busy.copy(operations = 0).canDiscard)
    }
    @Test fun `web feedback remains unsaved until added to the task`() {
        val preview = BrowserPreview(Host("id", "Test", "127.0.0.1"), "task")
        preview.comment = "Improve the heading"
        assertTrue(preview.hasUnsubmittedFeedback)
        preview.commentAdded = true
        assertFalse(preview.hasUnsubmittedFeedback)
    }
    @Test fun `adding feedback to a draft is not the same as sending it`() {
        val preview = BrowserPreview(Host("id", "Test", "127.0.0.1"), "task")
        preview.comment = "Improve heading"; preview.commentAdded = true
        val work = pendingWorkOf(emptyList(), listOf("Improve heading", " "), listOf(preview))
        assertEquals(0, work.feedback); assertEquals(1, work.drafts); assertTrue(work.needsReview)
    }
    @Test fun `saving documents remain protected until their operation finishes`() {
        val document = FileDocument("host", "task", "/work/PRD.md")
        document.receive(FileSnapshot("before".toByteArray()))
        document.editor = androidx.compose.ui.text.input.TextFieldValue("after")
        document.busy = true
        val work = pendingWorkOf(listOf(document), emptyList(), emptyList())
        assertEquals(listOf("/work/PRD.md"), work.files)
        assertFalse(work.canDiscard)
    }
    @Test fun `native views stay hidden until all nested overlays close`() {
        val overlays = NativeOverlayRegistry()
        val dialog = Any(); val menu = Any()
        overlays.enter(dialog); overlays.enter(menu)
        overlays.leave(menu); assertTrue(overlays.active)
        overlays.leave(dialog); assertFalse(overlays.active)
    }
    @Test fun `overlay cleanup is idempotent`() {
        val overlays = NativeOverlayRegistry(); val key = Any()
        overlays.enter(key); overlays.enter(key)
        overlays.leave(key); overlays.leave(key)
        assertFalse(overlays.active)
    }
}
