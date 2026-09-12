package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class InstructionQueueTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-instructions").toFile()
        try { block(File(dir, "queue.json")) } finally { dir.deleteRecursively() }
    }
    @Test fun `editing keeps attachments and stale editor cannot undo delivery`() = fixture { file ->
        val q = InstructionQueue(file)
        val a = q.enqueue("host-and-runtime-a", "original", listOf(InstructionAttachment("image", "/tmp/image.png")))
        val edited = q.edit(a.id, a.revision, "changed")
        assertEquals(a.attachments, edited.attachments)
        val delivering = q.beginDelivery(edited.id, edited.revision)
        assertFails { q.edit(edited.id, edited.revision, "stale") }
        assertFails { q.cancel(delivering.id, delivering.revision) }
        assertEquals(InstructionStatus.Delivering, q.entries.single().status)
        val reopened = InstructionQueue(file)
        assertEquals(InstructionStatus.Unknown, reopened.entries.single().status)
        assertFails { reopened.beginDelivery(a.id, reopened.entries.single().revision) }
    }
    @Test fun `identity deduplication and ordering are isolated by task`() = fixture { file ->
        val q = InstructionQueue(file)
        val first = q.enqueue("a", "first", id = "one")
        val second = q.enqueue("a", "second", id = "two")
        val other = q.enqueue("b", "other", id = "three")
        assertEquals(first, q.enqueue("a", "first", id = "one"))
        assertFails { q.enqueue("b", "different", id = "one") }
        assertFails { q.beginDelivery(second.id, second.revision) }
        assertFails { q.moveBefore(second.id, second.revision, other.id) }
        q.moveBefore(second.id, second.revision, first.id)
        assertEquals(listOf("two", "one", "three"), InstructionQueue(file).entries.map { it.id })
        val sending = q.beginDelivery(second.id, q.entries.first().revision)
        val unknown = q.markUnknown(sending.id, sending.revision, "connection lost")
        assertFails { q.beginDelivery(first.id, first.revision) }
        q.beginDelivery(other.id, other.revision)
        assertFails { q.confirmAccepted(unknown.id, unknown.revision, "") }
        q.confirmAccepted(unknown.id, unknown.revision, "runner receipt")
        q.beginDelivery(first.id, first.revision)
    }
    @Test fun `backup cannot turn an attempted send into a retryable instruction`() = fixture { file ->
        val q = InstructionQueue(file)
        val item = q.enqueue("a", "payload")
        q.beginDelivery(item.id, item.revision)
        file.writeText("broken")
        val recovered = InstructionQueue(file)
        assertEquals(InstructionStatus.Unknown, recovered.entries.single().status)
        assertTrue(recovered.error.contains("备份"))
        assertFails { recovered.beginDelivery(item.id, recovered.entries.single().revision) }
    }
    @Test fun `failed persistence cannot release an instruction for network IO`() = fixture { file ->
        val q = InstructionQueue(file)
        val item = q.enqueue("a", "payload")
        file.writeText("broken") // No valid backup yet: read and write must fail closed.
        assertFails { q.beginDelivery(item.id, item.revision) }
        assertEquals(InstructionStatus.Local, q.entries.single().status)
        assertEquals("broken", file.readText())
        assertFails { InstructionQueue(file).enqueue("a", "replacement") }
    }
}
