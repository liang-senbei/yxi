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
 * 把 [trayPopup] 挂到托盘图标上：右键松开时在鼠标处弹出。JPopupMenu 要有个能拿焦点的宿主窗口才会在点别处时自己收起，
 * 所以先在鼠标处显示一个 1×1、无边框、置顶的小窗当宿主，菜单收起时把它藏掉。托盘事件的坐标本来就是屏幕坐标。
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
            host.setLocation(e.xOnScreen, e.yOnScreen)
            host.isVisible = true
            host.toFront()
            menu.show(host, 0, 0)   // 超出屏幕底（任务栏在下面）时 JPopupMenu 自己往上挪
        }
    }
    icon.addMouseListener(mouse)
    return { icon.removeMouseListener(mouse); menu.isVisible = false; host.dispose() }
}
