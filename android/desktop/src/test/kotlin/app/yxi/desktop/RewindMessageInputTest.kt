package app.yxi.desktop

import app.yxi.agent.RewindMessageInput
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class RewindMessageInputTest {
    private fun image(data: String = "AQID", media: String = "image/png") = JSONObject().put("type", "image")
        .put("source", JSONObject().put("type", "base64").put("media_type", media).put("data", data))
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun message(vararg blocks: JSONObject) = JSONObject().put("role", "user").put("content", JSONArray(blocks.toList()))

    @Test fun `edit retains exact image bytes and their position without mutating original`() {
        val original = message(image(), text("old"), image("BAUG", "image/jpeg"))
        val before = original.toString()
        val prepared = RewindMessageInput.create(original, "new\n中文")
        val blocks = JSONObject(prepared.json).getJSONObject("message").getJSONArray("content")
        assertEquals(2, prepared.imageCount)
        assertEquals(6, prepared.imageBytes)
        assertEquals("AQID", blocks.getJSONObject(0).getJSONObject("source").getString("data"))
        assertEquals("new\n中文", blocks.getJSONObject(1).getString("text"))
        assertEquals("BAUG", blocks.getJSONObject(2).getJSONObject("source").getString("data"))
        assertEquals(before, original.toString())
    }
    @Test fun `image-only message gains edited text without losing image`() {
        val result = RewindMessageInput.create(message(image()), "caption")
        assertEquals(2, JSONObject(result.json).getJSONObject("message").getJSONArray("content").length())
    }
    @Test fun `plain text uses the same structured user envelope`() {
        val result = RewindMessageInput.create(JSONObject().put("role", "user").put("content", "old"), "new")
        assertEquals(0, result.imageCount)
        assertEquals("new", JSONObject(result.json).getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun `unsupported content is rejected rather than silently dropped`() {
        for (bad in listOf(image("not base64!"), image("AQID", "application/pdf"),
            JSONObject().put("type", "tool_result"), JSONObject().put("type", "document"))) {
            assertFails { RewindMessageInput.create(message(text("old"), bad), "new") }
        }
        assertFails { RewindMessageInput.create(message(text("one"), text("two")), "new") }
        assertFails { RewindMessageInput.create(message(*Array(11) { image() }), "new") }
        assertFails { RewindMessageInput.create(message(image()), " ") }
        assertFails { RewindMessageInput.create(message(image()).put("role", "assistant"), "new") }
    }
}
