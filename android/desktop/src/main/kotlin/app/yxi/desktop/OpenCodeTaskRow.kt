package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.SessionState

internal fun openCodeTaskState(state: AppState, task: LocalCodexTaskRecord): SessionState {
    val controller = state.remoteOpenCodeTasks.controllers[task.key]
    val pending = state.instructions.entries.filter { it.taskKey == task.key }
    return when {
        controller?.permissions?.isNotEmpty() == true || controller?.questions?.isNotEmpty() == true || pending.any { it.status == InstructionStatus.Unknown } -> SessionState.NeedsYou
        controller?.nativeBusy == true || controller?.busy == true || pending.any { it.status == InstructionStatus.Delivering || it.runtimeTurnState == RuntimeTurnState.InProgress } -> SessionState.Working
        else -> SessionState.Idle
    }
}

@Composable internal fun OpenCodeTaskRow(state: AppState, conn: Conn, task: LocalCodexTaskRecord) {
    RemoteNativeTaskRow(state, conn, task, false)
}

internal fun acpTaskState(state: AppState, task: LocalCodexTaskRecord): SessionState {
    val controller = state.remoteAcpTasks.controllers[task.key]
    val pending = state.instructions.entries.filter { it.taskKey == task.key }
    return when {
        controller?.pendingApprovals?.isNotEmpty() == true || pending.any { it.status == InstructionStatus.Unknown } -> SessionState.NeedsYou
        controller?.busy == true || controller?.changingMode == true || pending.any { it.status == InstructionStatus.Delivering || it.runtimeTurnState == RuntimeTurnState.InProgress } -> SessionState.Working
        else -> SessionState.Idle
    }
}

@Composable internal fun AcpTaskRow(state: AppState, conn: Conn, task: LocalCodexTaskRecord) {
    RemoteNativeTaskRow(state, conn, task, true)
}

@Composable private fun RemoteNativeTaskRow(state: AppState, conn: Conn, task: LocalCodexTaskRecord, acp: Boolean) {
    val nav = state.navigation; val t = Tokens.current
    var menu by remember(task.key) { mutableStateOf(false) }
    var rename by remember(task.key) { mutableStateOf(false) }
    var title by remember(task.key, rename) { mutableStateOf(nav.title(task.key) ?: task.title) }
    val status = if (acp) acpTaskState(state, task) else openCodeTaskState(state, task)
    val selected = state.conn === conn && if (acp) state.page == Page.Acp && state.remoteAcpSelectedKey == task.key
        else state.page == Page.OpenCode && state.remoteOpenCodeSelectedKey == task.key
    val ready = if (acp) state.remoteAcpTasks.controllers[task.key]?.ready == true else state.remoteOpenCodeTasks.controllers[task.key]?.ready == true
    NativeOverlay(menu)
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) t.selected else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable {
            state.select(conn, null)
            if (acp) { state.remoteAcpEngine = task.engine; state.remoteAcpSelectedKey = task.key; state.page = Page.Acp }
            else { state.remoteOpenCodeSelectedKey = task.key; state.page = Page.OpenCode }
        }.padding(vertical = 8.dp, horizontal = 8.dp)) {
            Text(nav.title(task.key) ?: task.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(LocalRuntimeDiscovery.title(task.engine) + " · " + when {
                status == SessionState.NeedsYou -> "需要你处理"
                status == SessionState.Working -> "正在运行"
                ready -> "空闲"
                else -> "未连接"
            }, style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        }
        Box {
            IconButton({ menu = true }, Modifier.size(26.dp)) { Icon(Icons.Default.MoreHoriz, "会话操作", Modifier.size(16.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (nav.pinned(task.key)) "取消置顶" else "置顶") }, onClick = { nav.togglePin(task.key); menu = false })
                DropdownMenuItem(text = { Text(if (nav.favorite(task.key)) "取消收藏" else "收藏") }, onClick = { nav.setNativeFavorite(task.key, !nav.favorite(task.key)); menu = false })
                DropdownMenuItem(text = { Text("修改显示名称") }, onClick = { rename = true; menu = false })
                DropdownMenuItem(text = { Text(if (nav.archived(task.key)) "恢复到项目列表" else "归档") }, enabled = status == SessionState.Idle,
                    onClick = { nav.setArchived(task.key, !nav.archived(task.key)); menu = false })
                HorizontalDivider()
                Text("移到项目分组", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                (conn.projectGroups.groups.keys.toList() + "").forEach { group ->
                    DropdownMenuItem(text = { Text(group.ifBlank { "未分组" }) }, onClick = { nav.setGroup(task.key, group); menu = false })
                }
            }
        }
    }
    if (rename) WorkbenchDialog(onDismissRequest = { rename = false }, title = { Text("修改显示名称") }, text = {
        Column { OutlinedTextField(title, { title = it }); if (nav.error.isNotBlank()) Text(nav.error, color = t.danger) }
    }, confirmButton = { TextButton({ nav.rename(task.key, title); if (nav.error.isBlank()) rename = false }) { Text("保存") } },
        dismissButton = { TextButton({ rename = false }) { Text("取消") } })
}
