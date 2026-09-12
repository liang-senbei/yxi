package app.yxi.desktop

import kotlin.test.*

class DesktopLauncherTest {
    @Test fun `new requests are distinct but retries keep identity`() {
        val a = DesktopLaunchPlan("/work/app", "claude", DesktopLaunchPlan.newRequestId())
        val b = DesktopLaunchPlan("/work/app", "claude", DesktopLaunchPlan.newRequestId())
        assertNotEquals(a.sessionName, b.sessionName)
        assertEquals(a.sessionName, a.copy().sessionName)
        assertTrue(a.sessionName.startsWith("cc-app-"))
        assertTrue(a.copy(agent = "codex").sessionName.startsWith("cx-app-"))
    }
    @Test fun `invalid launch input is rejected before any remote operation`() {
        val id = DesktopLaunchPlan.newRequestId()
        listOf("relative/path", "~/project", "/work\ncommand", "/work\u0000path").forEach {
            assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan(it, "claude", id) }
        }
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/work", "sh", id) }
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/work", "codex", "bad;id") }
    }
}
