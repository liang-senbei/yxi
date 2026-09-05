package app.yxi.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 2026-09-05 搬进会员服务（`/api/support/tickets`），客户端只剩「读服务端的 JSON」这一段逻辑。
 * 守两件：回复的 by 分得清官方 / 用户；服务端加了新分类老客户端不崩。
 */
class TicketsTest {

    @Test fun 解析一条带回复的工单() {
        val o = JSONObject(
            """{"id":7,"category":"payment","text":"充了没到账","status":"replied",
                "createdAt":"2026-09-05T10:00:00+08:00","updatedAt":"2026-09-05T11:00:00+08:00",
                "replies":[{"by":"official","text":"已补发","at":"2026-09-05T10:30:00+08:00"},
                           {"by":"user","text":"收到","at":"2026-09-05T11:00:00+08:00"}],
                "unread":true}""",
        )
        val tk = Tickets.parse(o)
        assertEquals("7", tk.id)
        assertEquals(Tickets.Category.Payment, tk.category)
        assertEquals("replied", tk.status)
        assertTrue(tk.unread)
        assertEquals(2, tk.replies.size)
        assertTrue(tk.replies[0].official)
        assertFalse(tk.replies[1].official)
    }

    @Test fun 字段缺了不抛且默认待处理() {
        val tk = Tickets.parse(JSONObject("""{"id":1,"text":"x"}"""))
        assertEquals("open", tk.status)
        assertEquals(0, tk.replies.size)
        assertFalse(tk.unread)
    }

    @Test fun 未知分类归到其他() {
        assertEquals(Tickets.Category.Other, Tickets.Category.of("weird"))
        assertEquals(Tickets.Category.Bug, Tickets.Category.of("bug"))
    }
}
