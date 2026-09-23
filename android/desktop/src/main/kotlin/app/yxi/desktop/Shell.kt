package app.yxi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.Frame
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * 窗口壳（design/desktop-reference.md §3、§4「必须有」2/3/7）：自绘标题栏、托盘、单实例、全局快捷键、界面缩放。
 * 托盘 / 第二个实例 / 关窗都要「把窗口拉回来」，所以窗口可见性和 AWT 句柄放这个对象里，Main.kt 只管接线。
 */
object Shell {
    var visible by mutableStateOf(true)      // 关窗 = 隐藏（留托盘），不是退出
    var frame: ComposeWindow? = null         // Window 内容里赋值；show() 要 toFront
    private var lock: FileLock? = null       // 攥着不放：被 GC 掉 channel 就关了，锁跟着丢

    /** 从托盘 / 第二个实例（别的线程）叫窗口回来：显示 + 取消最小化 + 前置。 */
    fun show() = EventQueue.invokeLater {
        visible = true
        frame?.apply {
            isVisible = true; extendedState = extendedState and Frame.ICONIFIED.inv()
            // Windows 不让后台进程抢前台，光 toFront 只会闪任务栏；置顶一下再取消是通行的办法
            isAlwaysOnTop = true; toFront(); requestFocus(); isAlwaysOnTop = false
        }
    }

    /**
     * 单实例：`%LOCALAPPDATA%\Yxi\lock`（非 Windows 用 Store.dir）。首个实例攥住文件锁、起一个回环端口把端口号写进文件，返回 true；
     * 后来的实例拿不到锁，就连那个端口发一行 `show` 让首个把窗口拉出来，返回 false（调用方退出）。进程一死锁自动释放，不怕残留。
     * 锁的是文件末尾之外的一个字节：Windows 的文件锁是强制锁，锁住写端口号的那几个字节别的进程就读不了了。
     */
    fun claimSingleInstance(): Boolean {
        val dir = System.getenv("LOCALAPPDATA")?.takeIf { System.getProperty("os.name").startsWith("Windows") }?.let { File(it, "Yxi") } ?: Store.dir
        val file = File(dir.apply { mkdirs() }, "lock")
        lock = runCatching { RandomAccessFile(file, "rw").channel.tryLock(1L shl 40, 1, false) }.getOrNull()
        // 锁拿不到且真叫醒了谁才算第二个；拿不到又叫不醒（文件系统不支持锁之类）就当自己是首个，别死在这
        if (lock == null && runCatching { Socket(InetAddress.getLoopbackAddress(), file.readText().trim().toInt()).use { it.getOutputStream().write("show\n".toByteArray()) } }.isSuccess) return false
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        file.writeText(server.localPort.toString())
        thread(isDaemon = true, name = "yxi-wake") {
            while (true) runCatching { server.accept().use { if (it.getInputStream().bufferedReader().readLine() == "show") show() } }
        }
        return true
    }
}

/** 标题栏 40dp（Codex 顶栏 36、Claude 58）：整条可拖、双击最大化；左侧栏钮 + 「主机 · 会话」，右三键自绘（悬停 Tokens.hover，关闭悬停红）。 */
@Composable
fun FrameWindowScope.TitleBar(state: AppState, win: WindowState, onClose: () -> Unit) {
    val t = Tokens.current
    val maximized = win.placement == WindowPlacement.Maximized
    val toggleMax = { win.placement = if (win.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized }
    val title = state.conn?.let { c -> c.host.alias.ifBlank { c.host.hostname } + (state.session?.let { " · " + (state.navigation.title(taskNavigationKey(c.host, it)) ?: it.short) } ?: "") } ?: "Yxi"
    WindowDraggableArea(Modifier.fillMaxWidth().height(40.dp).background(t.surface1).pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMax() }) }) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            CaptionButton({ state.sidebarOpen = !state.sidebarOpen }) { sidebarGlyph(it) }
            Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), color = t.textSecondary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CaptionButton({ win.isMinimized = true }) { minimizeGlyph(it) }
            CaptionButton(toggleMax) { if (maximized) restoreGlyph(it) else maximizeGlyph(it) }
            CaptionButton(onClose, danger = true) { closeGlyph(it) }
        }
    }
}

/** 标题栏按钮：46×40（Windows 系统三键的尺寸），无水波纹，悬停换底色；[danger] = 关闭键，悬停红底、图形用页面底色反白。 */
@Composable
private fun CaptionButton(onClick: () -> Unit, danger: Boolean = false, glyph: DrawScope.(Color) -> Unit) {
    val t = Tokens.current
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val bg = if (!hover) Color.Transparent else if (danger) t.danger else t.hover
    val fg = if (hover && danger) t.surface0 else t.textSecondary
    Box(Modifier.width(46.dp).fillMaxHeight().background(bg).hoverable(src).clickable(src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(10.dp)) { glyph(fg) }
    }
}

