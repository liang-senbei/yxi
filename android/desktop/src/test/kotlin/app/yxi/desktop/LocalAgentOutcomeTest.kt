package app.yxi.desktop

import kotlin.test.*

class LocalAgentOutcomeTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val start = """{"type":"thread.started","thread_id":"$id"}"""
    @Test fun `clean exit without terminal receipt stays unknown`() {
        assertEquals("结果未确认", LocalAgentOutcome.parse("codex", sequenceOf(start), 0, null).status)
        assertEquals("结果未确认", LocalAgentOutcome.parse("codex", sequenceOf("{}"), 0, null).status)
    }
    @Test fun `native failure overrides zero exit code`() {
        val failed = LocalAgentOutcome.parse("codex", sequenceOf(start,
            """{"type":"turn.failed","error":{"message":"provider unavailable"}}"""), 0, null)
        assertEquals("运行器报告失败", failed.status)
        assertEquals("provider unavailable", failed.detail)
        val claude = LocalAgentOutcome.parse("claude", sequenceOf(
            """{"type":"result","session_id":"$id","subtype":"error_during_execution","is_error":true,"errors":["approval required"]}"""), 0, null)
        assertEquals("运行器报告失败", claude.status)
        assertEquals("approval required", claude.detail)
    }
    @Test fun `success after recoverable error is accepted but wrong session is not`() {
        val stream = listOf(start, """{"type":"error","message":"retrying"}""", """{"type":"turn.completed","usage":{}}""")
        assertEquals("已完成", LocalAgentOutcome.parse("codex", stream.asSequence(), 0, id).status)
        assertEquals("结果未确认", LocalAgentOutcome.parse("codex", stream.asSequence(), 0, "87654321-1234-1234-1234-123456789abc").status)
        assertEquals("运行失败 (1)", LocalAgentOutcome.parse("codex", stream.asSequence(), 1, id).status)
    }
    @Test fun `conflicting identities and failure after success are not completed`() {
        val stream = sequenceOf(start, """{"type":"turn.completed"}""", """{"type":"turn.failed","error":{"message":"late failure"}}""")
        assertEquals("运行器报告失败", LocalAgentOutcome.parse("codex", stream, 0, id).status)
        val conflict = sequenceOf(start, """{"type":"thread.started","thread_id":"87654321-1234-1234-1234-123456789abc"}""", """{"type":"turn.completed"}""")
        assertEquals("结果未确认", LocalAgentOutcome.parse("codex", conflict, 0, null).status)
    }
}
