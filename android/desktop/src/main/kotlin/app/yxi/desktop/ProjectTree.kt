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
import app.yxi.agent.SessionState

@Composable
fun ProjectTree(state: AppState, conn: Conn, sessions: List<Session>, searching: Boolean) {
    val nav = state.navigation
    val t = Tokens.current
    var projectGroups by remember(conn) { mutableStateOf(false) }
    var selectedGroup by remember(conn) { mutableStateOf("") }
    var creatingGroup by remember(conn) { mutableStateOf("") }
    var creatingDirectory by remember(conn) { mutableStateOf<String?>(null) }
    if (projectGroups) CollaborationDialog(state, conn, initialGroup = selectedGroup) { projectGroups = false }
    creatingDirectory?.let { directory ->
        NewSessionDialog(conn, onDismiss = { creatingDirectory = null; creatingGroup = "" }, initialDirectory = directory, collaborationGroup = creatingGroup,
            onCodexConversation = { path, prompt ->
                creatingDirectory = null
                state.prepareCodexTask(conn, path, prompt)
            }) { session ->
            creatingDirectory = null
            state.select(conn, session)
        }
    }
    val visible = sessions.filter { nav.visible(taskNavigationKey(conn.host, it), it.state) }
    val pinned = if (nav.mode == "归档") emptyList() else visible.filter { nav.pinned(taskNavigationKey(conn.host, it)) }.sortedBy { nav.pinOrder(taskNavigationKey(conn.host, it)) }
    val pinKeys = pinned.map { taskNavigationKey(conn.host, it) }
    @Composable fun task(s: Session) {
        val key = taskNavigationKey(conn.host, s)
        var menu by remember(key) { mutableStateOf(false) }
        NativeOverlay(menu)
        var renaming by remember(key) { mutableStateOf(false) }
        var title by remember(key, renaming) { mutableStateOf(nav.title(key) ?: s.short) }
        val stable = s.runtimeId.isNotBlank()
        val selected = state.conn === conn && (if (stable) state.session?.runtimeId == s.runtimeId else state.session?.name == s.name)
        val reveal = remember(key) { BringIntoViewRequester() }
        LaunchedEffect(selected) { if (selected) { withFrameNanos { }; reveal.bringIntoView() } }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp).bringIntoViewRequester(reveal), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SessionRow(s, selected = selected,
                displayName = nav.title(key)) { state.select(conn, s) } }
            Box {
                IconButton({ menu = true }, Modifier.size(26.dp)) { Icon(Icons.Default.MoreHoriz, "任务操作", Modifier.size(16.dp), tint = t.textMuted) }
                DropdownMenu(menu, { menu = false }) {
                    @Composable fun item(label: String, enabled: Boolean = stable, action: () -> Unit) {
                        DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { menu = false; action() })
                    }
                    if (!stable) DropdownMenuItem(text = { Text("会话标识不可用，请刷新后整理") }, enabled = false, onClick = {})
                    item(if (nav.pinned(key)) "取消置顶" else "置顶") { nav.togglePin(key) }
                    item(if (nav.muted(key)) "恢复任务通知" else "静音此任务") { nav.setMuted(key, !nav.muted(key)) }
                    item(if (nav.favorite(key)) "取消收藏启动入口" else "收藏为启动入口", stable && s.cwd.startsWith('/')) { nav.setFavorite(key, conn.host, s, !nav.favorite(key)) }
                    if (nav.pinned(key)) {
                        item("置顶上移", pinKeys.indexOf(key) > 0) { nav.movePin(key, -1, pinKeys) }
                        item("置顶下移", pinKeys.indexOf(key) in 0 until pinKeys.lastIndex) { nav.movePin(key, 1, pinKeys) }
                    }
                    item("修改显示名称") { renaming = true }
                    val canArchive = s.state != SessionState.Working && s.state != SessionState.NeedsYou
                    item(if (nav.archived(key)) "恢复到项目列表" else if (canArchive) "归档" else "运行中或待处理任务不能归档", stable && (nav.archived(key) || canArchive)) {
                        nav.setArchived(key, !nav.archived(key))
                    }
                    item("复制项目路径", true) { runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(s.cwd), null) } }
                }
            }
        }
        if (renaming) WorkbenchDialog(onDismissRequest = { renaming = false }, title = { Text("修改任务显示名称") },
            text = { Column {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("名称") })
                Text("仅修改本机显示，不重命名服务器会话。", style = MaterialTheme.typography.bodySmall)
                if (nav.error.isNotBlank()) Text(nav.error, color = t.danger)
            } },
            confirmButton = { TextButton({ nav.rename(key, title); if (nav.error.isBlank()) renaming = false }) { Text("保存") } },
            dismissButton = { TextButton({ renaming = false }) { Text("取消") } })
    }
    if (pinned.isNotEmpty()) {
        Text("置顶", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp), color = t.textMuted, style = MaterialTheme.typography.labelSmall)
        pinned.forEach { task(it) }
    }
    if (!conn.groupsLoaded) {
        Text(conn.groupsError.ifBlank { "正在读取服务器分组…" }, Modifier.padding(16.dp), color = t.textMuted)
        return
    }
    val groupedNames = conn.projectGroups.groups.values.flatten().toSet()
    val groupRows = conn.projectGroups.groups.entries.map { it.key to visible.filter { s -> s.name in it.value } } +
        listOf("" to visible.filter { it.name !in groupedNames })
    Text("项目分组", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
    groupRows.forEach { (name, members) ->
        if ((searching || name.isEmpty()) && members.isEmpty()) return@forEach
        val groupKey = "server-group:" + projectKey(conn.host, "/") + ":" + name
        val closed = !searching && nav.collapsed(groupKey, false)
        var menu by remember(groupKey) { mutableStateOf(false) }
        NativeOverlay(menu)
        Row(Modifier.fillMaxWidth().clickable { nav.setCollapsed(groupKey, !closed) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (closed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, "展开分组", Modifier.size(16.dp), tint = t.textMuted)
            Icon(Icons.Outlined.Folder, null, Modifier.padding(horizontal = 6.dp).size(16.dp), tint = t.textSecondary)
            Text(name.ifEmpty { "未分组" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(members.size.toString(), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
            IconButton({ creatingGroup = name; creatingDirectory = members.firstOrNull()?.cwd.orEmpty() }, Modifier.size(28.dp)) { Icon(Icons.Outlined.Add, "在组内新建 Agent", Modifier.size(16.dp)) }
            Box {
                IconButton({ menu = true }, Modifier.size(28.dp)) { Icon(Icons.Default.MoreHoriz, "分组操作", Modifier.size(16.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    Text(conn.host.label + " · " + name.ifEmpty { "未分组" }, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
                    members.map { it.cwd }.distinct().filter { it.isNotBlank() }.forEach { path ->
                        DropdownMenuItem(text = { Text(path) }, onClick = { runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(path), null) }; menu = false })
                    }
                    DropdownMenuItem(text = { Text("编辑分组与组规") }, onClick = { selectedGroup = name; projectGroups = true; menu = false })
                }
            }
        }
        if (!closed) members.forEach { task(it) }
    }
    if (conn.groupsError.isNotBlank()) Text(conn.groupsError, color = t.warning, style = MaterialTheme.typography.bodySmall)
    return
}

