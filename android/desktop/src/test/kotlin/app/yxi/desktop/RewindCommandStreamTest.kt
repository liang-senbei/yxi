package app.yxi.desktop

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RewindCommandStreamTest {
    @Test fun `UTF8 output survives chunk boundaries`() {
        val text = "前".repeat(3000) + "恢复成功🙂"
        assertEquals(text, readRewindOutput(ByteArrayInputStream(text.toByteArray()), 20000))
    }

    @Test fun `oversized output is drained but never reported as a complete result`() {
        val input = ByteArrayInputStream(ByteArray(25000) { 65 })
        assertFailsWith<IllegalStateException> { readRewindOutput(input, 10000) }
        assertEquals(0, input.available())
    }

    @Test fun `exact byte limit accepts the complete result`() {
        val bytes = "完整结果".toByteArray()
        assertEquals("完整结果", readRewindOutput(ByteArrayInputStream(bytes), bytes.size))
    }
}
