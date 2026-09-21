package app.yxi.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 解析侧丢更新回归：同 key 的后到行是「更新」不是「重复」。
 * 真机症状：终端已往下走，对话页卡在第一版文本（缓存滞留，老板 2026-09-21 报）。 */
class TranscriptIncrementalUpdateTest {
    private fun assistantLine(uuid: String, vararg blocks: Pair<String, String>) = JSONObject()
        .put("type", "assistant")
        .put("uuid", uuid)
        .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().apply {
            blocks.forEach { (type, text) ->
                put(JSONObject().put("type", type).put(if (type == "thinking") "thinking" else "text", text))
            }
        }))
        .toString()

    private fun userLine(text: String) = JSONObject()
        .put("type", "user")
        .put("uuid", "u-$text")
        .put("message", JSONObject().put("role", "user").put("content", text))
        .toString()

    @Test fun `rewritten assistant line updates the visible text instead of staying stale`() {
        val p = Transcript.Incremental()
        p.add(listOf(assistantLine("m1", "text" to "第一版")).asSequence())
        assertEquals("第一版", (p.snapshot().single() as ChatItem.AssistantText).markdown)
        // 同 uuid 的行被改写/重放喂进来（流式补全、复核窗口重叠）：必须换文本，不能丢
        p.add(listOf(assistantLine("m1", "text" to "第一版，加上后续补全的完整内容")).asSequence())
        val updated = p.snapshot().single() as ChatItem.AssistantText
        assertEquals("第一版，加上后续补全的完整内容", updated.markdown)
    }

    @Test fun `exact duplicate lines stay single item`() {
        val p = Transcript.Incremental()
        val line = assistantLine("m1", "text" to "同一条")
        p.add(listOf(line, line).asSequence())
        assertEquals(1, p.snapshot().size)
    }

    @Test fun `distinct uuids keep every item in feed order`() {
        val p = Transcript.Incremental()
        p.add(listOf(
            assistantLine("m1", "text" to "甲"),
            assistantLine("m2", "text" to "乙"),
            assistantLine("m3", "text" to "丙"),
        ).asSequence())
        val texts = p.snapshot().map { (it as ChatItem.AssistantText).markdown }
        assertEquals(listOf("甲", "乙", "丙"), texts)
    }

    @Test fun `multi block assistant message keeps every block`() {
        val p = Transcript.Incremental()
        p.add(listOf(assistantLine("m1", "thinking" to "想想", "text" to "正文", "text" to "补充")).asSequence())
        val items = p.snapshot()
        assertEquals(3, items.size)
        assertEquals(setOf("m1-0", "m1-1", "m1-2"), items.map { it.key }.toSet())
        assertTrue(items[0] is ChatItem.Thinking)
        assertEquals("正文", (items[1] as ChatItem.AssistantText).markdown)
    }

    @Test fun `queued bubbles still render and leave once said`() {
        val p = Transcript.Incremental()
        p.add(listOf(
            """{"type":"queue-operation","operation":"enqueue","content":"排队甲"}""",
            """{"type":"queue-operation","operation":"enqueue","content":"排队乙"}""",
        ).asSequence())
        val queued = p.snapshot().filterIsInstance<ChatItem.Queued>()
        assertEquals(listOf("排队甲", "排队乙"), queued.map { it.text }) // 有序 List 不合成一条
        // 被处理后（作为用户消息出现）：排队气泡消失，正文出现 —— 去重兜底不被 update-wins 破坏
        p.add(listOf(userLine("排队甲")).asSequence())
        val after = p.snapshot()
        assertEquals(emptyList(), after.filterIsInstance<ChatItem.Queued>().map { it.text }.filter { it == "排队甲" })
        assertEquals("排队甲", after.filterIsInstance<ChatItem.UserText>().single().text)
    }
}
