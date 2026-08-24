package app.yxi.ui

import app.yxi.agent.Session
import app.yxi.agent.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只通知置顶的」这条规则。
 *
 * ⚠️ 它错了的表现是**一条通知都收不到，而且没有任何报错** ——
 * 用户会以为是权限没给（正是 TROUBLESHOOTING #91 那种排查），所以必须有测试盯着。
 */
class PinnedTest {

    @Test fun 开着的时候只有置顶的会响() {
        val pins = setOf("cc-Yxi", "cc-mail")
        assertTrue(Pinned.shouldNotify(true, pins, "cc-Yxi"))
        assertFalse(Pinned.shouldNotify(true, pins, "cc-anchor"))
    }

    @Test fun 关掉之后全都响() {
        assertTrue(Pinned.shouldNotify(false, setOf("cc-Yxi"), "cc-anchor"))
    }

    @Test fun 一条都没置顶时不生效() {
        // ⚠️ 故意的：否则新装的人什么通知都收不到，而设置页两个绿勾都亮着（#91）
        assertTrue(Pinned.shouldNotify(true, emptySet(), "cc-anchor"))
    }

    @Test fun 置顶存的和事件里的必须是同一种形式() {
        // ⚠️ 这条是真正的地雷。置顶存的是 Session.name（**带** cc- 前缀），
        // 事件里的 session 字段也带前缀；而 EventService 里另有一个去了前缀的短名变量。
        // 拿短名去比 → 一条都对不上 → 全静音，且无报错。
        val s = Session(
            name = "cc-Yxi", windows = 1, attached = false, cwd = "/root/src/workspace/Yxi",
            lastActivity = 0, state = SessionState.Idle, detail = "", stateTs = 0.0,
        )
        assertTrue("Session.name 必须带 cc- 前缀，界面上用的是 short", s.name.startsWith("cc-"))
        assertEquals("Yxi", s.short)

        // 真事件里的字段就长这样（从 ~/.yxi/events.jsonl 抠的）
        val eventSession = org.json.JSONObject(
            """{"kind":"needs","session":"cc-Yxi","cwd":"/root/src/workspace/Yxi"}"""
        ).getString("session")

        assertTrue(
            "置顶了 cc-Yxi，cc-Yxi 的事件就必须响 —— 对不上说明两边前缀形式不一致",
            Pinned.shouldNotify(true, setOf(s.name), eventSession),
        )
    }
}
