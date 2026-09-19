package app.yxi.desktop

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 纯进程内缓存检查：lease 撤销、append 原子提交、重取仅增量。零 IO、零 GUI、零长时夹具。 */
class DesktopTranscriptMemoryTest {
    private fun userLine(text: String) = JSONObject()
        .put("type", "user")
        .put("message", JSONObject().put("role", "user").put("content", text))
        .toString()

    @Test fun `claim revokes the previous reader lease`() {
        val entry = DesktopTranscriptMemory.Entry("/srv/x.jsonl", 0L)
        val stale = entry.claim().first
        val current = entry.claim().first
        // 旧读者已被撤销：append 回 null，视图不得改写
        assertNull(entry.append(stale, listOf(userLine("旧读者")), 10L))
        assertEquals(0L, entry.view.offset)
        // 新读者可提交
        val view = entry.append(current, listOf(userLine("新读者")), 12L)
        assertNotNull(view)
        assertEquals(12L, view.offset)
        // 旧 lease 持续失效
        assertNull(entry.append(stale, listOf(userLine("旧读者再试")), 5L))
        assertEquals(12L, entry.view.offset)
    }

    @Test fun `append commits parsed items and offset together`() {
        val entry = DesktopTranscriptMemory.Entry("/srv/x.jsonl", 100L)
        assertEquals(100L, entry.view.offset)
        assertEquals(emptyList(), entry.view.items)
        val view = entry.append(entry.claim().first, listOf(userLine("甲"), userLine("乙")), 7L)!!
        // 单个视图对象同时携带解析结果与累加后的偏移：一次整体换入，无半提交态
        assertEquals(107L, view.offset)
        assertEquals(2, view.items.size)
        assertSame(entry.view, view)
    }

    @Test fun `reclaiming the cache continues incrementally without replay`() {
        val entry = DesktopTranscriptMemory.Entry("/srv/x.jsonl", 0L)
        val first = entry.claim().first
        entry.append(first, listOf(userLine("一")), 10L)
        entry.release(first)
        // 新读者重取：拿到累计视图与最新偏移
        val (second, warm) = entry.claim()
        assertEquals(10L, warm.offset)
        assertEquals(1, warm.items.size)
        // 继续追加只累计本批：偏移 10+5，不重放旧内容，条目不重复
        val next = entry.append(second, listOf(userLine("二")), 5L)!!
        assertEquals(15L, next.offset)
        assertEquals(2, next.items.size)
    }
}
