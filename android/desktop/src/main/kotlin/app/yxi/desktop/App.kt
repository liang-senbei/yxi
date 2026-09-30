package app.yxi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import app.yxi.agent.Session
import kotlinx.coroutines.CoroutineScope

/**
 * 桌面版的总布局（像 Claude Desktop：左边栏 = 主机 + 会话列表，右边 = 当前会话的对话 / 终端）。
 * 结构分叉只在这里做一次（PRD §4.2）：经典走 [ClassicShell]（标题栏在 Shell.kt 的 WindowFrame 里），Code 风格走 CodeShell（CodeShell.kt）；
 * 两种外壳共用 [PageBody]、[SessionContent]、[sessionLinks] 和同一组状态 / 控制器，行为不按风格分叉。
 * 各面板分文件：HostsPane（主机）、SessionsPane（会话）、ChatPane（对话）、TermPane（终端）；设置 / 快捷键表两个弹窗也挂在这。
 */
@Composable
fun App(state: AppState) {
    AndroidEmulatorPanel(state.showAndroidEmulator) { state.showAndroidEmulator = false }
    LaunchedEffect(state) {
        while (true) {
            runCatching { state.instructions.pruneCompleted(Store.pref("inputRetentionDays", "0").toIntOrNull() ?: 0) }
                .onFailure { state.workspaceError = "历史清理未完成：${it.message}" }
            kotlinx.coroutines.delay(60 * 60 * 1000L)
        }
    }
    DeferredRouteRunner(state)
    TerminalQueueRunner(state)
    // 留在 App 层：切风格时外壳整个重建，正在打开的文件不能跟着被取消
    val scope = rememberCoroutineScope()
    NativeOverlay(state.showSettings)
    val actions = rememberHostActions(state)
    val code = LocalThemeSpec.current.style == UiStyle.Code
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 窗口 < 700dp 自动收起侧栏（Claude 的 narrowViewportMaxWidth）；变宽只把自动收起的还回去，用户自己 Ctrl+B 关掉的不动
        val narrow = maxWidth < 700.dp
        val wasOpen = remember { mutableStateOf(true) }
        // Ctrl+N / 托盘「新建会话」时侧栏若收着，先展开，建好的会话在侧栏里看得见（弹窗本身在 HostDialogs，不靠侧栏）
        LaunchedEffect(state.newSessionRequest) { if (state.newSessionRequest > 0 && !state.sidebarOpen) state.sidebarOpen = true }
        LaunchedEffect(narrow) { if (narrow) { wasOpen.value = state.sidebarOpen; state.sidebarOpen = false } else if (wasOpen.value) state.sidebarOpen = true }
        if (code) CodeShell(state, actions, scope) else ClassicShell(state, actions, scope)
        // 设置：经典整页盖住；Code 是 1024×800 居中弹窗 + 40% 黑遮罩，点遮罩关（规格表 §3.7）。
        // 调用位置两种风格共用：切风格时设置页不重建（不再读一次注册表）
        if (code && state.showSettings) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { state.showSettings = false })
        if (state.showSettings) androidx.compose.material3.Surface(
            if (code) Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 1024.dp).heightIn(max = 800.dp).fillMaxSize() else Modifier.fillMaxSize(),
            shape = if (code) RoundedCornerShape(12.dp) else RectangleShape,
            color = if (code) Tokens.current.dialog else Tokens.current.surface0,
        ) {
            SettingsDialog(state)
        }
    }
    HostDialogs(actions)
    HostEffects(actions)
    if (state.showShortcuts) ShortcutsDialog(state)
    if (state.showTaskSwitcher) TaskSwitcherDialog(state)
    if (state.showCollaboration) state.conn?.let { conn -> CollaborationDialog(state, conn) { state.showCollaboration = false } }
}

/** 经典外壳：侧栏 + 分隔线 + 整页入口 / 工作区，和拆壳前逐像素相同（M0 基线）。 */
@Composable
private fun ClassicShell(state: AppState, actions: HostActions, scope: CoroutineScope) {
    Row(Modifier.fillMaxSize()) {
        if (state.sidebarOpen && state.page != Page.Me) { Sidebar(state, actions, Modifier.width(LocalThemeSpec.current.metrics.sidebarWidth).fillMaxHeight()); VerticalDivider() }
        Column(Modifier.fillMaxSize()) {
            PageBody(state) {
                val conn = state.conn; val sess = state.session
                if (conn == null || sess == null) WorkspaceWelcome(state) else ClassicWorkspace(state, conn, sess, scope)
            }
        }
    }
}

/** 整页入口（左栏底部的「配置」「我的」）盖住工作区；再点一次那个入口就回来。两种外壳共用，[workspace] 是各自的工作区；[localHome] 是本机工作台的首页（Code 传入，经典为空、照旧）。 */
@Composable
internal fun PageBody(state: AppState, localHome: (@Composable () -> Unit)? = null, workspace: @Composable () -> Unit) {
    when (state.page) {
        Page.Config -> RoutesPane(state)
        Page.ConfigFiles -> ConfigPane(state)
        Page.Plugins -> PluginsPane(state)
        Page.Connections -> ConnectionsPane(state)
        Page.LocalAgents -> LocalAgentsPane(state)
        Page.LocalWorkspace -> LocalWorkspacePane(state, home = localHome)
        Page.ScheduledTasks -> ScheduledTasksPane(state)
        Page.Me -> MePane(state)
        Page.Routes -> RoutesPane(state)
        Page.Codex -> CodexWorkspacePane(state)
        Page.OpenCode -> RemoteOpenCodePane(state)
        Page.Acp -> RemoteAcpPane(state)
        Page.Workspace -> workspace()
    }
}

