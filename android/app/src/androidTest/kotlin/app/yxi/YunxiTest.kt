package app.yxi

import androidx.test.platform.app.InstrumentationRegistry
import app.yxi.yunxi.Memo
import app.yxi.yunxi.Memos
import app.yxi.yunxi.Reminders
import app.yxi.yunxi.Priority
import app.yxi.yunxi.Repeat
import app.yxi.yunxi.Status
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
    private fun memo(at: Long, r: Repeat) = Memo("t", "x", 0, 0, remindAt = at, repeat = r)

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
        fun m(at: Long?, status: Status = Status.TODO, repeat: Repeat = Repeat.NONE, fired: Long? = null) =
            Memo("a", "x", 0, 0, status = status, remindAt = at, repeat = repeat, firedAt = fired)
        assertFalse(m(null).pending(now))                                            // 没时间
        assertTrue(m(now + 5_000).pending(now))                                      // 未来
        assertFalse(m(now + 5_000, status = Status.DONE).pending(now))               // 完成了
        assertTrue(m(now + 5_000, status = Status.BLOCKED).pending(now))             // 受阻的还要提醒
        assertFalse(m(now - 5_000, fired = now - 5_000).pending(now))                // 响过了
        assertTrue(m(now - 5_000, repeat = Repeat.DAILY, fired = now - 5_000).pending(now))  // 重复的永远还会响
    }

    /** 1.1.13 的旧格式：只有 done 布尔，没有 status / priority */
    @Test fun 看板_旧JSON兼容() {
        val old = """[{"id":"1","text":"a","done":true,"createdAt":1,"updatedAt":2},
                      {"id":"2","text":"b","done":false,"createdAt":3,"updatedAt":4,"remindAt":99}]"""
        val l = Memos.parse(old)
        assertEquals(Status.DONE, l[0].status); assertTrue(l[0].done)
        assertEquals(Status.TODO, l[1].status); assertEquals(Priority.MID, l[1].priority)
        assertEquals(99L, l[1].remindAt); assertNull(l[1].dueAt); assertEquals("", l[1].note)
        // 新格式来回一致
        val neu = """[{"id":"3","text":"c","createdAt":1,"updatedAt":2,"status":"BLOCKED","priority":"HIGH","dueAt":7,"note":"n"}]"""
        val n = Memos.parse(neu)[0]
        assertEquals(Status.BLOCKED, n.status); assertEquals(Priority.HIGH, n.priority); assertEquals(7L, n.dueAt); assertEquals("n", n.note)
    }

    @Test fun 看板_排序与统计() {
        Memos.load(ctx)
        val ids = ArrayList<String>()
        try {
            val now = System.currentTimeMillis()
            ids += Memos.add(ctx, "低优先", priority = Priority.LOW)!!.id
            ids += Memos.add(ctx, "高优先·明天到期", priority = Priority.HIGH, dueAt = now + 86_400_000)!!.id
            ids += Memos.add(ctx, "高优先·昨天到期", priority = Priority.HIGH, dueAt = now - 86_400_000)!!.id
            ids += Memos.add(ctx, "已完成", priority = Priority.HIGH)!!.id
            Memos.setStatus(ctx, ids[3], Status.DONE)
            val mine = Memos.list.filter { it.id in ids }
            assertEquals(listOf("高优先·昨天到期", "高优先·明天到期", "低优先", "已完成"), mine.map { it.text })   // 未完成→高优先→截止近→无截止；完成沉底
            assertEquals(listOf("高优先·昨天到期"), Memos.overdue(now).filter { it.id in ids }.map { it.text })
            val c = Memos.counts()
            assertTrue(c.getValue(Status.DONE) >= 1); assertTrue(c.getValue(Status.TODO) >= 3); assertTrue(c.containsKey(Status.BLOCKED))
            Memos.setStatus(ctx, ids[0], Status.DOING)
            assertEquals(Status.DOING, Memos.get(ids[0])!!.status)
            Memos.toggleDone(ctx, ids[3]); assertEquals(Status.TODO, Memos.get(ids[3])!!.status)
        } finally { ids.forEach { Memos.remove(ctx, it) } }
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

    /** 老板 1.1.13 截图：搜「长沙」出来 8 个别省的镇村、没有湖南长沙。要网络。 */
    @Test fun 天气_搜两个字的长沙_湖南长沙在最前() { kotlinx.coroutines.runBlocking {
        val r = WeatherApi.search("长沙")
        assertTrue(r.isNotEmpty())
        assertEquals("湖南", r.first().admin)
        assertTrue(r.first().population > 1_000_000)
        assertTrue(r.zipWithNext().all { (a, b) -> a.population >= b.population })   // 人口倒序
    } }

    // ⚠️ 这里原来有个「定位探针」测试，删了：am instrument 起的 App 没有前台 Activity，算**后台**，
    //    而 ACCESS_COARSE_LOCATION 只在前台有效 —— 系统对后台 App 的 getCurrentLocation 直接回 null、lastKnown 也给空，
    //    探针永远 "no fix"，什么也证明不了。定位只能在前台 App 里点「用当前位置」验，模拟器上还要**同时**循环发
    //    `adb emu geo fix …`（fix 只送给正在监听的客户端，提前发的会丢）。见 Yxi_pilot/design/yunxi-api.md §3。

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
