package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/usage` 面板的解析。**下面这段是真面板原样抠出来的**（2026-08-24 实测）。
 */
class QuotaTest {

    private val REAL = """
   Usage:                 0 input, 0 output, 0 cache read, 0 cache write

   Current session
   █████                                              10% used
   Resets 4:59am (UTC)

   Current week (all models)
   ████                                               8% used
   Resets Aug 30, 12:59pm (UTC)
   +50% weekly limits promo through Aug 31 · clau.de/cc-50-promo

   Current week (Fable)
                                                      0% used

   What's contributing to your limits usage?
   Last 24h · these are independent characteristics of your usage, not a breakdown
   94% of your usage was at >150k context
"""

    @Test fun 解出两个百分比和重置时间() {
        val q = Quota.parse(REAL)!!
        assertEquals(10, q.sessionPct)
        assertEquals("4:59am (UTC)", q.sessionResets)
        assertEquals(8, q.weekPct)
        assertEquals("Aug 30, 12:59pm (UTC)", q.weekResets)
    }

    @Test fun 不能把分项模型的百分比当成周用量() {
        // ⚠️ 面板上「Current week (Fable)」也带 `% used`（这里是 0%）。
        // 按顺序数第几个 `% used` 的话，账号一开新模型就整片错位。
        // 锚点必须是标题文字。
        val q = Quota.parse(REAL)!!
        assertEquals("周用量必须是 all models 那个 8%，不是 Fable 的 0%", 8, q.weekPct)
    }

    @Test fun 不能把正文里的百分比读进来() {
        // ⚠️ 面板底部有「94% of your usage was at >150k context」——
        // 那是说明文字，不是额度。它离标题很远，靠「标题往下最多 4 行」挡住
        val q = Quota.parse(REAL)!!
        assertEquals(10, q.sessionPct)
        assertEquals(8, q.weekPct)
    }

    @Test fun 面板没打开就返回空() {
        // 抓屏抓到的是普通对话画面 —— 宁可不显示，也不能显示一个瞎猜的数
        assertNull(Quota.parse("● 好的，我来看看\n\n❯ \n  ⏵⏵ bypass permissions on"))
        assertNull(Quota.parse(""))
    }

    @Test fun 只有一半也不认() {
        // 面板还没画完就抓屏了 —— 这时候两段缺一段，整条判为拿不到
        val half = "   Current session\n   █████     10% used\n   Resets 4:59am (UTC)\n"
        assertNull("缺了周用量就不该给半个结果", Quota.parse(half))
    }
}
