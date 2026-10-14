package app.yxi.desktop

import app.yxi.agent.SessionState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** P1e 本机侧栏：行菜单、整理过滤和置顶排序、记录范围、原生历史去重。只用临时目录里的整理记录，不构造 AppState。 */
class LocalSidebarListTest {
    @TempDir lateinit var directory: File
    private val nav by lazy { WorkspaceNavigation(File(directory, "workspace.json")) }

    private fun record(id: String, at: Long, engine: String = "codex", home: String = "C:\\home-a", title: String = "对话 $id",
        user: String = "me", os: String = "Windows 11") =
        LocalCodexTaskRecord(id, user, os, home, "C:\\work\\demo", title, "default", at, engine)
    private fun items(entries: List<MenuEntry>) = entries.map { assertIs<MenuEntry.Item>(it) }
    private fun labels(entries: List<MenuEntry>) = items(entries).map { it.label }
    private fun thread(id: String) = NativeHistoryThread(id, "历史 $id", "C:\\work\\demo", "openai", "cli", 0L, "")

    @Test fun `idle menu lists five entries and each one flips its flag`() {
        val r = record("t1", 1)
        var renamed = 0
        val menu = items(localTaskMenu(nav, r, SessionState.Idle) { renamed++ })
        assertEquals(listOf("置顶", "收藏", "修改显示名称", "静音此任务", "归档"), menu.map { it.label })
        assertTrue(menu.all { it.enabled })
        menu.forEach { it.action() }
        assertEquals(1, renamed)
        assertTrue(nav.pinned(r.key)); assertTrue(nav.favorite(r.key)); assertTrue(nav.muted(r.key)); assertTrue(nav.archived(r.key))
        assertEquals(listOf("取消置顶", "取消收藏", "修改显示名称", "恢复任务通知", "恢复到项目列表"),
            labels(localTaskMenu(nav, r, SessionState.Idle) {}))
        assertEquals("", nav.error)
    }

    @Test fun `busy tasks cannot be archived but archived ones can always be restored`() {
        val r = record("t1", 1)
        listOf(SessionState.Working, SessionState.NeedsYou).forEach { status ->
            val last = items(localTaskMenu(nav, r, status) {}).last()
            assertEquals("运行中或待处理任务不能归档", last.label)
            assertFalse(last.enabled)
        }
        nav.setArchived(r.key, true)
        val restore = items(localTaskMenu(nav, r, SessionState.Working) {}).last()
        assertEquals("恢复到项目列表", restore.label)
        assertTrue(restore.enabled)
        restore.action()
        assertFalse(nav.archived(r.key))
    }

    @Test fun `sidebar list filters by mode and query and puts pinned tasks first`() {
        val a = record("a", 3, engine = "claude", title = "修复登录页")
        val b = record("b", 2, engine = "opencode", title = "整理导航")
        val c = record("c", 1)
        val records = listOf(a, b, c)
        val status = mapOf(a.key to SessionState.Idle, b.key to SessionState.NeedsYou, c.key to SessionState.Working)
        fun list(query: String = "") = localSidebarTasks(nav, records, query) { status.getValue(it.key) }.map { it.threadId }
        assertEquals(listOf("a", "b", "c"), list())
        nav.togglePin(c.key); nav.togglePin(b.key)
        assertEquals(listOf("c", "b", "a"), list())
        nav.rename(a.key, "登录页报错")
        assertEquals(listOf("a"), list("报错"))
        assertEquals(listOf("a"), list("修复"))
        assertEquals(listOf("b"), list("opencode"))
        assertEquals(listOf("c"), list("CODEX"))
        nav.setArchived(a.key, true); nav.setArchived(b.key, true)
        assertEquals(listOf("c", "b"), list())
        nav.setMode("待处理")
        assertEquals(listOf("b"), list())
        nav.setMode("归档")
        assertEquals(listOf("b", "a"), list())
        assertEquals("", nav.error)
    }

    @Test fun `owned records keep every codex data directory but only this user and system`() {
        val records = listOf(record("old", 1), record("other-home", 3, home = "C:\\home-b"), record("claude", 2, engine = "claude"),
            record("someone", 4, user = "other"), record("mac", 5, os = "Mac OS X"))
        assertEquals(listOf("other-home", "claude", "old"), localOwnedRecords(records, "me", "Windows 11").map { it.threadId })
    }

    @Test fun `native history drops threads already listed from the same codex data directory`() {
        val threads = listOf("t1", "t2", "t3", "t4").map { thread(it) }
        val owned = listOf(record("t1", 1), record("t2", 2, home = "C:\\home-b"), record("t3", 3, engine = "claude"))
        assertEquals(listOf("t2", "t3", "t4"), nativeWithoutOwned(threads, owned, "C:\\home-a").map { it.id })
        assertSame(threads, nativeWithoutOwned(threads, emptyList(), "C:\\home-a"))
        assertSame(threads, nativeWithoutOwned(threads, owned, null))
    }
}
