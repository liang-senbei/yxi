package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

/**
 * 桌面通知（托盘气泡）。窗口壳在 Main.kt 里把 [tray] 和 [focused] 接上；对话 / 侧栏只管调 [notify]。
 * 三档（Codex 原样）：always / unfocused / never，存 Store.pref("notify")。
 *
 * 措辞照 ZCode（老板 09-12 截图）：完成 = 「任务已完成 / 任务: <会话名>」；等批准 / 等输入 = 徽标文案 + 任务行。
 * 点气泡 = 跳到那个会话（[open] 由 Main 接线；Shell 的 TrayIcon ActionListener 调 [clicked]）。
 */
object Notify {
    var tray: TrayState? = null
    var focused by mutableStateOf(true)      // 窗口有没有焦点（unfocused 档只在失焦时弹）

    /** 点通知跳会话：Main 接线（按 hostId+会话名 select，再把窗口拉回前台）。 */
    var open: ((hostId: String, session: String) -> Unit)? = null
    var openTask: ((String) -> Unit)? = null
    var taskAllowed: ((String) -> Boolean)? = null
    private var click: (() -> Unit)? = null   // 最新一条【真的弹出去过】的通知的跳转目标
    private var clickAt = 0L                  // AWT 区分不了「点气泡」和「双击托盘图标」，靠时效门槛防过期劫持

    fun notify(title: String, text: String, hostId: String? = null, session: String? = null, taskKey: String? = null) {
        when (Store.pref("notify", "unfocused")) {
            "never" -> return
            "unfocused" -> if (focused) return
        }
        if (taskKey != null && taskAllowed?.invoke(taskKey) == false) return
        val targetTray = tray ?: return
        // ⚠️ 先过三档守卫再武装 click：被拦掉的通知没有气泡可点，不能留下跳转目标
        click = if (taskKey != null) { { openTask?.invoke(taskKey) } }
            else if (hostId != null && session != null) { { open?.invoke(hostId, session) } } else null
        clickAt = System.currentTimeMillis()
        targetTray.sendNotification(Notification(title, text, Notification.Type.Info))
    }

    /** 气泡被点（AWT ActionListener 在 EDT 上）：叫回窗口 + 10 秒内才跳对应会话（过期/双击托盘只叫窗口）。 */
    fun clicked() {
        runCatching { Shell.show() }
        val c = click
        if (c != null && System.currentTimeMillis() - clickAt < 10_000) runCatching { c() }
    }
}
