package app.yxi.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** TranscriptBranch 边界补测（cc-yxi_pilot）：真 CLI 转录里两次人类发言之间夹着
 * tool_result user 行与 isMeta 命令回实行 —— 它们都不是分支点，也不得把叶子搅乱。 */
class TranscriptBranchEdgeTest {
    private fun row(id: String, parent: String?, text: String, assistant: Boolean = false, tokens: Long = 100): String =
        JSONObject()
            .put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL)
            .put("type", if (assistant) "assistant" else "user")
            .put("message", JSONObject().put("role", if (assistant) "assistant" else "user")
                .put("content", if (assistant) JSONArray().put(JSONObject().put("type", "text").put("text", text)) else text)
                .apply { if (assistant) { put("model", "test-model"); put("usage", JSONObject().put("input_tokens", tokens)) } })
            .toString()

    /** 真 CLI 形态：工具结果以 user 行回灌（content 数组、tool_result 块）。 */
    private fun toolResult(id: String, parent: String?, text: String): String = JSONObject()
        .put("uuid", id).put("parentUuid", parent)
        .put("type", "user")
        .put("message", JSONObject().put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "tool_result")
                .put("tool_use_id", "call-1").put("content", text))))
        .toString()

    /** 真 CLI 形态：/effort 之类命令回执 = isMeta user 行、纯字符串 content。 */
    private fun metaReceipt(id: String, parent: String?, text: String): String = JSONObject()
        .put("uuid", id).put("parentUuid", parent)
        .put("type", "user").put("isMeta", true)
        .put("message", JSONObject().put("role", "user").put("content", text))
        .toString()

    private fun users(p: Transcript.Incremental) = p.snapshot().filterIsInstance<ChatItem.UserText>().map { it.text }
    private fun assistants(p: Transcript.Incremental) = p.snapshot().filterIsInstance<ChatItem.AssistantText>().map { it.markdown }

    @Test fun `tool_result turns between humans neither rewind nor block a later rewind`() {
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(
            row("u1", null, "first"), row("a1", "u1", "answer", true),
            toolResult("tr1", "a1", "ok"), row("a2", "tr1", "old answer", true),
            row("u2", "a2", "old second")))
        // tr1/a2/u2 一路接续：普通链上新节点不得触发回退
        assertEquals(listOf("first", "old second"), users(parser))
        assertEquals(listOf("answer", "old answer"), assistants(parser))
        // 从 a1 分支重写：被弃支（含 tool_result 派生行）整体移除
        parser.add(sequenceOf(row("replacement", "a1", "edited second")))
        assertEquals(listOf("first", "edited second"), users(parser))
        assertEquals(listOf("answer"), assistants(parser))
    }

    @Test fun `isMeta command receipts never rewind and are dropped with the abandoned branch`() {
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(
            row("u1", null, "first"), row("a1", "u1", "answer", true),
            row("u2", "a1", "second"),
            metaReceipt("m1", "u2", "<local-command-stdout>Set effort level to xhigh (saved)")))
        // 回实行只跟链、不动叶子、不回退
        assertEquals(listOf("first", "second"), users(parser))
        // 真人类发言从 u1 分支：m1/u2 连同挂在 u1 下的 a1 同属被弃支，一并移除（可见历史只剩 u1 链）
        parser.add(sequenceOf(row("replacement", "u1", "edited")))
        assertEquals(listOf("first", "edited"), users(parser))
        assertEquals(emptyList(), assistants(parser), "a1 在回退点之后：属被弃支，不残留")
        assertNull(parser.ctx, "保留支上没有任何 assistant：ctx 必须为空，不得残留旧值")
    }

    @Test fun `ctx usage is rebuilt from the kept branch after rewind`() {
        // ctx = 最后一条 assistant 报的「本轮上下文量」（last-value；真实转录里 cache_read
        // 让它天然逐轮累计），不是跨条求和 —— 断言按 last-value 语义写。
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(
            row("u1", null, "first"), row("a1", "u1", "answer", true, tokens = 100),
            row("u2", "a1", "second"), row("a2", "u2", "big answer", true, tokens = 9000)))
        assertEquals(9000L, parser.ctx?.tokens, "回退前：取最后一条 assistant 的本轮用量")
        // 从 a1 分支重写：a2 的 9000 不得残留，ctx 重建为保留支 a1 的 100
        parser.add(sequenceOf(row("replacement", "a1", "edited second")))
        assertEquals(100L, parser.ctx?.tokens, "回退后：旧支 9000 不得残留")
        assertEquals("test-model", parser.ctx?.model)
    }
}
