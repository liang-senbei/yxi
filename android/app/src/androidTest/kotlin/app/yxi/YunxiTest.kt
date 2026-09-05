package app.yxi

import androidx.test.platform.app.InstrumentationRegistry
import app.yxi.yunxi.Memo
import app.yxi.yunxi.Memos
import app.yxi.yunxi.Reminders
import app.yxi.yunxi.Repeat
import app.yxi.yunxi.WeatherApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** 云曦小管家 · 服务层。闹钟的「下一次」按墙上时间推，备忘增删落盘，WMO 代码分类。 */
class YunxiTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.systemDefault()
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private fun memo(at: Long, r: Repeat) = Memo("t", "x", false, 0, 0, at, r)

    @Test fun 下一次_不重复的没有() {
        assertNull(Reminders.nextAfter(memo(1_000L, Repeat.NONE), 2_000L))
    }

    @Test fun 下一次_每天_严格晚于现在且墙上时间不变() {
        val at = ZonedDateTime.of(2026, 9, 1, 8, 30, 0, 0, zone)
        val now = ZonedDateTime.of(2026, 9, 6, 8, 29, 0, 0, zone)      // 差一分钟还没到 → 今天 8:30
        val n1 = Instant.ofEpochMilli(Reminders.nextAfter(memo(ms(at), Repeat.DAILY), ms(now))!!).atZone(zone)
        assertEquals(6, n1.dayOfMonth); assertEquals(8, n1.hour); assertEquals(30, n1.minute)
        val later = ZonedDateTime.of(2026, 9, 6, 8, 30, 0, 0, zone)    // 正好到点 → 要给明天，不能给现在
        val n2 = Instant.ofEpochMilli(Reminders.nextAfter(memo(ms(at), Repeat.DAILY), ms(later))!!).atZone(zone)
        assertEquals(7, n2.dayOfMonth); assertEquals(8, n2.hour)
    }

    @Test fun 下一次_每周_落在同一个星期几() {
        val at = ZonedDateTime.of(2026, 9, 1, 20, 0, 0, 0, zone)       // 周二
        val now = ZonedDateTime.of(2026, 9, 20, 0, 0, 0, 0, zone)
        val n = Instant.ofEpochMilli(Reminders.nextAfter(memo(ms(at), Repeat.WEEKLY), ms(now))!!).atZone(zone)
        assertEquals(at.dayOfWeek, n.dayOfWeek); assertEquals(20, n.hour); assertTrue(n.isAfter(now))
    }

    @Test fun 还会响_的判断() {
        val now = 1_000_000L
        assertFalse(Memo("a", "x", false, 0, 0, null).pending(now))                               // 没时间
        assertTrue(Memo("a", "x", false, 0, 0, now + 5_000).pending(now))                          // 未来
        assertFalse(Memo("a", "x", true, 0, 0, now + 5_000).pending(now))                          // 勾掉了
        assertFalse(Memo("a", "x", false, 0, 0, now - 5_000, firedAt = now - 5_000).pending(now))  // 响过了
        assertTrue(Memo("a", "x", false, 0, 0, now - 5_000, Repeat.DAILY, firedAt = now - 5_000).pending(now))  // 重复的永远还会响
    }

    @Test fun 备忘_增删落盘() {
        Memos.load(ctx)
        val before = Memos.list.size
        val m = Memos.add(ctx, "  买牛奶  ")
        assertNotNull(m); assertEquals("买牛奶", m!!.text)
        assertEquals(before + 1, Memos.list.size)
        assertTrue(java.io.File(ctx.filesDir, "yunxi-memos.json").readText().contains("买牛奶"))
        Memos.toggleDone(ctx, m.id); assertTrue(Memos.get(m.id)!!.done)
        Memos.remove(ctx, m.id)
        assertNull(Memos.get(m.id)); assertEquals(before, Memos.list.size)
        assertFalse(java.io.File(ctx.filesDir, "yunxi-memos.json").readText().contains("买牛奶"))
        assertNull(Memos.add(ctx, "   "))                                                          // 空的不收
    }

    @Test fun 天气_WMO分类() {
        assertEquals(WeatherApi.Kind.CLEAR, WeatherApi.kind(0))
        assertEquals(WeatherApi.Kind.PARTLY, WeatherApi.kind(2))
        assertEquals(WeatherApi.Kind.RAIN, WeatherApi.kind(63))
        assertEquals(WeatherApi.Kind.RAIN, WeatherApi.kind(81))
        assertEquals(WeatherApi.Kind.SNOW, WeatherApi.kind(75))
        assertEquals(WeatherApi.Kind.THUNDER, WeatherApi.kind(96))
        assertEquals(WeatherApi.Kind.FOG, WeatherApi.kind(45))
        assertEquals("天气未知", WeatherApi.describe(12345))
    }
}
