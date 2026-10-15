package app.yxi.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/**
 * macOS 的窗口和按键（Mac1 第二轮，PRD §5.7、规格表 §7.1 / §7.2）。
 * [native] 为 true 时：系统边框、内容伸到透明标题栏下面（左上是红绿灯，自绘的最小化 / 最大化 / 关闭不画）、屏幕顶上的菜单栏、⌘ 当主修饰键、
 * 界面上的快捷键写成 ⌘ 的样子。真 App 在 Mac 上默认打开；截图夹具把它关掉（离屏画不出系统标题栏，现有基准也不跟着变），Mac 专属的场景再单独打开。
 */
internal object MacChrome {
    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
    var native by mutableStateOf(isMac)

    /** 红绿灯占的宽度（三颗灯 + 左右留白）：顶部条在这之后才放东西。实测值等 Mac 素材（规格表 §7.3），先按系统默认的位置留。 */
    val trafficLights = 72.dp

    /** 主修饰键：Mac 原生时是 ⌘，其余是 Ctrl。 */
    fun primary(e: KeyEvent): Boolean = if (native) e.isMetaPressed else e.isCtrlPressed

    /** 菜单栏接管的键（Mac 原生时 [shortcut] 不再处理它们，免得菜单项和窗口各触发一次）。 */
    val menuKeys = setOf(Key.N, Key.K, Key.B, Key.J, Key.Comma, Key.Slash, Key.Equals, Key.Minus, Key.Zero, Key.M, Key.W, Key.P, Key.F, Key.Backslash)

    private val combo = Regex("""(?:(?:Ctrl|Shift|Alt)\+)+(?:Enter|Tab|Esc|[A-Za-z0-9,./=\-`\\])""")

    /**
     * 把界面文字里的快捷键（「Ctrl+B」「Ctrl+Shift+P」「Shift+Enter」）换成 Mac 的写法（「⌘B」「⇧⌘P」「⇧↩」：按 ⌃⌥⇧⌘ 的顺序、不加 +）。
     * Ctrl+Tab 例外：Mac 上切会话仍是 ⌃Tab（⌘Tab 是系统切应用）。不是 Mac 原生时原样返回。
     */
    fun label(text: String): String = if (!native) text else combo.replace(text) { m ->
        val parts = m.value.split('+')
        val key = parts.last()
        val mods = parts.dropLast(1).toSet()
        val ctrlStays = key == "Tab"
        buildString {
            if ("Ctrl" in mods && ctrlStays) append('⌃')
            if ("Alt" in mods) append('⌥')
            if ("Shift" in mods) append('⇧')
            if ("Ctrl" in mods && !ctrlStays) append('⌘')
            append(when (key) { "Enter" -> "↩"; "Esc" -> "⎋"; "Tab" -> "Tab"; else -> key.uppercase() })
        }
    }
}

/**
 * Mac 的菜单栏（规格表 §7.1：应用名 / 文件 / 显示 / 窗口 / 帮助；「关于」「设置…」「退出」在应用菜单里，由 [installMacAppHandlers] 接）。
 * 每项都是已有的动作，快捷键同 [shortcut] 的表（⌘ 代替 Ctrl）。编辑菜单没放：输入框里的 ⌘C / ⌘V / ⌘Z 由 Compose 自己处理。
 */
@Composable
internal fun FrameWindowScope.YxiMacMenuBar(state: AppState, win: WindowState, onClose: () -> Unit) {
    MenuBar {
        Menu("文件") {
            Item("新会话", onClick = { state.newSessionRequest++ }, shortcut = KeyShortcut(Key.N, meta = true))
            Item("切换任务…", onClick = { state.showTaskSwitcher = true }, shortcut = KeyShortcut(Key.K, meta = true))
            Separator()
            Item("关闭窗口", onClick = onClose, shortcut = KeyShortcut(Key.W, meta = true))
        }
        Menu("显示") {
            Item(if (state.sidebarOpen) "隐藏侧栏" else "显示侧栏", onClick = { state.sidebarOpen = !state.sidebarOpen }, shortcut = KeyShortcut(Key.B, meta = true))
            Item("终端", onClick = { state.tab = if (state.tab == 1) 0 else 1 }, shortcut = KeyShortcut(Key.J, meta = true))
            Item("文件", onClick = { state.tab = if (state.tab == 2) 0 else 2 }, shortcut = KeyShortcut(Key.F, meta = true, shift = true))
            Item("网页预览", onClick = { if (state.browserPanelOpen) state.browserPanelOpen = false else { state.browserPanelOpen = true; state.filePanelOpen = false } },
                shortcut = KeyShortcut(Key.P, meta = true, shift = true))
            Item("关掉右侧面板", onClick = { state.tab = 0; state.browserPanelOpen = false; state.filePanelOpen = false; state.previewExpanded = false },
                shortcut = KeyShortcut(Key.Backslash, meta = true))
            Separator()
            Item("放大", onClick = { zoom(+1) }, shortcut = KeyShortcut(Key.Equals, meta = true))
            Item("缩小", onClick = { zoom(-1) }, shortcut = KeyShortcut(Key.Minus, meta = true))
            Item("实际大小", onClick = { zoom(0) }, shortcut = KeyShortcut(Key.Zero, meta = true))
        }
        Menu("窗口") {
            Item("最小化", onClick = { win.isMinimized = true }, shortcut = KeyShortcut(Key.M, meta = true))
            Item("缩放", onClick = { win.placement = if (win.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized })
        }
        Menu("帮助") {
            Item("键盘快捷键", onClick = { state.showShortcuts = true }, shortcut = KeyShortcut(Key.Slash, meta = true))
        }
    }
}

/**
 * 应用菜单里的「关于 Yxi」「设置…」「退出 Yxi」和点 Dock 图标（规格表 §7.2）：关于 → 设置的「关于」页，设置… → 设置，
 * 退出 → 走 [quit]（有未完成的活先确认，所以先取消系统的退出、由我们自己决定），点 Dock 图标 → 把窗口叫回来。只在 Mac 原生时装。
 */
internal fun installMacAppHandlers(state: AppState, quit: () -> Unit) {
    if (!MacChrome.native || !java.awt.Desktop.isDesktopSupported()) return
    val desktop = java.awt.Desktop.getDesktop()
    fun onUi(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
    runCatching { desktop.setAboutHandler { onUi { Shell.show(); state.settingsSection = "关于"; state.showSettings = true } } }
    runCatching { desktop.setPreferencesHandler { onUi { Shell.show(); state.showSettings = true } } }
    runCatching { desktop.setQuitHandler { _, response -> response.cancelQuit(); onUi { quit() } } }
    runCatching { desktop.addAppEventListener(java.awt.desktop.AppReopenedListener { onUi { Shell.show() } }) }
}
