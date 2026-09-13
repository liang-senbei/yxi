package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkspaceWelcome(state: AppState) {
    val current = state.conn
    val connected = state.conns.filter { it.status == Conn.Status.Connected }
    val hosts = if (current == null) connected else listOf(current)
    val tasks = hosts.flatMap { c -> c.sessions.map { c to it } }
        .filterNot { (c, task) -> state.navigation.archived(taskNavigationKey(c.host, task)) }
        .sortedByDescending { (c, task) -> state.navigation.pinned(taskNavigationKey(c.host, task)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Yxi 工作台", style = MaterialTheme.typography.labelLarge, color = Tokens.current.accent)
            Text(if (current == null) "开始一项任务" else "${current.host.label} 上的任务", style = MaterialTheme.typography.headlineMedium)
            Text(if (connected.isEmpty()) "连接你的服务器，在项目目录中运行 Agent。" else "${connected.size} 台服务器已连接，选择任务继续工作，或在新目录开始。", style = MaterialTheme.typography.bodyMedium, color = Tokens.current.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ state.newSessionRequest++ }, enabled = connected.isNotEmpty()) { Text("新建任务") }
                OutlinedButton({ state.sidebarOpen = true }) { Text("服务器") }
                TextButton({ state.showTaskSwitcher = true }, enabled = tasks.isNotEmpty()) { Text("切换任务") }
            }
            if (tasks.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("继续工作", style = MaterialTheme.typography.titleSmall)
                tasks.take(6).forEach { (conn, task) ->
                    TextButton({ state.select(conn, task) }, Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.navigation.title(taskNavigationKey(conn.host, task)) ?: task.short, style = MaterialTheme.typography.titleSmall, color = Tokens.current.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${conn.host.label} · ${task.cwd}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(task.state.label, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                    }
                }
            } else if (connected.isNotEmpty()) Text("暂无可继续的任务。点击新建任务，选择运行器与项目目录。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (state.deferredRoute != null || state.deferredRouteNotice.isNotBlank()) DeferredRouteStatus(state)
        }
    }
}
