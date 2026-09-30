package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import kotlinx.coroutines.CoroutineScope

/**
 * Code 风格外壳（PRD §4.2 / §5.1）。顶部 36 高的条整条可拖，只放窗口按钮；左上四个图标按钮原地不动，侧栏收起时也在。
 * 主区是 Column：PageBody 的 workspace 往里直接发兄弟节点。业务组件、控制器、状态和经典共用。
 * 画的顺序即命中的倒序：顶部条最先画（最后命中），图标行最后画（只有按钮接事件）。
 */
@Composable
internal fun CodeShell(state: AppState, actions: HostActions, scope: CoroutineScope) {
    Box(Modifier.fillMaxSize().background(Tokens.current.surface0)) {
        CodeTopBar()
        Row(Modifier.fillMaxSize()) {
            if (state.sidebarOpen && state.page != Page.Me) CodeSidebar(state, actions, Modifier.width(288.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Spacer(Modifier.height(36.dp))
                PageBody(state, localHome = { CodeLocalHome(state, actions, scope) }) {
                    val conn = state.conn
                    val sess = state.session
                    if (conn == null || sess == null) CodeHome(state, actions, scope) else CodeWorkspace(state, conn, sess, scope)
                }
            }
        }
        CodeIconRow(state)
    }
}

/** 顶部条：可拖动、双击最大化 / 还原；右端是窗口按钮（中心 1933 / 1979 / 2025）。 */
@Composable
private fun CodeTopBar() {
    val chrome = LocalWindowChrome.current ?: return
    val p = CodePalette.current
    with(chrome.scope) {
        WindowDraggableArea(Modifier.fillMaxWidth().height(36.dp).pointerInput(chrome) { detectTapGestures(onDoubleTap = { chrome.toggleMaximized() }) }) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
                WindowButtons(chrome.win, chrome.onClose, p.windowGlyph, p.closeFill, Color.White)
            }
        }
    }
}
/**
 * 左上四个图标：菜单 / 侧栏开关 / 后退 / 前进（中心 x 26 / 58 / 90 / 120，y 18）。
 * 后退、前进本期画成不可用（Page 不做历史栈）。外层 Box 不接事件，空白处照样能拖窗口。
 */
@Composable
private fun CodeIconRow(state: AppState) {
    val t = Tokens.current
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
    Box(Modifier.size(288.dp, 36.dp)) {
        Box(Modifier.offset(14.dp, 6.dp)) {
            CodeIconButton(Icons.Filled.Menu, "菜单", held = menu, tint = t.textPrimary) { menu = true }
            if (menu) CodeAppMenu(state) { menu = false }
        }
        CodeIconButton(
            Icons.AutoMirrored.Outlined.ViewSidebar, if (state.sidebarOpen) "收起侧栏" else "展开侧栏",
            Modifier.offset(44.dp, 4.dp), box = 28.dp, tint = t.textPrimary, shortcut = "Ctrl+B",
        ) { state.sidebarOpen = !state.sidebarOpen }
        CodeIconButton(Icons.AutoMirrored.Filled.ArrowBack, "后退", Modifier.offset(78.dp, 6.dp), enabled = false) {}
        CodeIconButton(Icons.AutoMirrored.Filled.ArrowForward, "前进", Modifier.offset(108.dp, 6.dp), enabled = false) {}
    }
}

/** 汉堡菜单：应用级入口，都是已有快捷键的动作。宽 240 是定值（规格没量到）。 */
@Composable
private fun CodeAppMenu(state: AppState, close: () -> Unit) {
    val chrome = LocalWindowChrome.current
    CodePopup(close, width = 240.dp) {
        CodeMenuItem("新会话", { close(); state.newSessionRequest++ }, trailing = "Ctrl+N")
        CodeMenuItem("切换任务", { close(); state.showTaskSwitcher = true }, trailing = "Ctrl+K")
        CodeMenuItem(if (state.sidebarOpen) "收起侧栏" else "展开侧栏", { close(); state.sidebarOpen = !state.sidebarOpen }, trailing = "Ctrl+B")
        CodeMenuDivider()
        CodeMenuItem("设置", { close(); state.showSettings = true }, trailing = "Ctrl+,")
        CodeMenuItem("键盘快捷键", { close(); state.showShortcuts = true }, trailing = "Ctrl+/")
        if (chrome != null) {
            CodeMenuDivider()
            CodeMenuItem("退出 Yxi", { close(); chrome.onQuit() })
        }
    }
}

