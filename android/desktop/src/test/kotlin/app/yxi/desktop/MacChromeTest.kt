package app.yxi.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MacChromeTest {
    private val before = MacChrome.native
    @AfterTest fun restore() { MacChrome.native = before }

    @Test fun `mac native labels use command symbols in system order without plus signs`() {
        MacChrome.native = true
        assertEquals("⌘B", MacChrome.label("Ctrl+B"))
        assertEquals("⇧⌘P", MacChrome.label("Ctrl+Shift+P"))
        assertEquals("⌥⌘A", MacChrome.label("Ctrl+Alt+A"))
        assertEquals("⌘,", MacChrome.label("Ctrl+,"))
        assertEquals("⌘\\ · ⇧⌘F · ⌘`", MacChrome.label("Ctrl+\\ · Ctrl+Shift+F · Ctrl+`"))
        assertEquals("⌘= · ⌘- · ⌘0", MacChrome.label("Ctrl+= · Ctrl+- · Ctrl+0"))
        assertEquals("⌘1 … 9", MacChrome.label("Ctrl+1 … 9"))
        assertEquals("对话视图 · ⌘O", MacChrome.label("对话视图 · Ctrl+O"))
        assertEquals("Enter 发送，⇧↩ 换行；截图直接 ⌘V", MacChrome.label("Enter 发送，Shift+Enter 换行；截图直接 Ctrl+V"))
    }

    @Test fun `ctrl tab stays control on the mac`() {
        MacChrome.native = true
        assertEquals("⌃Tab · ⌃⇧Tab", MacChrome.label("Ctrl+Tab · Ctrl+Shift+Tab"))
    }

    @Test fun `labels are unchanged when not mac native`() {
        MacChrome.native = false
        for (text in listOf("Ctrl+B", "Ctrl+Shift+P", "Ctrl+Tab · Ctrl+Shift+Tab", "Shift+Enter 换行")) assertEquals(text, MacChrome.label(text))
    }
}