/** 经典的工作区：视图标签 + 网页预览 / 更多 → 目录行 → 提示 → 正文和文件 / 网页侧栏。 */
@Composable
private fun ClassicWorkspace(state: AppState, conn: Conn, sess: Session, scope: CoroutineScope) {
    val taskKey = taskNavigationKey(conn.host, sess)
    val replaced = sess.runtimeId.isNotEmpty() && conn.sessions.any { it.name == sess.name && it.runtimeId.isNotEmpty() && it.runtimeId != sess.runtimeId }
    val links = sessionLinks(state, conn, sess, taskKey, scope)
    val density = LocalDensity.current.density
    // 对齐手机的 终端 / 对话 / 文件（实验室是手机上的调试入口，桌面不做）
    Row(Modifier.fillMaxWidth().height(LocalThemeSpec.current.metrics.headerHeight).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val views = listOf("对话", "终端", "文件", "改动")
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            WorkbenchTabs(views, views[state.tab], { state.tab = views.indexOf(it) })
        }
        TextButton({ state.browserPanelOpen = true; state.filePanelOpen = false }) { Text("网页预览") }
        val taskMenu = remember(taskKey) { mutableStateOf(false) }
        NativeOverlay(taskMenu.value)
        Box {
            TextButton({ taskMenu.value = true }) { Text("更多") }
            DropdownMenu(taskMenu.value, { taskMenu.value = false }) {
                DropdownMenuItem(text = { Text("模型与线路") }, onClick = { taskMenu.value = false; state.openRoutes() })
                DropdownMenuItem(text = { Text("Android 模拟器") }, onClick = { taskMenu.value = false; state.showAndroidEmulator = true })
                DropdownMenuItem(text = { Text("协作组") }, onClick = { taskMenu.value = false; state.showCollaboration = true })
                DropdownMenuItem(text = { Text("切换任务 · Ctrl+K") }, onClick = { taskMenu.value = false; state.showTaskSwitcher = true })
                DropdownMenuItem(text = { Text("键盘快捷键") }, onClick = { taskMenu.value = false; state.showShortcuts = true })
            }
        }
    }
    Text(sess.cwd, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp), color = Tokens.current.textMuted,
        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (state.workspaceError.isNotBlank()) Text(state.workspaceError, color = Tokens.current.danger)
    DeferredRouteStatus(state)
    if (!state.filePanelOpen && !state.browserPanelOpen && state.documents.any { it.hostId == conn.host.id && it.matchesTask(sess) }) TextButton({ state.filePanelOpen = true }) { Text("打开文件侧栏") }
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

/** 当前会话的正文：对话 / 终端 / 文件 / 改动（state.tab）。两种外壳共用；key(taskKey) 让换会话时各面板重建。 */
@Composable
internal fun SessionContent(state: AppState, conn: Conn, sess: Session, taskKey: String, openFile: (String) -> Unit) {
    key(taskKey) { when (state.tab) {
        0 -> ChatPane(conn, sess, state.instructions, state.chatAttachments, savedDraft = state.chatDrafts.getOrPut(taskKey) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }, displayName = state.navigation.title(taskKey), onRoutes = { state.openRoutes() }, onTerminal = { state.tab = 1 }, modelSwitches = state.modelSwitches)
        1 -> TermPane(conn, sess)
        2 -> FilesPane(conn, sess, openFile)
        else -> GitChangesPane(conn, sess.cwd, openFile) { quote -> state.appendDocumentQuote(conn.host, sess, quote); state.tab = 0 }
    } }
}

/** 会话里的链接：http(s) / mailto 交给 [previewWebLinks]，没有 scheme 的路径当文件打开，其余提示不支持。两种外壳共用。 */
internal class SessionLinks(val openFile: (String) -> Unit, val uris: UriHandler)

@Composable
internal fun sessionLinks(state: AppState, conn: Conn, sess: Session, taskKey: String, scope: CoroutineScope): SessionLinks {
    val defaultUris = LocalUriHandler.current   // 必须在 CompositionLocalProvider(LocalUriHandler provides …) 之外取
    fun openFile(path: String) { scope.launch {
        try { state.openDocument(conn, sess, path); state.workspaceError = "" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.workspaceError = "文件无法打开：${e.message}" }
    } }
    val webLinks = previewWebLinks(state, conn, taskKey, defaultUris)
    val uris = object : UriHandler {
        override fun openUri(uri: String) {
            val targetUri = normalizedWebLink(uri)
            val parsed = runCatching { java.net.URI(targetUri) }.getOrNull()
            when {
                parsed == null -> state.workspaceError = "无法识别链接"
                parsed.scheme?.lowercase() in listOf("https", "http", "mailto") -> runCatching { webLinks.openUri(targetUri) }.onFailure { state.workspaceError = it.message.orEmpty() }
                parsed.scheme == null && !parsed.path.isNullOrBlank() -> openFile(parsed.path)
                else -> state.workspaceError = "暂不支持该链接，请从文件列表打开"
            }
        }
    }
    return SessionLinks(::openFile, uris)
}