/**
 * 会话工作区：Code 会话头 + 内容。M2 仍按 state.tab 切对话 / 终端 / 文件 / 改动（右侧浮起面板在 M4）；
 * 文件 / 网页预览侧栏沿用经典的分栏逻辑。M4 要改成浮起卡片，所以这里单独一份，不和经典共用。
 */
@Composable
private fun CodeWorkspace(state: AppState, conn: Conn, sess: Session, scope: CoroutineScope) {
    val taskKey = taskNavigationKey(conn.host, sess)
    val replaced = sess.runtimeId.isNotEmpty() && conn.sessions.any { it.name == sess.name && it.runtimeId.isNotEmpty() && it.runtimeId != sess.runtimeId }
    val links = sessionLinks(state, conn, sess, taskKey, scope)
    val density = LocalDensity.current.density
    CodeSessionHeader(state, conn, sess, taskKey)
    if (state.workspaceError.isNotBlank()) {
        Text(state.workspaceError, Modifier.padding(horizontal = 17.6.dp), color = Tokens.current.danger, fontSize = 13.sp, lineHeight = 18.sp)
    }
    DeferredRouteStatus(state)
    CompositionLocalProvider(LocalUriHandler provides links.uris) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panel = state.filePanelOpen || state.browserPanelOpen
            val compact = maxWidth < 850.dp || state.previewExpanded
            val availableWidth = maxWidth.value
            Row(Modifier.fillMaxSize()) {
                if (!panel || !compact) Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (replaced) Text("原会话已结束，同名会话是新任务。请在左侧重新选择；旧草稿已保留。", Modifier.padding(24.dp))
                    else SessionContent(state, conn, sess, taskKey, links.openFile)
                }
                if (panel) {
                    if (!compact) Box(Modifier.width(6.dp).fillMaxHeight().background(Tokens.current.border).draggable(
                        rememberDraggableState { delta -> state.filePanelWidth = (state.filePanelWidth - delta / density).coerceIn(340f, 900f) }, Orientation.Horizontal,
                        onDragStopped = { state.savePreviewWidth() }))
                    Box(if (compact) Modifier.fillMaxSize() else Modifier.width(state.filePanelWidth.coerceAtMost(availableWidth - 350).dp).fillMaxHeight()) {
                        if (state.browserPanelOpen) BrowserPane(state, conn, sess) else DocumentPane(state, conn, sess)
                    }
                }
            }
        }
    }
}
/**
 * 会话头（y 36–84，高 48，无分隔线）。左：环境图标（305.6）、标题（328.8）、任务操作箭头、连接状态；
 * 右：终端 / 改动 / 预览 / ⋮，中心 1933.2 / 1962.8 / 1992.4 / 2022（end 14 + 间距 5.6）。
 * 按钮对应的视图开着时是激活态。连接状态和重连照经典 SessionHeader（Code 下 ChatPane 不再画它）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeSessionHeader(state: AppState, conn: Conn, sess: Session, taskKey: String) {
    val t = Tokens.current
    val p = CodePalette.current
    var titleMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    NativeOverlay(titleMenu || more)
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 17.6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Dns, conn.host.label, Modifier.size(16.dp), tint = t.textSecondary)
            Spacer(Modifier.width(7.2.dp))
            TooltipArea(
                tooltip = { CodeTooltip(sess.cwd) },
                modifier = Modifier.weight(1f, fill = false),
                delayMillis = 500,
                tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.BottomStart, Alignment.BottomEnd, DpOffset(0.dp, 5.dp)),
            ) {
                Text(state.navigation.title(taskKey) ?: sess.short, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.padding(start = 6.dp)) {
                CodeIconButton(Icons.Filled.KeyboardArrowDown, "任务操作", held = titleMenu, tint = p.titleArrow) { titleMenu = true }
                if (titleMenu) CodeTitleMenu(state, sess, taskKey, onRename = { renaming = true }) { titleMenu = false }
            }
            CodeConnStatus(conn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.6.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeIconButton(Icons.Outlined.Terminal, "终端", active = state.tab == 1, shortcut = "Ctrl+J") { state.tab = if (state.tab == 1) 0 else 1 }
            CodeIconButton(Icons.Outlined.Difference, "改动", active = state.tab == 3) { state.tab = if (state.tab == 3) 0 else 3 }
            CodeIconButton(Icons.Outlined.Preview, "网页预览", active = state.browserPanelOpen) {
                if (state.browserPanelOpen) state.browserPanelOpen = false else { state.browserPanelOpen = true; state.filePanelOpen = false }
            }
            Box {
                CodeIconButton(Icons.Filled.MoreVert, "更多", held = more) { more = true }
                if (more) CodeMoreMenu(state, conn, sess) { more = false }
            }
        }
    }
    if (renaming) CodeRenameDialog(state, sess, taskKey) { renaming = false }
}
/** 标题菜单：照侧栏任务菜单（ProjectTree）的四项和可用条件。按内容宽，左对齐箭头按钮、下方 5.6。 */
@Composable
private fun CodeTitleMenu(state: AppState, sess: Session, taskKey: String, onRename: () -> Unit, close: () -> Unit) {
    val nav = state.navigation
    val stable = sess.runtimeId.isNotBlank()
    val archived = nav.archived(taskKey)
    val canArchive = sess.state != SessionState.Working && sess.state != SessionState.NeedsYou
    CodePopup(close, gap = 5.6.dp, width = null) {
        CodeMenuItem("修改显示名称", { close(); onRename() })
        CodeMenuItem(if (nav.pinned(taskKey)) "取消置顶" else "置顶", { close(); nav.togglePin(taskKey) }, enabled = stable)
        CodeMenuItem(
            if (archived) "恢复到项目列表" else if (canArchive) "归档" else "运行中或待处理任务不能归档",
            { close(); nav.setArchived(taskKey, !archived) }, enabled = stable && (archived || canArchive),
        )
        CodeMenuDivider()
        CodeMenuItem("复制项目路径", {
            close()
            runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(sess.cwd), null) }
        })
    }
}

