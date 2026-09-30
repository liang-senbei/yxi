package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import kotlinx.coroutines.launch

/**
 * 一台主机的项目树：分组标题 + 各类任务行。行为和对话框（改名 / 配置 / 组内新建 / 分组编辑）只写在这里，两种风格共用；
 * [look] 为 null 画经典样式，Code 侧栏传自己的外观（[TreeLook]），只换画法（PRD §4.2）。
 */
@Composable
internal fun ProjectTree(state: AppState, conn: Conn, sessions: List<Session>, searching: Boolean, query: String = "", look: TreeLook? = null) {
    val nav = state.navigation
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var openError by remember(conn) { mutableStateOf("") }
    val tree = hostTree(state, conn, sessions, searching, query)
    @Composable fun codexTask(record: CodexTaskRecord) {
        var menu by remember(record.key) { mutableStateOf(false) }
        var rename by remember(record.key) { mutableStateOf(false) }
        var configuring by remember(record.key) { mutableStateOf(false) }
        var displayTitle by remember(record.key, rename) { mutableStateOf(nav.title(record.key) ?: record.title) }
        NativeOverlay(menu)
        val selected = state.page == Page.Codex && state.codexSelectedTaskKey == record.key && state.conn === conn
        val controller = state.codexWorkspace.controllers[record.key]
        val open: () -> Unit = {
            state.select(conn, null); state.codexSelectedTaskKey = record.key; state.page = Page.Codex
            if (controller == null && conn.ssh.isConnected) scope.launch {
                try { state.codexWorkspace.open(conn, record); openError = "" }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { openError = e.message.orEmpty() }
            }
        }
        val entries = { codexMenu(state, conn, record, onConfigure = { configuring = true }, onRename = { rename = true }) }
        if (look != null) look.row(TreeRow(record.key, nav.title(record.key) ?: record.title, "Codex", codexTaskState(state, record), selected, entries, open))
        else Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp).background(if (selected) t.surface3 else androidx.compose.ui.graphics.Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = open).padding(vertical = 8.dp)) {
                Text(nav.title(record.key) ?: record.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("Codex · " + when {
                    controller?.pendingRequests?.isNotEmpty() == true -> "需要你处理"
                    controller?.activeTurnId != null -> "正在运行"
                    controller?.ready == true -> "空闲"
                    else -> "未连接"
                }, style = MaterialTheme.typography.labelSmall, color = t.textMuted)
            }
            Box {
                IconButton({ menu = true }, Modifier.size(26.dp)) { Icon(Icons.Default.MoreHoriz, "会话操作", Modifier.size(16.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    ClassicMenuEntries(entries()) { menu = false }
                }
            }
        }
        if (configuring) CodexAgentConfigurationDialog(state, conn, record) { configuring = false }
        if (rename) WorkbenchDialog(onDismissRequest = { rename = false }, title = { Text("修改 Agent 显示名称") },
            text = { Column {
                OutlinedTextField(displayTitle, { displayTitle = it }, singleLine = true, label = { Text("名称") })
                if (nav.error.isNotBlank()) Text(nav.error, color = t.danger)
            } },
            confirmButton = { TextButton({ nav.rename(record.key, displayTitle); if (nav.error.isBlank()) rename = false }) { Text("保存") } },
            dismissButton = { TextButton({ rename = false }) { Text("取消") } })
    }
    var projectGroups by remember(conn) { mutableStateOf(false) }
    var selectedGroup by remember(conn) { mutableStateOf("") }
    var creatingGroup by remember(conn) { mutableStateOf("") }
    var creatingDirectory by remember(conn) { mutableStateOf<String?>(null) }
    if (projectGroups) CollaborationDialog(state, conn, initialGroup = selectedGroup) { projectGroups = false }
    creatingDirectory?.let { directory ->
        NewSessionDialog(conn, onDismiss = { creatingDirectory = null; creatingGroup = "" }, initialDirectory = directory.takeIf { it.isNotBlank() }, collaborationGroup = creatingGroup, sharedMcpRegistry = state.sharedMcp,
            onAcpConversation = { engine, path, prompt ->
                creatingDirectory = null; creatingGroup = ""; state.prepareAcpTask(conn, engine, path, prompt)
            }, onOpenCodeConversation = { path, prompt ->
                creatingDirectory = null; creatingGroup = ""; state.prepareOpenCodeTask(conn, path, prompt)
            }, onCodexConversation = { path, prompt ->
                creatingDirectory = null
                state.prepareCodexTask(conn, path, prompt, creatingGroup)
                creatingGroup = ""
            }) { session ->
            creatingDirectory = null
            state.select(conn, session)
        }
    }
    @Composable fun task(s: Session) {
        val key = taskNavigationKey(conn.host, s)
        var menu by remember(key) { mutableStateOf(false) }
        NativeOverlay(menu)
        var renaming by remember(key) { mutableStateOf(false) }
        var configuring by remember(key) { mutableStateOf(false) }
        var title by remember(key, renaming) { mutableStateOf(nav.title(key) ?: s.short) }
        val stable = s.runtimeId.isNotBlank()
        val selected = state.conn === conn && (if (stable) state.session?.runtimeId == s.runtimeId else state.session?.name == s.name)
        val reveal = remember(key) { BringIntoViewRequester() }
        LaunchedEffect(selected) { if (selected) { withFrameNanos { }; reveal.bringIntoView() } }
        val entries = { sessionMenu(nav, conn, s, key, stable, tree.pinKeys, onConfigure = { configuring = true }, onRename = { renaming = true }) }
        if (look != null) Box(Modifier.fillMaxWidth().bringIntoViewRequester(reveal)) {
            look.row(TreeRow(key, nav.title(key) ?: s.short, LocalRuntimeDiscovery.title(s.agent), s.state, selected, entries) { state.select(conn, s) })
        } else Row(Modifier.fillMaxWidth().padding(start = 6.dp).bringIntoViewRequester(reveal), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SessionRow(s, selected = selected,
                displayName = nav.title(key)) { state.select(conn, s) } }
            Box {
                IconButton({ menu = true }, Modifier.size(26.dp)) { Icon(Icons.Default.MoreHoriz, "任务操作", Modifier.size(16.dp), tint = t.textMuted) }
                DropdownMenu(menu, { menu = false }) {
                    ClassicMenuEntries(entries()) { menu = false }
                }
            }
        }
        if (configuring) AgentConfigurationDialog(conn, s, state.modelSwitches) { configuring = false }
        if (renaming) WorkbenchDialog(onDismissRequest = { renaming = false }, title = { Text("修改任务显示名称") },
            text = { Column {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("名称") })
                Text("仅修改本机显示，不重命名服务器会话。", style = MaterialTheme.typography.bodySmall)
                if (nav.error.isNotBlank()) Text(nav.error, color = t.danger)
            } },
            confirmButton = { TextButton({ nav.rename(key, title); if (nav.error.isBlank()) renaming = false }) { Text("保存") } },
            dismissButton = { TextButton({ renaming = false }) { Text("取消") } })
    }
    if (!conn.groupsLoaded) {
        if (look != null) look.note(conn.groupsError.ifBlank { "正在读取服务器分组…" }, false)
        else Text(conn.groupsError.ifBlank { "正在读取服务器分组…" }, Modifier.padding(16.dp), color = t.textMuted)
        if (conn.groupsError.isNotBlank()) {
            if (look != null) look.note("暂列出全部 Agent，分组恢复后自动整理", false)
            else Text("暂列出全部 Agent，分组恢复后自动整理", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
            tree.sessions.forEach { task(it) }
        }
        tree.codex.forEach { codexTask(it) }
        tree.openCode.forEach { OpenCodeTaskRow(state, conn, it, look) }
        tree.acp.forEach { AcpTaskRow(state, conn, it, look) }
        return
    }
    if (look == null) Text("项目分组", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
    tree.groups.forEach { group ->
        val name = group.name
        val closed = group.closed
        val toggle: () -> Unit = { nav.setCollapsed(group.key, !closed) }
        val create: () -> Unit = { creatingGroup = name; creatingDirectory = group.directories.firstOrNull().orEmpty() }
        val entries = { projectGroupMenu(conn, name, group.directories, onEdit = { selectedGroup = name; projectGroups = true }) }
        // 分组标题里有按位置记的状态（⋮ 开关、hover），按分组键定位，搜索滤掉前面的分组时不串
        if (look != null) key(group.key) { look.group(name, closed, toggle, create, entries) }
        else ClassicGroupHeader(name, closed, group.count, toggle, create, entries)
        if (!closed) {
            group.sessions.forEach { task(it) }
            group.codex.forEach { codexTask(it) }
            group.openCode.forEach { OpenCodeTaskRow(state, conn, it, look) }
            group.acp.forEach { AcpTaskRow(state, conn, it, look) }
        }
    }
    if (conn.groupsError.isNotBlank()) { if (look != null) look.note(conn.groupsError, true) else Text(conn.groupsError, color = t.warning, style = MaterialTheme.typography.bodySmall) }
    if (openError.isNotBlank()) { if (look != null) look.note(openError, true) else Text(openError, color = t.warning, style = MaterialTheme.typography.bodySmall) }
    return
}

/** 经典的分组标题：折叠箭头、文件夹、名称、成员数、组内新建、⋯ 分组菜单；整行点一下折叠 / 展开。 */
@Composable
private fun ClassicGroupHeader(name: String, closed: Boolean, count: Int, onToggle: () -> Unit, onCreate: () -> Unit, entries: () -> List<MenuEntry>) {
    val t = Tokens.current
    var menu by remember(name) { mutableStateOf(false) }
    NativeOverlay(menu)
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (closed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, "展开分组", Modifier.size(16.dp), tint = t.textMuted)
        Icon(Icons.Outlined.Folder, null, Modifier.padding(horizontal = 6.dp).size(16.dp), tint = t.textSecondary)
        Text(name.ifEmpty { "未分组" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        IconButton(onCreate, Modifier.size(28.dp)) { Icon(Icons.Outlined.Add, "在组内新建 Agent", Modifier.size(16.dp)) }
        Box {
            IconButton({ menu = true }, Modifier.size(28.dp)) { Icon(Icons.Default.MoreHoriz, "分组操作", Modifier.size(16.dp)) }
            DropdownMenu(menu, { menu = false }) {
                ClassicMenuEntries(entries()) { menu = false }
            }
        }
    }
}

