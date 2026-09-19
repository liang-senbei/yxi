package app.yxi.desktop

import app.yxi.agent.Session
import app.yxi.agent.SessionState
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorkspaceNavigationTest {
    private val host = Host("host", "HK", "hk.example")
    private fun session(path: String, id: String = "10:\$1:123", name: String = "cc-work") = Session(name, 1, false, path, 1, SessionState.Idle, "", 0.0, runtimeId = id)
    private fun fixture(block: (WorkspaceNavigation, File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-navigation").toFile()
        try { val f = File(dir, "workspace.json"); block(WorkspaceNavigation(f), f) } finally { dir.deleteRecursively() }
    }
    @Test fun `same folder basename remains two distinct projects`() {
        val groups = projectSections(listOf(session("/prod/yxi"), session("/dev/yxi")))
        assertEquals(2, groups.size); assertEquals(setOf("/prod/yxi", "/dev/yxi"), groups.map { it.path }.toSet())
        assertNotEquals(projectKey(host, "/prod/yxi"), projectKey(host, "/dev/yxi"))
        assertEquals(projectKey(host, "/prod/./yxi/"), projectKey(host, "/prod/yxi"))
        assertNotEquals(projectKey(host, "/prod/yxi"), projectKey(host.copy(id = "other"), "/prod/yxi"))
    }
    @Test fun `new same named process cannot inherit archive while rename preserves identity`() = fixture { nav, _ ->
        val first = session("/work")
        val key = taskNavigationKey(host, first)
        nav.setArchived(key, true)
        assertTrue(nav.archived(taskNavigationKey(host, first.copy(name = "renamed"))))
        assertFalse(nav.archived(taskNavigationKey(host, first.copy(runtimeId = "10:\$2:124"))))
        assertFalse(nav.archived(taskNavigationKey(host.copy(username = "another"), first)))
    }
    @Test fun `pins names and archive survive restart with scoped ordering`() = fixture { nav, f ->
        nav.togglePin("a"); nav.togglePin("hidden-host"); nav.togglePin("b")
        nav.movePin("b", -1, listOf("a", "b"))
        nav.rename("b", "Review Windows UI"); nav.setArchived("a", true)
        val restored = WorkspaceNavigation(f)
        assertTrue(restored.pinOrder("b") < restored.pinOrder("a"))
        assertEquals(2, restored.pinOrder("hidden-host"))
        assertEquals("Review Windows UI", restored.title("b")); assertTrue(restored.archived("a"))
    }
    @Test fun `archived task needing attention remains discoverable`() = fixture { nav, _ ->
        nav.setArchived("a", true)
        assertFalse(nav.visible("a", SessionState.Done))
        assertTrue(nav.visible("a", SessionState.NeedsYou))
        nav.setMode("归档"); assertTrue(nav.visible("a", SessionState.Done))
        nav.setArchived("a", false); assertFalse(nav.visible("a", SessionState.Done))
    }
    @Test fun `corrupted metadata cannot be overwritten by an empty state`() = fixture { _, f ->
        f.writeText("broken")
        val nav = WorkspaceNavigation(f)
        nav.togglePin("a")
        assertTrue(nav.error.isNotEmpty()); assertFalse(nav.pinned("a")); assertEquals("broken", f.readText())
    }
    @Test fun `notification filter pins fallback and single task mute`() = fixture { nav, _ ->
        // 无置顶回退：勾选只置顶但当前无任何置顶时，全部放行
        assertTrue(nav.shouldNotify("a", true)); assertTrue(nav.shouldNotify("b", true))
        // 只置顶：出现置顶后仅置顶任务放行；未勾选时不过滤
        nav.togglePin("b")
        assertFalse(nav.shouldNotify("a", true)); assertTrue(nav.shouldNotify("b", true))
        assertTrue(nav.shouldNotify("a", false)); assertTrue(nav.shouldNotify("b", false))
        // 单任务静音：无论置顶与开关一律静音，取消后恢复
        nav.setMuted("b", true)
        assertFalse(nav.shouldNotify("b", true)); assertFalse(nav.shouldNotify("b", false))
        nav.setMuted("b", false)
        assertTrue(nav.shouldNotify("b", true)); assertFalse(nav.shouldNotify("a", true))
    }
}
