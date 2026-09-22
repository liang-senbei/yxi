package app.yxi.agent

import kotlin.test.*

class SessionStatusIncarnationTest {
    private fun snapshot(cc: String = "", hook: String = "", runtime: String = "100:\$1:20") = SessionProbe.parseSnapshot("""
        __YXI_SNAPSHOT_V1__	tmux_begin
        cc-demo|1|30|0|/project|bash|$runtime
        __YXI_SNAPSHOT_V1__	tmux_end
        __YXI_SNAPSHOT_V1__	cc_begin
        $cc
        __YXI_SNAPSHOT_V1__	cc_end
        __YXI_SNAPSHOT_V1__	status_begin
        $hook
        __YXI_SNAPSHOT_V1__	status_end
        __YXI_SNAPSHOT_V1__	ev_begin
        {"session":"cc-demo","preview":"old work"}
        __YXI_SNAPSHOT_V1__	ev_end
    """.trimIndent()).sessions.single()

    @Test fun `recreated windows discard old busy and waiting records`() {
        for (state in listOf("work", "input")) {
            val session = snapshot(hook = """{"session":"cc-demo","state":"$state","detail":"old","ts":10}""")
            assertEquals(SessionState.Idle, session.state)
            assertEquals("", session.detail)
            assertEquals(0.0, session.stateTs)
        }
    }
    @Test fun `fresh native idle wins over hook and stale native does not hide fresh hook`() {
        val hook = """{"session":"cc-demo","state":"work","ts":30}"""
        assertEquals(SessionState.Idle, snapshot("""{"tmux":"cc-demo:@0.%0","status":"idle","statusUpdatedAt":25000}""", hook).state)
        assertEquals(SessionState.Working, snapshot("""{"tmux":"cc-demo:@0.%0","status":"waiting","statusUpdatedAt":10000}""", hook).state)
    }
    @Test fun `legacy snapshots without runtime identity retain existing status behavior`() {
        assertEquals(SessionState.NeedsYou, snapshot(hook = """{"session":"cc-demo","state":"input","ts":10}""", runtime = "").state)
    }
}
