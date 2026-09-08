package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.SystemTray
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.system.exitProcess

/** Yxi 桌面版入口：单实例 → 窗口（自绘标题栏、关窗留托盘、全局快捷键、缩放）→ 托盘。壳的实现在 Shell.kt，布局在 App.kt。 */
@OptIn(ExperimentalComposeUiApi::class)   // WindowDecoration（自绘标题栏 + 边缘改大小）在 1.12 还是实验 API
fun main(args: Array<String>) {
    if (!Shell.claimSingleInstance()) exitProcess(0)     // 已经有一个在跑：它会把窗口拉出来，这个直接退
    application {
        // --smoke：开窗口 3 秒就退并打印 smoke ok —— CI / Mac 上证明 Compose + Skia 在那个平台起得来（application 退出时 exitProcess(0)）
        if ("--smoke" in args) LaunchedEffect(Unit) { delay(3000); println("smoke ok"); exitApplication() }
        val state = remember { AppState() }
        val tray = remember { TrayState().also { Notify.tray = it } }
        val scope = rememberCoroutineScope()
        // 记住上次的窗口大小 / 位置（Store.dir/window.json）。只在第一次组合时读：Shell.visible 一变这里会重组，不能每次都重置
        val win = rememberWindowState(width = 1200.dp, height = 800.dp)
        remember { Store.loadWindow(win); if (Store.pref("maximized", "0") == "1") win.placement = WindowPlacement.Maximized }
        fun quit() {
            // 最大化时别把满屏尺寸存成常规尺寸，只记「上次是最大化」，常规尺寸还是上次浮动时那份
            Store.setPref("maximized", if (win.placement == WindowPlacement.Maximized) "1" else "0")
            if (win.placement == WindowPlacement.Floating) Store.saveWindow(win)
            exitApplication()
        }
        fun close() {
            if (Store.pref("closeToTray", "1") != "1" || !SystemTray.isSupported()) return quit()
            Shell.visible = false
            if (Store.pref("trayHint", "0") != "1") { Store.setPref("trayHint", "1"); tray.sendNotification(Notification("Yxi", "Yxi 会在托盘里继续运行，服务器上的会话不受影响")) }
        }
        Window(
            onCloseRequest = ::close, state = win, visible = Shell.visible, title = "Yxi", icon = painterResource("icon.png"),
            decoration = WindowDecoration.Undecorated(resizerThickness = 8.dp),      // 无系统边框；边缘 8dp 仍能拖着改大小
            onPreviewKeyEvent = { shortcut(it, state, scope) },
        ) {
            DisposableEffect(Unit) {
                window.minimumSize = Dimension(720, 560)
                Shell.frame = window
                val focus = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) { Notify.focused = true }
                    override fun windowLostFocus(e: WindowEvent) { Notify.focused = false }
                }
                window.addWindowFocusListener(focus)
                onDispose { window.removeWindowFocusListener(focus); Shell.frame = null }
            }
            Zoomed {
                YxiTheme {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        TitleBar(state, win, ::close)
                        App(state)
                    }
                }
            }
        }
        YxiTray(state, tray, ::quit)
    }
}
