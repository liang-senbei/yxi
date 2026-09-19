package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class InstructionQueueTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-instructions").toFile()
        try { block(File(dir, "queue.json")) } finally { dir.deleteRecursively() }
    }
    /** 手写种子条目：仅 decode 必需键；updated=0 表示无时间旧数据。 */
    private fun entry(id: String, status: String, updated: Long, runtime: String = "None",
                      turnId: String = "", text: String = "正文$id") = JSONObject()
        .put("id", id).put("taskKey", "task").put("text", text).put("status", status)
        .put("revision", 1).put("detail", "d").put("attachments", JSONArray())
        .put("updatedAtMillis", updated).put("runtimeTurnId", turnId).put("runtimeTurnState", runtime)
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

    @Test fun `prune clears only terminal entries that carry timestamps`() = fixture { file ->
        file.writeText(JSONObject().put("version", 1).put("items", JSONArray(listOf(
            entry("gone-cancelled", "Cancelled", 1_000_000),
            entry("gone-completed", "Accepted", 1_000_000, runtime = "Completed", turnId = "turn-1"),
            entry("keep-unknown", "Unknown", 1_000_000),
            entry("keep-running", "Accepted", 1_000_000, runtime = "InProgress", turnId = "turn-2"),
            entry("keep-notime", "Cancelled", 0)))).toString())
        val q = InstructionQueue(file)
        assertEquals(0, q.pruneCompleted(0, now = 1_000_000_000)) // 默认不清理
        assertEquals(2, q.pruneCompleted(3, now = 1_000_000_000))
        val byId = q.entries.associateBy { it.id }
        // 终态可清：正文清空，仅保留 id+摘要等去重标识
        assertTrue(byId.getValue("gone-cancelled").contentPurged)
        assertEquals("", byId.getValue("gone-cancelled").text)
        assertTrue(Regex("[a-f0-9]{64}").matches(byId.getValue("gone-completed").contentDigest))
        assertEquals("task", byId.getValue("gone-cancelled").taskKey)
        // Unknown、运行中、无时间旧数据一律不清
        for (keep in listOf("keep-unknown", "keep-running", "keep-notime")) {
            assertFalse(byId.getValue(keep).contentPurged)
            assertEquals("正文$keep", byId.getValue(keep).text)
        }
    }

    @Test fun `purged identity deduplicates same content and rejects changed content`() = fixture { file ->
        val q = InstructionQueue(file)
        val item = q.enqueue("task", "指令甲")
        q.cancel(item.id, item.revision)
        assertEquals(1, q.pruneCompleted(3, now = System.currentTimeMillis() + 14 * 86_400_000L))
        val purged = q.entries.single()
        assertTrue(purged.contentPurged); assertEquals("", purged.text)
        // 同ID同内容：命中保留的去重标识，不重投
        assertEquals(purged.id, q.enqueue("task", "指令甲", id = purged.id).id)
        assertEquals(1, q.entries.size)
        // 同ID改内容：拒绝
        val rejected = assertFailsWith<IllegalStateException> { q.enqueue("task", "指令乙", id = purged.id) }
        assertEquals("同一指令标识对应不同内容", rejected.message)
    }

    @Test fun `reload preserves the purged marker and digest`() = fixture { file ->
        val q = InstructionQueue(file)
        val item = q.enqueue("task", "指令甲")
        q.cancel(item.id, item.revision)
        q.pruneCompleted(3, now = System.currentTimeMillis() + 14 * 86_400_000L)
        val digest = q.entries.single().contentDigest
        val purged = InstructionQueue(file).entries.single()
        assertTrue(purged.contentPurged)
        assertEquals("", purged.text)
        assertEquals(digest, purged.contentDigest)
        assertEquals(InstructionStatus.Cancelled, purged.status)
    }

    @Test fun `ordinary backup rotates away purged bodies`() = fixture { file ->
        file.writeText(JSONObject().put("version", 1).put("items", JSONArray(listOf(
            entry("old", "Cancelled", 1_000_000, text = "旧正文甲")))).toString())
        val q = InstructionQueue(file)
        assertEquals(1, q.pruneCompleted(3, now = 1_000_000_000))
        val backup = File(file.parentFile, file.name + ".bak")
        assertTrue(backup.isFile, "清理后应轮换常规备份")
        for (copy in listOf(file, backup)) {
            assertFalse(copy.readText().contains("旧正文甲"), "${copy.name} 不得残留旧正文")
            assertTrue(JSONObject(copy.readText()).getJSONArray("items").getJSONObject(0).optBoolean("contentPurged"),
                "${copy.name} 应保留 purged 标记：${copy.readText()}")
        }
    }
}
