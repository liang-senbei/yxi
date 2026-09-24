package app.yxi.desktop

import app.yxi.agent.ChatItem
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.*

class LocalClaudeHistoryTest {
    @TempDir lateinit var root: File
    @Test fun `native history is identified exactly and never rewritten`() {
        val id = UUID.randomUUID().toString()
        val record = LocalCodexTaskRecord(id, "fixture", "fixture", root.path, root.path, "same title", "model", 1L, "claude")
        val file = File(root, "projects/project/$id.jsonl").apply { parentFile.mkdirs() }
        val row = JSONObject().put("type", "user").put("uuid", UUID.randomUUID().toString()).put("parentUuid", JSONObject.NULL)
            .put("sessionId", id).put("cwd", root.canonicalPath).put("message", JSONObject().put("role", "user").put("content", "old question"))
        file.writeText(row.toString() + "\n")
        val before = file.readBytes()
        val items = LocalClaudeHistory.read(record)
        assertTrue(items.any { it is ChatItem.UserText && it.text == "old question" })
        assertContentEquals(before, file.readBytes())
        row.put("sessionId", UUID.randomUUID().toString()); file.writeText(row.toString())
        assertFailsWith<IllegalStateException> { LocalClaudeHistory.read(record) }
        file.writeBytes(before)
        val duplicate = File(root, "projects/other/$id.jsonl").apply { parentFile.mkdirs(); writeBytes(before) }
        assertFailsWith<IllegalStateException> { LocalClaudeHistory.read(record) }
        assertTrue(duplicate.delete())
        file.writeText("x".repeat(8 * 1024 * 1024 + 1))
        assertFailsWith<IllegalStateException> { LocalClaudeHistory.read(record) }
    }
}
