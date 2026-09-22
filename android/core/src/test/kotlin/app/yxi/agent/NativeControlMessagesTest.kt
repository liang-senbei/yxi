package app.yxi.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class NativeControlMessagesTest {
    private fun marker() = JSONObject().put("type", "user").put("uuid", "marker")
        .put("parentUuid", "parent").put("sessionId", "11111111-1111-1111-1111-111111111111")
        .put("session_id", "11111111-1111-1111-1111-111111111111")
        .put("entrypoint", "cli").put("version", "2.1.278")
        .put("message", JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", NativeControlMessages.INTERRUPTION))))

    @Test fun `native interruption is a system item and cannot acknowledge queued user text`() {
        val parser = Transcript.Incremental()
        val queue = JSONObject().put("type", "queue-operation").put("operation", "enqueue")
            .put("content", NativeControlMessages.INTERRUPTION)
        parser.add(sequenceOf(queue.toString(), marker().toString()))
        val items = parser.snapshot()
        assertTrue(items.none { it is ChatItem.UserText })
        assertEquals(1, items.filterIsInstance<ChatItem.Injected>().size)
        assertEquals(NativeControlMessages.INTERRUPTION, items.filterIsInstance<ChatItem.Queued>().single().text)
    }

    @Test fun `typed identical text and other session metadata remain user messages`() {
        for (record in listOf(marker().put("origin", "user"), marker().put("session_id", "other"),
            marker().apply { remove("session_id") })) {
            assertFalse(NativeControlMessages.isInterruption(record))
            assertEquals(1, Transcript.parse(sequenceOf(record.toString())).filterIsInstance<ChatItem.UserText>().size)
        }
    }
}
