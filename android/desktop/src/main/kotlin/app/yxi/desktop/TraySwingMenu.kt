package app.yxi.desktop

import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/** 托盘菜单的一项：[label] 为空是分隔线。 */
internal class TrayEntry(val label: String?, val action: () -> Unit = {})

/**
 * Windows 托盘的右键菜单用 Swing 画（JPopupMenu），不用 AWT 的原生 PopupMenu。
 * 原生菜单在 Windows 上走 AWT 自己的字体和代码页映射，跟界面（Compose / Java2D）不是一条路：1.4.16 里已经给它换了中文字体（94f6ab63），
 * 用户 10-15 仍反馈「后台右击退出这些字是乱码」。Swing 菜单和窗口里的字一样由 Java2D 画、按字体回退找字形，中文不会变成乱码。
 * 字体仍用 [TrayMenuFont.select] 挑的系统菜单字号 + 中文字体。Mac 上仍用原生菜单（菜单栏图标，系统 NSMenu 本来就支持中文）。
 */
internal fun trayPopup(entries: List<TrayEntry>): JPopupMenu {
    val font = TrayMenuFont.select(entries.mapNotNull { it.label }.joinToString(""))
    return JPopupMenu().apply {
        this.font = font
        for (entry in entries) {
            if (entry.label == null) addSeparator()
            else add(JMenuItem(entry.label).apply { this.font = font; addActionListener { entry.action() } })
        }
    }
}

/**
 * 菜单左上角放哪（纯函数，单测用）：从鼠标处往右下开；右边、下边放不下就往左、往上翻；始终留在 [area]（屏幕去掉任务栏的可用区）里，
 * 所以任务栏在底部时菜单在它上面、不压住任务栏。
 */
internal fun trayMenuOrigin(pointer: java.awt.Point, area: java.awt.Rectangle, size: java.awt.Dimension): java.awt.Point {
    val x = if (pointer.x + size.width > area.x + area.width) pointer.x - size.width else pointer.x
    val y = if (pointer.y + size.height > area.y + area.height) pointer.y - size.height else pointer.y
    return java.awt.Point(x.coerceIn(area.x, maxOf(area.x, area.x + area.width - size.width)), y.coerceIn(area.y, maxOf(area.y, area.y + area.height - size.height)))
}

/**
 * 把 [trayPopup] 挂到托盘图标上：右键松开时在鼠标处弹出。JPopupMenu 要有个能拿焦点的宿主窗口才会在点别处时自己收起，
 * 所以先在菜单的位置（[trayMenuOrigin]）显示一个 1×1、无边框、置顶的小窗当宿主，菜单收起时把它藏掉。
 */
internal fun attachTrayPopup(icon: java.awt.TrayIcon, entries: List<TrayEntry>): () -> Unit {
    val menu = trayPopup(entries)
    val host = JDialog().apply {
        isUndecorated = true; type = Window.Type.UTILITY; isAlwaysOnTop = true; setSize(1, 1)
        addWindowFocusListener(object : WindowAdapter() { override fun windowLostFocus(e: WindowEvent) { menu.isVisible = false; isVisible = false } })
    }
    menu.addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { host.isVisible = false }
        override fun popupMenuCanceled(e: PopupMenuEvent) { host.isVisible = false }
    })
    val mouse = object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
            if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
            // ⚠️ Windows 托盘事件里的坐标是物理像素（awt_TrayIcon.cpp 直接用 GetCursorPos），缩放 125% / 150% 时和窗口的坐标对不上，
            //    菜单会跑到别处甚至别的显示器上（交叉审查 10-15 指出）。MouseInfo 给的是缩放后的坐标，和 setLocation 同一套
            val pointer = runCatching { java.awt.MouseInfo.getPointerInfo().location }.getOrNull() ?: java.awt.Point(e.xOnScreen, e.yOnScreen)
            val gc = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
                .firstOrNull { it.bounds.contains(pointer) } }.getOrNull() ?: host.graphicsConfiguration
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val b = gc.bounds
            val area = java.awt.Rectangle(b.x + insets.left, b.y + insets.top, b.width - insets.left - insets.right, b.height - insets.top - insets.bottom)
            val at = trayMenuOrigin(pointer, area, menu.preferredSize)
            host.setLocation(at.x, at.y)
            host.isVisible = true
            host.toFront()
            menu.show(host, 0, 0)
        }
    }
    icon.addMouseListener(mouse)
    return { icon.removeMouseListener(mouse); menu.isVisible = false; host.dispose() }
}
