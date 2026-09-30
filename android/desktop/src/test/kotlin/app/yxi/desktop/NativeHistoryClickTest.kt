package app.yxi.desktop

import kotlin.test.*

/** §12-1：看着本地任务时点侧栏原生历史，页面停在旧任务上。修法是先退出任务视图再读历史。 */
class NativeHistoryClickTest {
    private val thread = NativeHistoryThread("thread-1", "旧对话", "/tmp/project", "openai", "appServer", 0, "unknown")

    @Test fun `clicking native history leaves the selected local task`() {
        val state = AppState()
        state.page = Page.Workspace
        state.localSelectedTaskKey = "codex:task-1"
        val opened = mutableListOf<NativeHistoryThread>()
        var keyWhenOpened: String? = "未调用"
        openNativeHistory(state, thread) { opened += it; keyWhenOpened = state.localSelectedTaskKey }
        assertNull(state.localSelectedTaskKey)
        assertNull(keyWhenOpened, "读历史之前就要退出任务视图")
        assertEquals(Page.LocalWorkspace, state.page)
        assertEquals(listOf(thread), opened)
    }

    @Test fun `clicking native history without a selected task just opens it`() {
        val state = AppState()
        val opened = mutableListOf<NativeHistoryThread>()
        openNativeHistory(state, thread) { opened += it }
        assertNull(state.localSelectedTaskKey)
        assertEquals(Page.LocalWorkspace, state.page)
        assertEquals(listOf(thread), opened)
    }
}
