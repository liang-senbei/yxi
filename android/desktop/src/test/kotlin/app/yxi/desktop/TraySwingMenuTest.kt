package app.yxi.desktop

import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraySwingMenuTest {
    @Test fun `swing tray menu keeps order, separators, actions and a font that can draw the chinese labels`() {
        var fired = ""
        val menu = trayPopup(listOf(TrayEntry("显示窗口") { fired = "show" }, TrayEntry("设置…"), TrayEntry(null), TrayEntry("退出 Yxi") { fired = "quit" }))
        val items = menu.components.toList()
        assertEquals(4, items.size)
        assertTrue(items[2] is JPopupMenu.Separator)
        val labels = items.filterIsInstance<JMenuItem>()
        assertEquals(listOf("显示窗口", "设置…", "退出 Yxi"), labels.map { it.text })
        for (item in labels) assertEquals(-1, item.font.canDisplayUpTo(item.text), "「${item.text}」有字画不出来：${item.font.family}")
        labels.last().doClick()
        assertEquals("quit", fired)
    }
}
