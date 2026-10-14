package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

/** M3a：各运行器的消息转成 TranscriptItem。样例照各自协议的形状写（内容是编的），只核分类、标题、详情和 key。 */
class TranscriptItemTest {
    @Test fun `claude blocks become user, text and tool items`() {
        val image = InstructionAttachment("a.png", "/yxi-local-claude-image/a.png")
        assertEquals(TranscriptItem.User("user-1", "看看这个", listOf(image)), Transcripts.claude(ClaudeMessage("user-1", "User", "看看这个", attachments = listOf(image))))
        assertEquals(TranscriptItem.AssistantText("u:0", "好的"), Transcripts.claude(ClaudeMessage("u:0", "Assistant", "好的", "text")))
        assertEquals(TranscriptItem.Tool("u:1", "Bash", "{\"command\":\"ls\"}"), Transcripts.claude(ClaudeMessage("u:1", "Assistant", "Bash\n{\"command\":\"ls\"}", "tool_use")))
        assertEquals(TranscriptItem.Tool("t:0", "工具结果", "a.txt"), Transcripts.claude(ClaudeMessage("t:0", "Tool", "a.txt", "tool_result")))
    }

    @Test fun `acp tool cards keep their title and status`() {
        assertEquals(TranscriptItem.User("user-q", "跑测试"), Transcripts.acp(AcpMessage("user-q", "User", "跑测试")))
        assertEquals(TranscriptItem.AssistantText("m1", "测试都过了"), Transcripts.acp(AcpMessage("m1", "Assistant", "测试都过了")))
        assertEquals(TranscriptItem.Tool("call-1", "npm test", "ok", "completed"), Transcripts.acp(AcpMessage("call-1", "npm test", "ok", "tool", "completed")))
        assertEquals(TranscriptItem.Tool("call-2", "工具", "", ""), Transcripts.acp(AcpMessage("call-2", "", "", "tool")))
    }

    @Test fun `codex commands split the command from its output and file changes list names`() {
        assertEquals(TranscriptItem.User("u", "改一下"), Transcripts.codex(CodexMessage("u", "你", "改一下")))
        assertEquals(TranscriptItem.AssistantText("a", "改好了"), Transcripts.codex(CodexMessage("a", "Codex", "改好了")))
        assertEquals(TranscriptItem.Tool("c", "npm test", "目录：/srv/app\nok\n退出码：0", "completed"),
            Transcripts.codex(CodexMessage("c", "执行命令", "npm test\n目录：/srv/app\nok\n退出码：0", "command", "completed")))
        val files = Transcripts.codex(CodexMessage("f", "文件改动", "src/a.ts\n+x", "files", "completed", listOf("src/a.ts", "C:\\w\\b.ts")))
        assertEquals("改动 a.ts、b.ts", (files as TranscriptItem.Tool).title)
        assertEquals(listOf("src/a.ts", "C:\\w\\b.ts"), files.paths)
    }

    @Test fun `opencode messages split into parts and drop step markers`() {
        fun part(type: String, vararg pairs: Pair<String, Any>) = JSONObject().put("type", type).apply { pairs.forEach { (k, v) -> put(k, v) } }
        val user = JSONObject().put("info", JSONObject().put("id", "msg_u").put("role", "user"))
            .put("parts", JSONArray().put(part("text", "text" to "第一段")).put(part("text", "text" to "注入的", "synthetic" to true)).put(part("text", "text" to "第二段")))
        assertEquals(listOf(TranscriptItem.User("msg_u", "第一段\n第二段")), Transcripts.openCode(user))
        val assistant = JSONObject().put("info", JSONObject().put("id", "msg_a").put("role", "assistant"))
            .put("parts", JSONArray().put(part("step-start")).put(part("reasoning", "text" to "先看目录")).put(part("reasoning", "text" to " "))
                .put(part("tool", "tool" to "bash", "state" to JSONObject().put("status", "completed").put("input", JSONObject().put("command", "ls")).put("output", "a.txt")))
                .put(part("text", "text" to "目录里只有 a.txt")).put(part("file", "url" to "file:///a.txt")).put(part("step-finish")))
        val items = Transcripts.openCode(assistant)
        assertEquals(listOf("msg_a:1", "msg_a:3", "msg_a:4", "msg_a:5"), items.map { it.key }, "step 标记和空白思考不出，其余按段号")
        assertEquals(TranscriptItem.Thinking("msg_a:1", "先看目录"), items[0])
        (items[1] as TranscriptItem.Tool).let { assertEquals("bash", it.title); assertEquals("completed", it.status); assertTrue("\"command\": \"ls\"" in it.detail && it.detail.endsWith("a.txt")) }
        assertEquals(TranscriptItem.AssistantText("msg_a:4", "目录里只有 a.txt"), items[2])
        assertTrue(items[3] is TranscriptItem.Notice, "认不出的段原样给，不吞掉")
    }

    @Test fun `tool statuses read in Chinese and unknown ones pass through`() {
        assertEquals(listOf("等待中", "进行中", "进行中", "进行中", "已完成", "失败", "失败", "已拒绝", "weird"),
            listOf("pending", "in_progress", "inProgress", "running", "completed", "failed", "error", "declined", "weird").map(::toolStatusName))
    }
}
