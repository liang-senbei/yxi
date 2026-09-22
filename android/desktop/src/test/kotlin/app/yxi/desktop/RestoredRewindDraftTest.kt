package app.yxi.desktop

import java.security.MessageDigest
import kotlin.test.*

class RestoredRewindDraftTest {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun screen(text: String) = "────────────────────\n❯ $text\n────────────────────\n  ⏸ manual mode on"
    @Test fun `only exact previously submitted draft is eligible`() {
        assertTrue(RestoredRewindDraft.matches(screen("original"), hash("original")))
        assertFalse(RestoredRewindDraft.matches(screen("original edited"), hash("original")))
        assertFalse(RestoredRewindDraft.matches(screen(" original"), hash("original")))
        assertFalse(RestoredRewindDraft.matches(screen(""), hash("")))
        assertTrue(RestoredRewindDraft.matches(screen("一\n  二🙂"), hash("一\n二🙂")))
        assertFalse(RestoredRewindDraft.matches(screen("wrap\n  ped"), hash("wrapped")))
    }
}
