package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
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
    val visible = sessions.filter { nav.visible(taskNavigationKey(conn.host, it), it.state) }
    val pinned = if (nav.mode == "归档") emptyList() else visible.filter { nav.pinned(taskNavigationKey(conn.host, it)) }.sortedBy { nav.pinOrder(taskNavigationKey(conn.host, it)) }
    val pinKeys = pinned.map { taskNavigationKey(conn.host, it) }
    @Composable fun task(s: Session) {
        val key = taskNavigationKey(conn.host, s)
        var menu by remember(key) { mutableStateOf(false) }
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
        if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text("修改任务显示名称") },
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
    val sections = projectSections(visible.filterNot { it in pinned })
    if (sections.isNotEmpty()) Text(if (nav.mode == "归档") "已归档项目任务" else "项目", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp), color = t.textMuted, style = MaterialTheme.typography.labelSmall)
    sections.forEach { group ->
        val key = projectKey(conn.host, group.path)
        val hasSelected = state.conn === conn && group.sessions.any { it.name == state.session?.name }
        val closed = !searching && nav.collapsed(key, default = !hasSelected)
        Column {
            Row(Modifier.fillMaxWidth().clickable { nav.setCollapsed(key, !closed) }.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (closed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, if (closed) "展开项目" else "收起项目", Modifier.size(15.dp), tint = t.textMuted)
                Icon(Icons.Outlined.Folder, null, Modifier.padding(horizontal = 6.dp).size(16.dp), tint = t.textSecondary)
                Column(Modifier.weight(1f)) {
                    Text(group.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (sections.count { it.label == group.label } > 1) Text(group.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                }
                Text(group.sessions.size.toString(), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
            }
            if (!closed) group.sessions.forEach { task(it) }
        }
    }
    if (visible.isEmpty() && conn.sessions.isNotEmpty()) Text("当前筛选下没有任务", Modifier.padding(18.dp, 10.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
}
