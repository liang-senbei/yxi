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

/**
 * 桌面版的总布局（像 Claude Desktop：左边栏 = 主机 + 会话列表，右边 = 当前会话的对话 / 终端），标题栏在外面（Main.kt）。
 * 各面板分文件：HostsPane（主机）、SessionsPane（会话）、ChatPane（对话）、TermPane（终端）；设置 / 快捷键表两个弹窗也挂在这。
 */
@Composable
fun App(state: AppState) {
    LaunchedEffect(state) {
        while (true) {
            runCatching { state.instructions.pruneCompleted(Store.pref("inputRetentionDays", "0").toIntOrNull() ?: 0) }
                .onFailure { state.workspaceError = "历史清理未完成：${it.message}" }
            kotlinx.coroutines.delay(60 * 60 * 1000L)
        }
    }
    DeferredRouteRunner(state)
    val scope = rememberCoroutineScope()
    val defaultUris = LocalUriHandler.current
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 窗口 < 700dp 自动收起侧栏（Claude 的 narrowViewportMaxWidth）；变宽只把自动收起的还回去，用户自己 Ctrl+B 关掉的不动
        val narrow = maxWidth < 700.dp
        val wasOpen = remember { mutableStateOf(true) }
        // Ctrl+N / 托盘「新建会话」时侧栏若收着，先展开——「新建会话」弹窗住在 Sidebar 里，收起时它根本没组合出来
        LaunchedEffect(state.newSessionRequest) { if (state.newSessionRequest > 0 && !state.sidebarOpen) state.sidebarOpen = true }
        LaunchedEffect(narrow) { if (narrow) { wasOpen.value = state.sidebarOpen; state.sidebarOpen = false } else if (wasOpen.value) state.sidebarOpen = true }
        Row(Modifier.fillMaxSize()) {
            if (state.sidebarOpen) { Sidebar(state, Modifier.width(288.dp).fillMaxHeight()); VerticalDivider() }
            Column(Modifier.fillMaxSize()) {
                // 整页入口（左栏底部的「配置」「我的」）盖住工作区；再点一次那个入口就回来
                when (state.page) {
                    Page.Config -> ConfigPane(state)
                    Page.Me -> MePane(state)
                    Page.Routes -> RoutesPane(state)
                    Page.Codex -> CodexWorkspacePane(state)
                    Page.Workspace -> {
                        val conn = state.conn; val sess = state.session
                        if (conn == null || sess == null) {
                            WorkspaceWelcome(state)
                        } else {
                            val taskKey = taskNavigationKey(conn.host, sess)
                            val replaced = sess.runtimeId.isNotEmpty() && conn.sessions.any { it.name == sess.name && it.runtimeId.isNotEmpty() && it.runtimeId != sess.runtimeId }
                            fun openFile(path: String) { scope.launch {
                                try { state.openDocument(conn, sess, path); state.workspaceError = "" }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { state.workspaceError = "文件无法打开：${e.message}" }
                            } }
                            val links = object : UriHandler {
                                override fun openUri(uri: String) {
                                    val parsed = runCatching { java.net.URI(uri) }.getOrNull()
                                    when {
                                        parsed == null -> state.workspaceError = "无法识别链接"
                                        parsed.scheme in listOf("https", "http", "mailto") -> runCatching { defaultUris.openUri(uri) }.onFailure { state.workspaceError = it.message.orEmpty() }
                                        parsed.scheme == null && !parsed.path.isNullOrBlank() -> openFile(parsed.path)
                                        else -> state.workspaceError = "暂不支持该链接，请从文件列表打开"
                                    }
                                }
                            }
                            // 对齐手机的 终端 / 对话 / 文件（实验室是手机上的调试入口，桌面不做）
                            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                        DropdownMenuItem(text = { Text("Codex 任务") }, onClick = { taskMenu.value = false; state.page = Page.Codex })
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
                            CompositionLocalProvider(LocalUriHandler provides links) {
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val panel = state.filePanelOpen || state.browserPanelOpen
                                    val compact = maxWidth < 850.dp || state.previewExpanded
                                    val availableWidth = maxWidth.value
                                    Row(Modifier.fillMaxSize()) {
                                        if (!panel || !compact) Column(Modifier.weight(1f).fillMaxHeight()) {
                                            if (replaced) Text("原会话已结束，同名会话是新任务。请在左侧重新选择；旧草稿已保留。", Modifier.padding(24.dp))
                                            else key(taskKey) { when (state.tab) {
                                                0 -> ChatPane(conn, sess, state.instructions, savedDraft = state.chatDrafts.getOrPut(taskKey) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }, displayName = state.navigation.title(taskKey))
                                                1 -> TermPane(conn, sess)
                                                2 -> FilesPane(conn, sess, ::openFile)
                                                else -> GitChangesPane(conn, sess.cwd, ::openFile) { quote -> state.appendDocumentQuote(conn.host, sess, quote); state.tab = 0 }
                                            } }
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
                    }
                }
            }
        }
    }
    if (state.showSettings) SettingsDialog(state)
    if (state.showShortcuts) ShortcutsDialog(state)
    if (state.showTaskSwitcher) TaskSwitcherDialog(state)
    if (state.showCollaboration) state.conn?.let { conn -> CollaborationDialog(state, conn) { state.showCollaboration = false } }
}