// 三键的小图形（Windows 标题栏那套：一横 / 方框 / 两个叠方框 / 叉），10dp 见方、1dp 线；方框往里缩半个线宽免得被画布切掉
private fun DrawScope.box(c: Color, l: Float, t: Float, r: Float, b: Float, w: Float) = drawRect(c, Offset(l + w / 2, t + w / 2), Size(r - l - w, b - t - w), style = Stroke(w))
private fun DrawScope.minimizeGlyph(c: Color) { val w = 1.dp.toPx(); drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w) }
private fun DrawScope.maximizeGlyph(c: Color) = box(c, 0f, 0f, size.width, size.height, 1.dp.toPx())
private fun DrawScope.restoreGlyph(c: Color) {
    val w = 1.dp.toPx(); val d = size.width * 0.25f
    box(c, 0f, d, size.width - d, size.height, w)
    // 后面那个只画露出来的 ┐
    drawLine(c, Offset(d, d), Offset(d, w / 2), w); drawLine(c, Offset(d, w / 2), Offset(size.width, w / 2), w)
    drawLine(c, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height - d), w); drawLine(c, Offset(size.width, size.height - d - w / 2), Offset(size.width - d, size.height - d - w / 2), w)
}
private fun DrawScope.closeGlyph(c: Color) { val w = 1.dp.toPx(); drawLine(c, Offset.Zero, Offset(size.width, size.height), w); drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), w) }
private fun DrawScope.sidebarGlyph(c: Color) { val w = 1.dp.toPx(); box(c, 0f, size.height * 0.1f, size.width, size.height * 0.9f, w); drawLine(c, Offset(size.width * 0.35f, size.height * 0.1f), Offset(size.width * 0.35f, size.height * 0.9f), w) }

/**
 * 托盘。AWT 直写而不是 Compose 的 `Tray`：那个只挂 ActionListener，Windows 上要双击才触发，报告 §3.2 要的是左键单击唤回。
 * 通知仍走 [TrayState]（Notify.tray 就是它）：这里收它的 notificationFlow 转成气泡。
 */
@Composable
fun YxiTray(state: AppState, tray: TrayState, onQuit: () -> Unit) {
    if (!SystemTray.isSupported()) return
    val icon = remember { TrayIcon(ImageIO.read(Shell::class.java.getResource("/icon.png")), "Yxi").apply { isImageAutoSize = true } }
    DisposableEffect(Unit) {
        icon.popupMenu = PopupMenu().apply {
            fun item(text: String, act: () -> Unit) = add(MenuItem(text).apply { addActionListener { act() } })
            item("显示窗口") { Shell.show() }
            item("新建会话") { Shell.show(); state.newSessionRequest++ }
            item("设置…") { Shell.show(); state.showSettings = true }
            addSeparator()
            item("退出 Yxi", onQuit)
            TrayMenuFont.apply(this)
        }
        icon.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) Shell.show() } })
        // 点气泡/Toast（Windows 上就是系统通知）= 叫回窗口 + 跳到通知说的那个会话（ZCode 同款，目标在 Notify.clicked 里）
        icon.addActionListener { Notify.clicked() }
        runCatching { SystemTray.getSystemTray().add(icon) }     // 没托盘（Linux 某些桌面）就没托盘，别把整个界面搞崩
        onDispose { SystemTray.getSystemTray().remove(icon) }
    }
    LaunchedEffect(Unit) {
        tray.notificationFlow.collect {
            icon.displayMessage(it.title, it.message, when (it.type) {
                Notification.Type.Info -> TrayIcon.MessageType.INFO
                Notification.Type.Warning -> TrayIcon.MessageType.WARNING
                Notification.Type.Error -> TrayIcon.MessageType.ERROR
                Notification.Type.None -> TrayIcon.MessageType.NONE
            })
        }
    }
}

/** 全局快捷键（Window.onPreviewKeyEvent）：只认 Ctrl 组合和 F5，普通按键放行给输入框。表在 Shortcuts.kt，改这边记得改那边。 */
fun shortcut(e: KeyEvent, state: AppState, scope: CoroutineScope): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.key == Key.F5) { scope.launch { state.conn?.refresh() }; return true }
    if (!e.isCtrlPressed) return false
    val digit = (e.key.keyCode - Key.One.keyCode).toInt()     // 桌面 keyCode = VK_1…VK_9 连号
    when {
        e.key == Key.N -> state.newSessionRequest++
        e.key == Key.K -> state.showTaskSwitcher = true
        e.key == Key.B -> state.sidebarOpen = !state.sidebarOpen
        e.key == Key.J -> state.tab = if (state.tab == 1) 0 else 1
        e.key == Key.Tab -> state.selectByOffset(if (e.isShiftPressed) -1 else 1)
        e.key == Key.A && e.isAltPressed -> state.jumpToAttention()
        e.key == Key.Comma -> state.showSettings = true
        e.key == Key.Slash -> state.showShortcuts = true
        e.key == Key.Equals || e.key == Key.NumPadAdd -> zoom(+1)
        e.key == Key.Minus || e.key == Key.NumPadSubtract -> zoom(-1)
        e.key == Key.Zero || e.key == Key.NumPad0 -> zoom(0)
        digit in 0..8 -> state.selectIndex(digit)
        else -> return false
    }
    return true
}

/** 界面缩放：0.8…1.5 步进 0.1，存 Store.pref("zoom")；[step] 0 = 回 1.0。 */
fun zoom(step: Int) {
    val z = if (step == 0) 1f else (Store.pref("zoom", "1").toFloat() + step * 0.1f).coerceIn(0.8f, 1.5f)
    Store.setPref("zoom", ((z * 10).roundToInt() / 10f).toString())
}

/** 把缩放乘进 LocalDensity，整个窗口内容（含标题栏）一起放大缩小；Store.pref 是 snapshot 状态，改了自动重组。 */
@Composable
fun Zoomed(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val z = Store.pref("zoom", "1").toFloatOrNull() ?: 1f
    CompositionLocalProvider(LocalDensity provides Density(d.density * z, d.fontScale), content = content)
}
