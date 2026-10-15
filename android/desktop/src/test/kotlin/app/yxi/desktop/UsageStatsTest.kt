package app.yxi.desktop

import app.yxi.agent.Day
import app.yxi.agent.ModelCost
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageStatsTest {
    private val today = LocalDate.of(2025, 10, 9)   // 周四
    private fun day(date: String, tokens: Long, cost: Double = 1.0, model: String = "opus-4") = Day(date, tokens, cost, listOf(ModelCost(model, tokens, cost)))
    private val days = listOf(
        day("2025-06-01", 500), day("2025-10-01", 100), day("2025-10-02", 200, model = "sonnet-4"), day("2025-10-03", 300),
        day("2025-10-05", 0), day("2025-10-08", 400), day("2025-10-09", 1000), day("2025-10-10", 999),   // 10-10 在「今天」之后，不算
    )

    @Test fun `summary counts tokens, cost, active days, longest streak and top model inside the range`() {
        val all = UsageStats.summary(days, UsageRange.All, today)
        assertEquals(2500, all.tokens); assertEquals(6, all.activeDays); assertEquals(3, all.longestStreak); assertEquals(2500 / 6, all.averageTokens)
        assertEquals("opus-4", all.topModel)
        val week = UsageStats.summary(days, UsageRange.Week, today)   // 10-03 … 10-09
        assertEquals(300 + 400 + 1000, week.tokens); assertEquals(3, week.activeDays); assertEquals(2, week.longestStreak)
        assertEquals(5.0, UsageStats.summary(days, UsageRange.Month, today).costUSD, 1e-9)   // 0 tokens 的那天不算
    }

    @Test fun `models are summed per name and sorted by tokens`() {
        assertEquals(listOf("opus-4" to 2300L, "sonnet-4" to 200L), UsageStats.models(days, UsageRange.All, today))
        assertTrue(UsageStats.models(emptyList(), UsageRange.All, today).isEmpty())
        assertNull(UsageStats.summary(emptyList(), UsageRange.All, today).topModel)
    }

    @Test fun `heatmap is 26 monday-first weeks ending with today's week, future days blank, used days in three levels`() {
        val grid = UsageStats.heatmap(days, today)
        assertEquals(26, grid.size); assertTrue(grid.all { it.size == 7 })
        val last = grid.last()   // 10-06（周一）… 10-12
        assertEquals(listOf(0, 0, 3, 3, null, null, null), last.map { it?.let { l -> if (l > 0) 3 else 0 } })   // 周三 10-08、周四 10-09 有用量
        assertNull(last[4])
        assertEquals(3, last[3])   // 今天 1000 是最多的那档
        assertEquals(0, grid.first()[0])   // 26 周前那周没用量（06-01 在窗口外）
    }
}
