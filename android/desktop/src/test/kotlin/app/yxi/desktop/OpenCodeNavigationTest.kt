package app.yxi.desktop

import app.yxi.agent.SessionState
import java.io.File
import kotlin.test.*

class OpenCodeNavigationTest {
    @Test fun `uncertain remote deliveries remain actionable and saved navigation survives reload`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val state = AppState()
        val record = LocalCodexTaskRecord("session", "root", "remote", "/native", "/project", "Task", "model", 1, "opencode", "provider", "host-a")
        val file = File("/sandbox/tmp/opencode-navigation.json")
        val nav = WorkspaceNavigation(file)
        try {
            nav.rename(record.key, "Renamed")
            nav.setGroup(record.key, "yunxi")
            nav.togglePin(record.key)
            nav.setNativeFavorite(record.key, true)
            nav.setArchived(record.key, true)
            val queued = state.instructions.enqueue(record.key, "Fixture")
            val sending = state.instructions.beginDelivery(queued.id, queued.revision)
            val unknown = state.instructions.markUnknown(sending.id, sending.revision, "disconnected")
            assertEquals(SessionState.NeedsYou, openCodeTaskState(state, record))
            nav.setMode("待处理")
            assertTrue(nav.visible(record.key, openCodeTaskState(state, record)), "Archived unknown deliveries must remain actionable")
            state.instructions.confirmRuntimeAccepted(unknown.id, unknown.revision, "turn", "native receipt")
            assertEquals(SessionState.Working, openCodeTaskState(state, record))
            state.instructions.completeRuntimeTurn(record.key, "turn", RuntimeTurnState.Completed, "native completion")
            assertEquals(SessionState.Idle, openCodeTaskState(state, record))
            assertFalse(nav.visible(record.key, openCodeTaskState(state, record)))
            val restored = WorkspaceNavigation(file)
            assertEquals("Renamed", restored.title(record.key))
            assertEquals("yunxi", restored.group(record.key))
            assertTrue(restored.pinned(record.key) && restored.favorite(record.key) && restored.archived(record.key))
            assertFalse(restored.favorite(record.copy(hostKey = "host-b").key))
        } finally { state.closeLocalFeatures(); state.remoteOpenCodeTasks.close() }
    }
}
