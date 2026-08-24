package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `claude -p "/usage"` 输出的解析。**下面这段是真输出原样抠的**（2026-08-24 实测）。
 */
class QuotaTest {

    private val REAL = """
You are currently using your subscription to power your Claude Code usage

Current session: 12% used · resets Aug 24, 3:10pm (UTC)
Current week (all models): 11% used · resets Aug 30, 12:59pm (UTC)
Current week (Fable): 0% used

What's contributing to your limits usage?
Last 24h · 1492 requests · 11 sessions
  97% of your usage was at >150k context
""".trimIndent()

    @Test fun 解出两档额度和重置时间() {
        val q = Quota.parse(REAL)!!
        assertEquals(12, q.sessionPct)
        assertEquals("Aug 24, 3:10pm (UTC)", q.sessionResets)
        assertEquals(11, q.weekPct)
        assertEquals("Aug 30, 12:59pm (UTC)", q.weekResets)
    }

    @Test fun 不能把Fable分项当成周用量() {
        // ⚠️ 输出里「Current week (Fable): 0% used」也带 % used。
        // 锚点是「Current week (all models)」这行文字，不是「第几个 %」。
        assertEquals("周用量必须是 all models 那个 11%，不是 Fable 的 0%", 11, Quota.parse(REAL)!!.weekPct)
    }

    @Test fun 不能把正文里的百分比读进来() {
        // 「97% of your usage was at >150k context」是说明文字，不是额度
        val q = Quota.parse(REAL)!!
        assertEquals(12, q.sessionPct); assertEquals(11, q.weekPct)
    }

    @Test fun 读得出订阅档位() {
        // 真 credentials + /usage 拼在一起的样子（fetch 就是这么组合的）
        val real = """
"subscriptionType":"max"
"rateLimitTier":"default_claude_max_20x"
Current session: 16% used · resets Aug 24, 3:10pm (UTC)
Current week (all models): 12% used · resets Aug 30, 12:59pm (UTC)
""".trimIndent()
        assertEquals("Max 20x", Quota.parse(real)!!.plan)
    }

    @Test fun 档位各档都认() {
        assertEquals("Max 5x", Quota.planOf("\"rateLimitTier\":\"default_claude_max_5x\""))
        assertEquals("Max 20x", Quota.planOf("\"rateLimitTier\":\"default_claude_max_20x\""))
        assertEquals("Pro", Quota.planOf("\"rateLimitTier\":\"claude_pro\""))
        // 只有 subscriptionType 时兜底
        assertEquals("Max", Quota.planOf("\"subscriptionType\":\"max\""))
        assertEquals("", Quota.planOf("没有相关字段"))
    }

    @Test fun 没有额度信息就返回空() {
        assertNull(Quota.parse("bash: claude: command not found"))
        assertNull(Quota.parse(""))
    }

    @Test fun 缺了周那行也不给半个结果() {
        assertNull(Quota.parse("Current session: 12% used · resets Aug 24, 3:10pm (UTC)"))
    }

    @Test fun 没有重置时间也认() {
        // 有的输出可能只有百分比没有 resets —— 百分比是主，时间可空
        val q = Quota.parse("Current session: 5% used\nCurrent week (all models): 8% used")!!
        assertEquals(5, q.sessionPct); assertEquals("", q.sessionResets)
        assertEquals(8, q.weekPct)
    }
}
