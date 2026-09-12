package app.yxi.desktop

import app.yxi.agent.SupportApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.*

class SupportApiTest {
    @Test fun `create sends only explicit fields and rejects oversized text without truncation`(): Unit = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val api = SupportApi { path, method, body ->
            assertEquals("/api/support/tickets", path); assertEquals("POST", method)
            sent += JSONObject(body!!); 200 to """{"id":42,"createdAt":"2026-09-13"}"""
        }
        val text = "😀".repeat(2000)
        assertEquals("42", api.create("bug", text, "1.2.0", "Windows test device").id)
        assertEquals(setOf("category", "text", "version", "device"), sent.single().keys().asSequence().toSet())
        assertEquals(text, sent.single().getString("text"))
        assertFails { api.create("bug", text + "x") }
        assertFails { api.create("unknown", "test") }
        assertFails { api.create("bug", "hello\u0000secret") }
        assertEquals(1, sent.size)
    }
    @Test fun `uncertain outcomes differ from rejection and are never retried`(): Unit = runBlocking {
        var calls = 0
        val offline = SupportApi { _, _, _ -> calls++; 0 to "" }
        assertTrue(assertFailsWith<SupportApi.Failure> { offline.create("other", "question") }.uncertain)
        assertEquals(1, calls)
        val limited = SupportApi { _, _, _ -> 429 to "{}" }
        assertFalse(assertFailsWith<SupportApi.Failure> { limited.create("other", "question") }.uncertain)
        val incomplete = SupportApi { _, _, _ -> 200 to "{}" }
        assertTrue(assertFailsWith<SupportApi.Failure> { incomplete.create("other", "question") }.uncertain)
    }
    @Test fun `list preserves unknown roles states and numeric cursor without inventing empty results`(): Unit = runBlocking {
        val api = SupportApi { path, _, _ ->
            assertEquals("/api/support/tickets?limit=30&before=a%26b", path)
            200 to """{"items":[{"id":9,"category":"future","text":"Question","status":"future-state","unread":true,"replies":[{"by":"future-role","text":"Reply","at":"now"}]}],"nextCursor":9,"unread":1}"""
        }
        val page = api.list("a&b")
        assertEquals("9", page.next); assertEquals("future-state", page.items.single().status)
        assertEquals("future-role", page.items.single().replies.single().by)
        assertFails { SupportApi { _, _, _ -> 200 to "{}" }.list() }
    }
    @Test fun `reply returns server state and refuses mismatched ticket receipt`(): Unit = runBlocking {
        var responseId = "a/b"
        val api = SupportApi { path, method, body ->
            assertEquals("/api/support/tickets/a%2Fb/reply", path); assertEquals("POST", method)
            assertEquals(setOf("text"), JSONObject(body!!).keys().asSequence().toSet())
            200 to JSONObject().put("id", responseId).put("category", "other").put("text", "original").put("status", "open")
                .put("replies", org.json.JSONArray()).put("unread", false).toString()
        }
        assertEquals("open", api.reply("a/b", "reopen this ticket").status)
        responseId = "another"
        assertTrue(assertFailsWith<SupportApi.Failure> { api.reply("a/b", "follow up") }.uncertain)
    }
}
