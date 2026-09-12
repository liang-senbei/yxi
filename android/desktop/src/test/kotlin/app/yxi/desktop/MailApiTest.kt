package app.yxi.desktop

import app.yxi.agent.MailApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.*

class MailApiTest {
    @Test fun `nullable read and claim states and code-only attachments stay distinct`() {
        val page = MailApi.parsePage(JSONObject("""{"items":[{"id":12,"title":"Gift","body":"Body","readAt":null,"claimedAt":null,"attachments":[{"kind":"code","name":"Pro","code":"ACTUAL-CODE"}]}],"nextCursor":null,"unread":1}"""))
        assertEquals("12", page.items.single().id)
        assertTrue(page.items.single().unread)
        assertFalse(page.items.single().claimable)
        assertEquals("ACTUAL-CODE", page.items.single().attachments.single().shown)
        assertNull(page.next); assertNull(page.unclaimed)
        assertEquals("123", MailApi.parsePage(JSONObject("""{"items":[],"nextCursor":123}""")).next)
        assertFails { MailApi.parsePage(JSONObject("{}")) }
    }
    @Test fun `unknown mutation outcomes never retry or fabricate success`(): Unit = runBlocking {
        var calls = 0
        val offline = MailApi { _, _, _ -> calls++; 0 to "" }
        assertTrue(assertFails { offline.claim("1") }.message!!.contains("未确认"))
        assertEquals(1, calls)
        val incomplete = MailApi { _, _, _ -> 200 to "{}" }
        assertFails { incomplete.read("1") }; assertFails { incomplete.claim("1") }; assertFails { incomplete.delete("1") }
        val conflict = MailApi { _, _, _ -> 409 to "{}" }
        assertTrue(assertFails { conflict.delete("1") }.message!!.contains("先领取"))
    }
    @Test fun `requests preserve encoded cursors and ids without leaking other data`(): Unit = runBlocking {
        val captured = mutableListOf<Triple<String, String, String?>>()
        val api = MailApi { path, method, body ->
            captured += Triple(path, method, body)
            200 to if (method == "GET") """{"items":[],"nextCursor":null}""" else """{"ok":true,"replay":true,"unread":0}"""
        }
        api.list("a&before=other")
        api.read("a/b"); assertTrue(api.claim("a/b").getBoolean("replay")); api.delete("a/b")
        assertEquals("/api/mail?limit=30&before=a%26before%3Dother", captured[0].first)
        assertEquals("/api/mail/a%2Fb/read", captured[1].first)
        assertTrue(captured.drop(1).all { it.second == "POST" && it.third == "{}" })
    }
}
