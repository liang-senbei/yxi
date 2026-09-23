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
        RunnerCatalog.entries.filterNot { it.serverCreation }.forEach { runner ->
            val rejected = assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/work", runner.id, id) }
            assertTrue(rejected.message.orEmpty().contains("尚未完成"))
        }
        assertFailsWith<IllegalArgumentException> { DesktopLaunchPlan("/work", "codex", "bad;id") }
    }
    @Test fun `discovery does not accept arbitrary executable strings`() {
        assertFailsWith<IllegalArgumentException> { RunnerCatalog.probeCommand("claude; touch bad") }
        assertTrue(RunnerCatalog.probeCommand("grok").contains("command -v"))
        assertTrue(RunnerCatalog.probeCommand("opencode").contains("command -v"))
    }
}
