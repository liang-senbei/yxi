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
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.SystemTray
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.system.exitProcess

/** Yxi 桌面版入口：单实例 → 窗口（自绘标题栏、关窗留托盘、全局快捷键、缩放）→ 托盘。壳的实现在 Shell.kt，布局在 App.kt。 */
@OptIn(ExperimentalComposeUiApi::class)   // WindowDecoration（自绘标题栏 + 边缘改大小）在 1.12 还是实验 API
fun main(args: Array<String>) {
    if ("--host-smoke" in args) {
        try { runHostStoreNativeSmoke("--reopen" in args); exitProcess(0) }
        catch (e: Throwable) { e.printStackTrace(); exitProcess(1) }
    }
    if ("--credential-smoke" in args) {
        try { runCredentialNativeSmoke("--reopen" in args); exitProcess(0) }
        catch (e: Throwable) { e.printStackTrace(); exitProcess(1) }
    }
    if ("--browser-smoke" in args) {
        try { runBrowserNativeSmoke(); exitProcess(0) }
        catch (e: Throwable) { e.printStackTrace(); exitProcess(1) }
    }
    Updater.boot(args)   // Velopack 钩子参数（--veloapp-*）直接退；否则 10 秒后 + 每 6 小时查更新。要在单实例之前：钩子别去唤醒已开的实例
    if (!Shell.claimSingleInstance()) exitProcess(0)     // 已经有一个在跑：它会把窗口拉出来，这个直接退
    application {
        // --smoke：开窗口 3 秒就退并打印 smoke ok —— CI / Mac 上证明 Compose + Skia 在那个平台起得来（application 退出时 exitProcess(0)）
        if ("--smoke" in args) LaunchedEffect(Unit) { delay(3000); println("smoke ok"); exitApplication() }
        val state = remember { AppState() }
        DisposableEffect(state) { onDispose { state.codexWorkspace.close() } }
        val tray = remember { TrayState().also { Notify.tray = it } }
        val scope = rememberCoroutineScope()
        // 点通知跳会话（ZCode 同款）：按 hostId + 会话名选中，select 会把页面拉回工作区；叫回窗口归 Notify.clicked
        LaunchedEffect(Unit) {
            Notify.taskAllowed = { key -> state.navigation.shouldNotify(key, Store.pref("notifyOnlyPinned", "0") == "1") }
            Notify.openTask = { key ->
                val terminal = state.conns.firstNotNullOfOrNull { c -> c.sessions.firstOrNull { taskNavigationKey(c.host, it) == key }?.let { c to it } }
                if (terminal != null) state.select(terminal.first, terminal.second)
                else state.conns.firstOrNull { c -> state.codexWorkspace.tasks(c.host).any { it.key == key } }?.let { c ->
                    state.select(c, null)
                    state.codexSelectedTaskKey = key
                    state.page = Page.Codex
                }
            }
            Notify.open = { hostId, name ->
                val c = state.conns.firstOrNull { it.host.id == hostId }
                val s = c?.sessions?.firstOrNull { it.name == name } ?: c?.sessions?.firstOrNull()
                if (c != null) state.select(c, s)
            }
        }
        // 记住上次的窗口大小 / 位置（Store.dir/window.json）。只在第一次组合时读：Shell.visible 一变这里会重组，不能每次都重置
        val win = rememberWindowState(width = 1200.dp, height = 800.dp)
        val quitting = remember { androidx.compose.runtime.mutableStateOf(false) }
        val reviewingExit = remember { androidx.compose.runtime.mutableStateOf(false) }
        remember { Store.loadWindow(win); if (Store.pref("maximized", "0") == "1") win.placement = WindowPlacement.Maximized }
        fun finishQuit() {
            if (quitting.value) return
            quitting.value = true
            state.browsers.values.forEach { it.close() }
            // 最大化时别把满屏尺寸存成常规尺寸，只记「上次是最大化」，常规尺寸还是上次浮动时那份
            Store.setPref("maximized", if (win.placement == WindowPlacement.Maximized) "1" else "0")
            if (win.placement == WindowPlacement.Floating) Store.saveWindow(win)
            scope.launch {
                if (!BrowserRuntime.shutdown()) System.err.println("Browser cleanup did not finish before shutdown")
                exitApplication()
            }
        }
        fun quit() {
            if (quitting.value || reviewingExit.value) return
            if (state.pendingWork().needsReview) {
                if (!Shell.visible || Shell.frame?.isFocused != true) {
                    Shell.show()
                    scope.launch { delay(150); if (!quitting.value) reviewingExit.value = true }
                } else reviewingExit.value = true
            }
            else finishQuit()
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
                // AWT 默认把 Ctrl+Tab / Ctrl+Shift+Tab 当焦点遍历键，在 Compose 的 onPreviewKeyEvent 之前就吃掉了（审查 P1）
                window.setFocusTraversalKeys(java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
                window.setFocusTraversalKeys(java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
                window.minimumSize = Dimension(720, 560)
                // ⚠️ **无边框窗口最大化会盖住任务栏**（审查 P2）。AWT 对 undecorated 的 MAXIMIZED_BOTH
                //    取的是整块屏幕，不是「可用区」—— 表现是最大化之后任务栏被压在窗口下面，
                //    而我们的标题栏是自绘的，用户连「还原」都够不着（系统边框那三颗不存在）。
                //    setMaximizedBounds 明确给出可用区（屏幕矩形减掉 screenInsets）就没这问题。
                // ⚠️ 换显示器要重算：两块屏的分辨率和任务栏位置都可能不同，所以移动时再来一次。
                fun fitMaximized() = runCatching {
                    val gc = window.graphicsConfiguration ?: return@runCatching
                    val b = gc.bounds
                    val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
                    window.maximizedBounds = java.awt.Rectangle(
                        b.x + ins.left, b.y + ins.top,
                        b.width - ins.left - ins.right, b.height - ins.top - ins.bottom,
                    )
                }
                fitMaximized()
                val moved = object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent) = Unit.also { fitMaximized() }
                }
                window.addComponentListener(moved)
                Shell.frame = window
                val focus = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) { Notify.focused = true }
                    override fun windowLostFocus(e: WindowEvent) { Notify.focused = false }
                }
                window.addWindowFocusListener(focus)
                onDispose { window.removeWindowFocusListener(focus); window.removeComponentListener(moved); Shell.frame = null }
            }
            Zoomed {
                YxiTheme {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        TitleBar(state, win, ::close)
                        if ("--smoke" in args) AssistantBody("# Document renderer\n\n| Runtime | Status |\n| --- | --- |\n| Markdown | loaded |")
                        App(state)
                    }
                    if (reviewingExit.value) ExitReviewDialog(state.pendingWork(),
                        onCancel = { reviewingExit.value = false },
                        onDiscard = {
                            if (state.pendingWork().canDiscard) { reviewingExit.value = false; finishQuit() }
                        })
                }
            }
        }
        YxiTray(state, tray, ::quit)
    }
}
