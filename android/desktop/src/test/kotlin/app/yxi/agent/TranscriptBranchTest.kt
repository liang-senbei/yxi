package app.yxi.agent

import org.json.JSONObject
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TranscriptBranchTest {
    private fun row(id: String, parent: String?, text: String, assistant: Boolean = false): String = JSONObject()
        .put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL)
        .put("type", if (assistant) "assistant" else "user")
        .put("message", JSONObject().put("role", if (assistant) "assistant" else "user")
            .put("content", if (assistant) JSONArray().put(JSONObject().put("type", "text").put("text", text)) else text)
            .apply { if (assistant) { put("model", "test-model"); put("usage", JSONObject().put("input_tokens", 100)) } })
        .toString()

    @Test fun `rewind removes abandoned turns and their context across streaming batches`() {
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(row("u1", null, "first"), row("a1", "u1", "answer", true),
            row("u2", "a1", "old second"), row("a2", "u2", "old answer", true),
            row("u3", "a2", "old third")))
        parser.add(sequenceOf(row("replacement", "a1", "edited second")))
        assertEquals(listOf("first", "edited second"), parser.snapshot().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals(listOf("answer"), parser.snapshot().filterIsInstance<ChatItem.AssistantText>().map { it.markdown })
        parser.add(sequenceOf(row("new-answer", "replacement", "new answer", true)))
        assertEquals(4, parser.snapshot().size)
        assertFalse(parser.snapshot().any { it.key.startsWith("u3") })
    }

    @Test fun `same uuid update and assistant siblings do not imply rewind`() {
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(row("u1", null, "first"), row("a1", "u1", "one", true),
            row("a2", "u1", "two", true), row("a1", "u1", "updated", true)))
        assertEquals(listOf("updated", "two"), parser.snapshot().filterIsInstance<ChatItem.AssistantText>().map { it.markdown })
    }

    @Test fun `progress ancestor between turns preserves conversation`() {
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(row("u1", null, "first"), row("a1", "u1", "answer", true),
            """{"type":"progress","uuid":"p1","parentUuid":"a1"}""",
            row("u2", "p1", "second")))
        assertEquals(listOf("first", "second"), parser.snapshot().filterIsInstance<ChatItem.UserText>().map { it.text })
    }

    @Test fun `tail window with unknown ancestors does not invent a rewind`() {
        val parser = Transcript.Incremental()
        parser.add(sequenceOf(row("a1", "outside-window", "answer", true), row("u2", "a1", "second")))
        assertEquals(2, parser.snapshot().size)
    }
}
