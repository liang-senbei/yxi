package app.yxi.desktop

import app.yxi.agent.Rewind
import java.io.ByteArrayInputStream
import kotlin.test.*

class RewindStructuredOutputTest {
    @Test fun `image events are drained while only final result and exit receipt are retained`() {
        val sid = "11111111-1111-4111-8111-111111111111"
        val event = "{\"type\":\"user\",\"image\":\"" + "x".repeat(100_000) + "\"}\n"
        val result = "{\"type\":\"result\",\"session_id\":\"$sid\",\"result\":\"中文🙂\"}\n"
        val input = ByteArrayInputStream((event + result + Rewind.TAG + ":rc=0\n").toByteArray())
        val output = readStructuredRewindOutput(input)
        assertEquals(0, input.available())
        assertFalse(output.contains("image"))
        assertEquals(Rewind.Outcome.Ok(sid, "中文🙂"), Rewind.parse(output))
    }
    @Test fun `missing result and oversized event cannot look successful`() {
        assertTrue(Rewind.parse(readStructuredRewindOutput(ByteArrayInputStream((Rewind.TAG + ":rc=0\n").toByteArray()))) is Rewind.Outcome.Failed)
        assertFailsWith<IllegalStateException> { readStructuredRewindOutput(ByteArrayInputStream("long event".toByteArray()), lineLimit = 4) }
        val error = readStructuredRewindOutput(ByteArrayInputStream(("No conversation found with session ID: x\n" + Rewind.TAG + ":rc=1\n").toByteArray()))
        assertEquals("no-conversation", (Rewind.parse(error) as Rewind.Outcome.Failed).code)
    }
}