/** 改名弹窗：同侧栏任务菜单的「修改任务显示名称」，只改本机显示。 */
@Composable
private fun CodeRenameDialog(state: AppState, sess: Session, taskKey: String, close: () -> Unit) {
    val nav = state.navigation
    var title by remember(taskKey) { mutableStateOf(nav.title(taskKey) ?: sess.short) }
    WorkbenchDialog(
        onDismissRequest = close,
        title = { Text("修改任务显示名称") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("名称") })
                Text("仅修改本机显示，不重命名服务器会话。", style = MaterialTheme.typography.bodySmall)
                if (nav.error.isNotBlank()) Text(nav.error, color = Tokens.current.danger)
            }
        },
        confirmButton = { TextButton({ nav.rename(taskKey, title); if (nav.error.isBlank()) close() }) { Text("保存") } },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
}
/** 会话头「更多」：文件视图、文件侧栏，以及经典「更多」里的全部入口。右对齐、下方 6.4。 */
@Composable
private fun CodeMoreMenu(state: AppState, conn: Conn, sess: Session, close: () -> Unit) {
    val docs = !state.filePanelOpen && !state.browserPanelOpen && state.documents.any { it.hostId == conn.host.id && it.matchesTask(sess) }
    CodePopup(close, alignEnd = true, gap = 6.4.dp) {
        CodeMenuItem("文件", { close(); state.tab = if (state.tab == 2) 0 else 2 }, checked = state.tab == 2)
        if (docs) CodeMenuItem("打开文件侧栏", { close(); state.filePanelOpen = true })
        CodeMenuDivider()
        CodeMenuItem("模型与线路", { close(); state.openRoutes() })
        CodeMenuItem("Android 模拟器", { close(); state.showAndroidEmulator = true })
        CodeMenuItem("协作组", { close(); state.showCollaboration = true })
        CodeMenuItem("切换任务", { close(); state.showTaskSwitcher = true }, trailing = "Ctrl+K")
        CodeMenuItem("键盘快捷键", { close(); state.showShortcuts = true }, trailing = "Ctrl+/")
    }
}

/**
 * 连接状态：文案和判定同经典 SessionHeader，已连接时不占位；断开时可重连。
 * 始终在组合里（已连接时提前返回），这样「连上过」的记忆不会随显隐丢掉。
 */
@Composable
private fun CodeConnStatus(conn: Conn) {
    val t = Tokens.current
    var everConnected by remember(conn) { mutableStateOf(false) }
    LaunchedEffect(conn.status) { if (conn.status == Conn.Status.Connected) everConnected = true }
    if (conn.status == Conn.Status.Connected) return
    val down = conn.status == Conn.Status.Failed || conn.status == Conn.Status.Idle
    val (color, label) = when {
        down -> t.danger to "已断开连接"
        everConnected -> t.warning to "正在重新连接…"
        else -> t.warning to "正在连接"
    }
    Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
        if (down) TextButton(onClick = { conn.start() }) { Text("重新连接", color = t.accent, fontSize = 13.sp) }
    }
}
