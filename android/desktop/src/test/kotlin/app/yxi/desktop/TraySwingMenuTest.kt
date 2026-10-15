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

    @Test fun `menu opens at the pointer and flips up and left to stay above a bottom taskbar`() {
        val area = java.awt.Rectangle(0, 0, 1536, 824)   // 1920×1080 @125% 去掉底部任务栏
        val size = java.awt.Dimension(160, 120)
        assertEquals(java.awt.Point(100, 100), trayMenuOrigin(java.awt.Point(100, 100), area, size))
        assertEquals(java.awt.Point(1500 - 160, 824 - 120), trayMenuOrigin(java.awt.Point(1500, 850), area, size))   // 托盘在任务栏里：往上翻、不压任务栏
        assertEquals(java.awt.Point(1340, 704), trayMenuOrigin(java.awt.Point(1500, 830), area, size))
    }
}
