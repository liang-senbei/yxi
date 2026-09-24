package app.yxi.desktop

import app.yxi.agent.ChatItem
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.*

class LocalClaudeHistoryTest {
    @TempDir lateinit var root: File
    private fun record(): LocalCodexTaskRecord = LocalCodexTaskRecord(UUID.randomUUID().toString(), "fixture", "fixture", root.path,
        root.path, "History", "model", 1L, "claude")
    private fun file(record: LocalCodexTaskRecord) = File(root, "projects/project/${record.threadId}.jsonl").apply { parentFile.mkdirs() }
    private fun row(record: LocalCodexTaskRecord, id: String, parent: String?, text: String, type: String = "user"): JSONObject =
        JSONObject().put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL).put("type", type)
            .put("sessionId", record.threadId).put("cwd", root.canonicalPath)
            .put("message", JSONObject().put("role", type).put("content", if (type == "user") text else
                org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))))
    private fun allPages(record: LocalCodexTaskRecord, pageSize: Int): List<ChatItem> {
        var page = LocalClaudeHistory.page(record, pageSize = pageSize)
        var items = page.items
        while (page.earlier != null) {
            page = LocalClaudeHistory.page(record, page.earlier, pageSize)
            items = page.items + items
        }
        return items
    }
    @Test fun `large history reduces page payload and reads all earlier messages without truncating`() {
        val record = record(); val file = file(record)
        file.bufferedWriter().use { out ->
            for (i in 0 until 14) out.append(row(record, "message-$i", if (i == 0) null else "message-${i - 1}", "text-$i " + "x".repeat(900_000)).toString()).append('\n')
        }
        assertTrue(file.length() > 8 * 1024 * 1024)
        val latest = LocalClaudeHistory.page(record)
        assertNotNull(latest.earlier)
        assertTrue(latest.items.size < 14)
        val all = allPages(record, 100)
        assertEquals((0 until 14).map { "message-$it" }, all.map { it.key })
        assertEquals(14, all.size)
    }
    @Test fun `pagination preserves selected branch replay updates and cross page tool results`() {
        val record = record(); val file = file(record)
        val tool = row(record, "tool", "branch", "analysis", "assistant")
        tool.getJSONObject("message").getJSONArray("content").put(JSONObject().put("type", "tool_use").put("id", "read-1")
            .put("name", "Read").put("input", JSONObject().put("file_path", "fixture.txt")))
        val result = row(record, "result", "tool", "")
        result.getJSONObject("message").put("content", org.json.JSONArray().put(JSONObject().put("type", "tool_result")
            .put("tool_use_id", "read-1").put("content", "full result")))
        result.put("toolUseResult", JSONObject().put("stdout", "full result"))
        val rows = listOf(row(record, "root", null, "first"), row(record, "answer", "root", "old answer", "assistant"),
            row(record, "discarded", "answer", "old branch"), row(record, "discarded-answer", "discarded", "discarded text", "assistant"),
            row(record, "branch", "answer", "new branch"), tool, result, JSONObject(tool.toString()),
            row(record, "final", "result", "original", "assistant"), row(record, "final", "result", "updated", "assistant"),
            row(record, "sidechain", "final", "hidden").put("isSidechain", true))
        file.writeText(rows.joinToString("\n", postfix = "\n"))
        val expected = app.yxi.agent.Transcript.parse(rows.asSequence().map { it.toString() })
        val actual = allPages(record, 1)
        assertEquals(expected.map { it.key }, actual.map { it.key })
        assertEquals(actual.size, actual.map { it.key }.distinct().size)
        assertTrue(actual.none { it.key.startsWith("discarded") || it.key == "sidechain" })
        assertEquals("updated", (actual.last() as ChatItem.AssistantText).markdown)
        val call = actual.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("full result", call.result)
        assertEquals("full result", call.meta?.getString("stdout"))
    }
    @Test fun `page context carries mode and ponytail through later assistant usage`() {
        val record = record(); val file = file(record)
        val usage = row(record, "answer", "root", "response", "assistant")
        usage.getJSONObject("message").put("model", "native-model").put("usage", JSONObject().put("input_tokens", 7))
        val rows = listOf(row(record, "root", null, "hello"),
            JSONObject().put("type", "mode").put("mode", "plan"),
            JSONObject().put("type", "attachment").put("attachment", JSONObject().put("type", "hook_progress").put("content", "PONYTAIL MODE ACTIVE — level: ultra")), usage)
        file.writeText(rows.joinToString("\n", postfix = "\n"))
        val full = app.yxi.agent.Transcript.Incremental().apply { add(rows.asSequence().map { it.toString() }) }
        val page = LocalClaudeHistory.page(record, pageSize = 1)
        assertEquals(full.ctx, page.context)
        assertEquals("plan", page.context?.mode)
        assertEquals("ultra", page.context?.ponytail)
        assertEquals(full.snapshot(), allPages(record, 1))
    }
    @Test fun `old cursor remains stable after append but rejects prefix edits and replacement`() {
        val record = record(); val file = file(record)
        val rows = (0 until 4).map { row(record, "id-$it", if (it == 0) null else "id-${it - 1}", "question-$it") }
        file.writeText(rows.joinToString("\n", postfix = "\n"))
        val first = LocalClaudeHistory.page(record, pageSize = 1)
        val cursor = assertNotNull(first.earlier)
        file.appendText(row(record, "id-4", "id-3", "appended").toString() + "\n")
        assertEquals("id-2", LocalClaudeHistory.page(record, cursor, 1).items.single().key)
        assertEquals("id-4", LocalClaudeHistory.page(record, pageSize = 1).items.single().key)
        val bytes = file.readBytes()
        file.writeText(file.readText().replace("question-0", "modified-0"))
        assertFailsWith<IllegalStateException> { LocalClaudeHistory.page(record, cursor) }
        file.writeBytes(bytes)
        val replacement = File(file.parentFile, "replacement").apply { writeBytes(bytes) }
        java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFailsWith<IllegalStateException> { LocalClaudeHistory.page(record, cursor) }
    }
    @Test fun `incomplete tail is explicit and does not swallow complete earlier messages`() {
        val record = record(); val file = file(record)
        file.writeText(row(record, "complete", null, "visible").toString() + "\n{\"type\":")
        val page = LocalClaudeHistory.page(record)
        assertTrue(page.partialTail)
        assertEquals("visible", (page.items.single() as ChatItem.UserText).text)
        file.writeText(row(record, "wrong", null, "wrong cwd").put("cwd", root.resolve("other").path).toString() + "\n")
        assertFailsWith<IllegalStateException> { LocalClaudeHistory.page(record) }
    }
    @Test fun `complete final JSON without newline is readable and resumable`() {
        val record = record(); val file = file(record)
        file.writeText(row(record, "complete", null, "final question").toString())
        val page = LocalClaudeHistory.page(record)
        assertFalse(page.partialTail)
        assertEquals("final question", (page.items.single() as ChatItem.UserText).text)
    }
    @Test fun `many short consecutive messages paginate without missing or duplicating items`() {
        val record = record(); val file = file(record)
        file.bufferedWriter().use { out ->
            for (i in 0 until 5000) out.append(row(record, "short-$i", if (i == 0) null else "short-${i - 1}", "message $i").toString()).append('\n')
        }
        val items = allPages(record, 500)
        assertEquals((0 until 5000).map { "short-$it" }, items.map { it.key })
    }
    @Test fun `queue normalization matches native parser across page boundaries`() {
        val record = record(); val file = file(record)
        fun queue(operation: String, content: String) = JSONObject().put("type", "queue-operation").put("operation", operation).put("content", content)
        val rows = listOf(row(record, "root", null, "already said"), queue("enqueue", "already said"), queue("enqueue", "removed"),
            queue("enqueue", "pending"), queue("remove", "removed"), queue("enqueue", "pending"))
        file.writeText(rows.joinToString("\n", postfix = "\n"))
        val expected = app.yxi.agent.Transcript.parse(rows.asSequence().map { it.toString() })
        assertEquals(expected, allPages(record, 1))
    }
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
