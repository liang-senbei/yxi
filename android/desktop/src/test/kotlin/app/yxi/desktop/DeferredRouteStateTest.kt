package app.yxi.desktop

import app.yxi.agent.Lines
import org.json.JSONObject
import kotlin.test.*

/** 66c3f5b 退出保护 × 0cb6030 延后切换：AppState 层可无头验证的部分（基线 d7e15f6）。 */
class DeferredRouteStateTest {
    private fun conn(label: String) = Conn(Host("h-$label", label, "127.0.0.1"), FileHostKeys())
    private fun codexLine(name: String = "中转A") = Lines.Line(
        id = "l1", name = name, baseUrl = "https://relay.invalid/v1", apiKey = "sk-x", agent = Lines.CODEX,
        extra = JSONObject().put("model", "gpt-5").put("model_reasoning_effort", "high"),
    )

    /** A5：id 唯一（LaunchedEffect 的 key）、status 初值明确。 */
    @Test fun `requests get unique ids and start in waiting status`() {
        val a = DeferredRoute(conn("A"), codexLine(), null, emptyList())
        val b = DeferredRoute(conn("B"), codexLine(), null, emptyList())
        assertNotEquals(a.id, b.id)
        assertEquals("等待目标范围内的任务空闲", a.status)
        assertFalse(a.applying)
    }

    /** A4：构造时深拷贝 line/catalog 的 extra —— 之后外部改动原 JSONObject 不得影响请求（清单比对不许被突变骗过）。 */
    @Test fun `request snapshots line and catalog against later mutation`() {
        val original = codexLine()
        val catalogOriginal = listOf(codexLine("另一条"))
        val request = DeferredRoute(conn("A"), original, null, catalogOriginal)
        original.extra.put("model", "MUTATED")
        catalogOriginal[0].extra.put("model_reasoning_effort", "MUTATED")
        assertEquals("gpt-5", request.line.extra.optString("model"))
        assertEquals("high", request.catalog[0].extra.optString("model_reasoning_effort"))
        assertEquals("中转A", request.line.name)
    }

    /** A3：无延后请求时退出保护与基线一致（waitingRoutes 空、无操作计数）。 */
    @Test fun `without a deferred request exit review matches a plain workspace`() {
        val state = AppState()
        val work = state.pendingWork()
        assertTrue(work.waitingRoutes.isEmpty())
        assertEquals(0, work.operations)
        assertFalse(work.needsReview)
        assertTrue(work.canDiscard)
    }

    /** A1：等待中（非写入）→ 退出框列出「等待切换线路」，可丢弃（operations 不计）。 */
    @Test fun `waiting request surfaces in exit review but stays discardable`() {
        val state = AppState()
        val c = conn("测试主机")
        state.conns.add(c)
        state.deferredRoute = DeferredRoute(c, codexLine(), null, emptyList())
        val work = state.pendingWork()
        assertEquals(listOf("测试主机 · 中转A"), work.waitingRoutes)
        assertTrue(work.needsReview)
        assertEquals(0, work.operations)
        assertTrue(work.canDiscard)
    }

    /** A2：写入中（applying）→ 归入 operations（不可丢弃），不再列 waitingRoutes。 */
    @Test fun `applying request counts as an operation and blocks discard`() {
        val state = AppState()
        val c = conn("测试主机")
        state.conns.add(c)
        val request = DeferredRoute(c, codexLine(), null, emptyList())
        state.deferredRoute = request
        request.applying = true
        val work = state.pendingWork()
        assertTrue(work.waitingRoutes.isEmpty())
        assertEquals(1, work.operations)
        assertTrue(work.needsReview)
        assertFalse(work.canDiscard)
    }
}
