package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.*
import kotlin.test.*

class ScheduledTasksTest {
    @TempDir lateinit var root: File
    private fun task(next: Long = 1000) = ScheduledTask("test", "Daily", "Inspect", ScheduleTarget("local", "Local"), next, "每天", "Asia/Shanghai")
    @Test fun `claim persists before delivery and crash pauses instead of replaying`() {
            val file = root.resolve("schedules.json")
            val first = ScheduledTasks(file); first.put(task())
            assertNotNull(first.claim(2000))
            assertNull(first.claim(2000))
            val reopened = ScheduledTasks(file)
            assertFalse(reopened.tasks.single().enabled)
            assertEquals("结果未确认", reopened.runs.single().status)
            assertNull(reopened.claim(Long.MAX_VALUE / 2))
            assertFails { reopened.resume("test", 3000) }
            reopened.acknowledge(reopened.runs.single().id)
            reopened.resume("test", 3000)
            assertTrue(reopened.tasks.single().enabled)
    }
    @Test fun `approval progress retains active claim and local session identity across restart`() {
            val file = root.resolve("schedules.json")
            val store = ScheduledTasks(file)
            val target = ScheduleTarget("local-session", "Existing Claude", "claude", task = "local-claude:identity")
            store.put(task().copy(target = target))
            val run = assertNotNull(store.claim(2000)).second
            store.progress(run.id, "等待用户批准")
            assertEquals("执行中", store.runs.single().status)
            assertEquals("等待用户批准", store.runs.single().detail)
            assertNull(store.claim(Long.MAX_VALUE / 2))
            val reopened = ScheduledTasks(file)
            assertEquals(target, reopened.tasks.single().target)
            assertEquals("结果未确认", reopened.runs.single().status)
            assertFalse(reopened.tasks.single().enabled)
    }
    @Test fun `run target snapshot survives editing schedule and legacy runs never infer target`() {
            val file = root.resolve("schedules.json")
            val store = ScheduledTasks(file)
            val firstTarget = ScheduleTarget("local-session", "First", "claude", task = "local-claude:first")
            store.put(task().copy(target = firstTarget))
            val run = assertNotNull(store.claim(2000)).second
            store.finish(run.id, "已完成", "receipt")
            store.put(task().copy(target = firstTarget.copy(task = "local-claude:second")))
            val reopened = ScheduledTasks(file)
            assertEquals("local-claude:second", reopened.tasks.single().target.task)
            assertEquals("local-claude:first", reopened.runs.single().targetTaskKey)
            val legacy = org.json.JSONObject(file.readText())
            legacy.getJSONArray("runs").getJSONObject(0).remove("targetTaskKey")
            val legacyFile = root.resolve("legacy.json").apply { writeText(legacy.toString()) }
            val legacyStore = ScheduledTasks(legacyFile)
            assertEquals("", legacyStore.error)
            assertNull(legacyStore.runs.single().targetTaskKey)
    }
    @Test fun `missed hourly slots coalesce and one-time plan disables before execution`() {
            val store = ScheduledTasks(root.resolve("schedules.json"))
            store.put(task().copy(repeat = "每小时"))
            val run = store.claim(1000 + 25 * 3600000)!!.second
            assertEquals(1000 + 26 * 3600000L, store.tasks.single().next)
            store.finish(run.id, "已完成", "ok")
            assertNull(store.claim(1000 + 25 * 3600000))
            store.put(task().copy(repeat = "一次"))
            assertNotNull(store.claim(2000)); assertFalse(store.tasks.single().enabled)
    }
    @Test fun `daily calendar recurrence preserves wall time across daylight saving`() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDateTime.of(2026, 3, 7, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val next = ScheduledTasks.nextTime(task(start).copy(zone = zone.id), start)
        assertEquals(23 * 3600000L, next - start)
        assertEquals(9, Instant.ofEpochMilli(next).atZone(zone).hour)
    }
    @Test fun `damaged primary disables recovered schedules rather than replaying old work`() {
            val file = root.resolve("schedules.json"); val store = ScheduledTasks(file)
            store.put(task()); store.pause("test"); file.writeText("broken")
            assertNull(ScheduledTasks(file).claim(2000))
            assertNull(ScheduledTasks(file).claim(2000))
    }
}
