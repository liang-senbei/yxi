package app.yxi.desktop

import kotlin.test.*

class FileDocumentTest {
    @Test fun `editing a host cannot redirect an open document to another endpoint`() {
        val host = Host("a", "Hong Kong", "hk.example")
        val doc = FileDocument(host.id, "task", "/work/readme.md", DocumentEndpoint.of(host))
        doc.checkEndpoint(host.copy(alias = "Renamed"))
        assertFails { doc.checkEndpoint(host.copy(hostname = "other.example")) }
        assertFails { doc.checkEndpoint(host.copy(port = 2222)) }
        assertFails { doc.checkEndpoint(host.copy(username = "other")) }
        assertFails { doc.checkEndpoint(host.copy(id = "b")) }
    }
    private fun snapshot(s: String) = FileSnapshot(s.toByteArray())
    @Test fun `remote change does not replace unsaved edits`() {
        val doc = FileDocument("host-a", "same-task", "/work/PRD.md")
        doc.receive(snapshot("original"))
        doc.editor = androidx.compose.ui.text.input.TextFieldValue("my edit")
        doc.receive(snapshot("agent edit"))
        assertTrue(doc.conflict); assertEquals("my edit", doc.editor.text)
        assertEquals("original", String(doc.base!!.bytes))
    }
    @Test fun `clean preview follows remote changes`() {
        val doc = FileDocument("a", "task", "/a.md")
        doc.receive(snapshot("one")); doc.receive(snapshot("two"))
        assertEquals("two", doc.editor.text); assertFalse(doc.dirty); assertFalse(doc.conflict)
    }
    @Test fun `save response lost can reconcile exact saved content`() {
        val doc = FileDocument("a", "task", "/a.md")
        doc.receive(snapshot("one")); doc.editor = androidx.compose.ui.text.input.TextFieldValue("two")
        doc.receive(snapshot("two"))
        assertFalse(doc.dirty); assertFalse(doc.conflict)
    }
    @Test fun `explicitly loading remote resolves conflict`() {
        val doc = FileDocument("a", "task", "/a.md")
        doc.receive(snapshot("one")); doc.editor = androidx.compose.ui.text.input.TextFieldValue("two")
        doc.receive(snapshot("three")); doc.useIncoming()
        assertEquals("three", doc.editor.text); assertFalse(doc.dirty)
    }
    @Test fun `binary invalid encoding and oversized data cannot be edited`() {
        assertFails { decodeDocument(byteArrayOf(0, 1)) }
        assertFails { decodeDocument(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { decodeDocument(ByteArray(TEXT_LIMIT + 1) { 65 }) }
    }
    @Test fun `same path on another server has independent edits`() {
        val a = FileDocument("a", "same", "/x.md"); val b = FileDocument("b", "same", "/x.md")
        a.receive(snapshot("A")); b.receive(snapshot("B"))
        a.editor = androidx.compose.ui.text.input.TextFieldValue("A edited")
        assertEquals("B", b.editor.text); assertFalse(b.dirty)
    }
}
