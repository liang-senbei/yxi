package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

/**
 * 桌面通知（托盘气泡）。窗口壳在 Main.kt 里把 [tray] 和 [focused] 接上；对话 / 侧栏只管调 [notify]。
 * 三档（Codex 原样）：always / unfocused / never，存 Store.pref("notify")。
 */
object Notify {
    var tray: TrayState? = null
    var focused by mutableStateOf(true)      // 窗口有没有焦点（unfocused 档只在失焦时弹）

    fun notify(title: String, text: String) {
        when (Store.pref("notify", "unfocused")) {
            "never" -> return
            "unfocused" -> if (focused) return
        }
        tray?.sendNotification(Notification(title, text, Notification.Type.Info))
    }
}
