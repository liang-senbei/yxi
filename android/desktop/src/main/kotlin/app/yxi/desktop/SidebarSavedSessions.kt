package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** [SidebarSavedSessions] 的外观槽：null 画经典样式；Code 侧栏传 [CodeSavedLook]。筛选、排序、打开和「置顶 / 收藏」的选择
 *  （存 sidebarSavedKind）都在 SidebarSavedSessions 里，两种风格共用（PRD §4.2）。 */
internal class SavedLook(
    val header: @Composable (choice: String, choose: (String) -> Unit) -> Unit,
    val row: @Composable (SavedRow) -> Unit,
    val note: @Composable (text: String, warning: Boolean) -> Unit,
    val end: @Composable () -> Unit,
)

/** 置顶 / 收藏区的一行：[key] 是导航键（Code 按它定位按位置记的行状态），[host] 是主机名（这个列表跨主机），[state] 画状态点。 */
internal class SavedRow(val key: String, val title: String, val host: String, val state: SessionState, val enabled: Boolean, val selected: Boolean, val onClick: () -> Unit)

@Composable
internal fun SidebarSavedSessions(state: AppState, hosts: List<Host>, query: String, look: SavedLook? = null, openFavorite: (Conn, FavoriteLaunch) -> Unit) {
    val nav = state.navigation
    val scope = rememberCoroutineScope()
    var openError by remember { mutableStateOf("") }
    val t = Tokens.current
    var choice by remember { mutableStateOf(Store.pref("sidebarSavedKind", "置顶").takeIf { it in listOf("置顶", "收藏") } ?: "置顶") }
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
    val scopedHosts = hosts.filter { state.hostScope.isEmpty() || state.hostScope == it.id }
    var count = 0
    val choose: (String) -> Unit = { value -> choice = value; Store.setPref("sidebarSavedKind", value) }
    if (look != null) look.header(choice, choose) else Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton({ menu = true }) { Text("$choice ⌄", style = MaterialTheme.typography.labelMedium, color = t.textMuted) }
            DropdownMenu(menu, { menu = false }) {
                listOf("置顶", "收藏").forEach { value ->
                    DropdownMenuItem(text = { Text(value) }, onClick = { choice = value; Store.setPref("sidebarSavedKind", value); menu = false })
                }
            }
        }
    }
    scopedHosts.forEach { host ->
        val conn = state.conns.firstOrNull { it.host.id == host.id && DocumentEndpoint.of(it.host) == DocumentEndpoint.of(host) }
        val live = conn?.sessions.orEmpty().filter { session ->
            val key = taskNavigationKey(host, session)
            (if (choice == "置顶") nav.pinned(key) else nav.favorite(key)) && nav.visible(key, session.state) &&
                listOf(nav.title(key).orEmpty(), session.short, session.cwd, host.label).any { it.contains(query, true) }
        }.sortedBy { nav.pinOrder(taskNavigationKey(host, it)) }
        live.forEach { session ->
            count++
            SavedSessionRow(look, taskNavigationKey(host, session), host.label, session.state, nav.title(taskNavigationKey(host, session)) ?: session.short, host.label + " · " + session.cwd, conn?.ssh?.isConnected == true,
                selected = state.page == Page.Workspace && state.conn === conn && state.session?.runtimeId == session.runtimeId) { if (conn != null) state.select(conn, session) }
        }
        state.codexWorkspace.tasks(host).filter { task ->
            val controller = state.codexWorkspace.controllers[task.key]
            val attention = controller?.pendingRequests?.isNotEmpty() == true
            (if (choice == "置顶") nav.pinned(task.key) else nav.favorite(task.key)) &&
                when (nav.mode) {
                    "归档" -> nav.archived(task.key)
                    "待处理" -> attention
                    else -> !nav.archived(task.key) || attention
                } && listOf(nav.title(task.key).orEmpty(), task.title, task.directory, host.label).any { it.contains(query, true) }
        }.sortedBy { nav.pinOrder(it.key) }.forEach { task ->
            count++
            SavedSessionRow(look, task.key, host.label, codexTaskState(state, task), nav.title(task.key) ?: task.title, host.label + " · Codex · " + task.directory, conn != null,
                selected = state.page == Page.Codex && state.conn === conn && state.codexSelectedTaskKey == task.key) {
                if (conn != null) {
                    openError = ""
                    state.select(conn, null)
                    state.codexSelectedTaskKey = task.key
                    state.page = Page.Codex
                    if (conn.ssh.isConnected && !state.codexWorkspace.busy && state.codexWorkspace.controllers[task.key] == null) scope.launch {
                        try { state.codexWorkspace.open(conn, task) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { openError = "任务连接未完成：${e.message.orEmpty()}" }
                    }
                }
            }
        }
        state.remoteOpenCodeTasks.tasks(host).filter { task ->
            (if (choice == "置顶") nav.pinned(task.key) else nav.favorite(task.key)) && nav.visible(task.key, openCodeTaskState(state, task)) &&
                listOf(nav.title(task.key).orEmpty(), task.title, task.directory, host.label).any { it.contains(query, true) }
        }.sortedBy { nav.pinOrder(it.key) }.forEach { task ->
            count++
            SavedSessionRow(look, task.key, host.label, openCodeTaskState(state, task), nav.title(task.key) ?: task.title, "${host.label} · OpenCode · ${task.directory}", conn != null,
                selected = state.page == Page.OpenCode && state.conn === conn && state.remoteOpenCodeSelectedKey == task.key) {
                if (conn != null) { state.select(conn, null); state.remoteOpenCodeSelectedKey = task.key; state.page = Page.OpenCode }
            }
        }
        state.remoteAcpTasks.tasks(host).filter { task ->
            (if (choice == "置顶") nav.pinned(task.key) else nav.favorite(task.key)) && nav.visible(task.key, acpTaskState(state, task)) &&
                listOf(nav.title(task.key).orEmpty(), task.title, task.directory, host.label, LocalRuntimeDiscovery.title(task.engine)).any { it.contains(query, true) }
        }.sortedBy { nav.pinOrder(it.key) }.forEach { task ->
            count++
            SavedSessionRow(look, task.key, host.label, acpTaskState(state, task), nav.title(task.key) ?: task.title, "${host.label} · ${LocalRuntimeDiscovery.title(task.engine)} · ${task.directory}", conn != null,
                selected = state.page == Page.Acp && state.conn === conn && state.remoteAcpSelectedKey == task.key) {
                if (conn != null) {
                    state.select(conn, null); state.remoteAcpEngine = task.engine; state.remoteAcpSelectedKey = task.key; state.page = Page.Acp
                }
            }
        }
        if (choice == "收藏" && nav.mode == "全部") nav.favorites(host).filter { favorite ->
            conn?.sessions?.none { taskNavigationKey(host, it) == favorite.key } != false &&
                listOf(favorite.title, favorite.directory, host.label).any { it.contains(query, true) }
        }.forEach { favorite ->
            count++
            SavedSessionRow(look, favorite.key, host.label, SessionState.Idle, favorite.title, host.label + " · " + if (conn?.ssh?.isConnected == true) "启动入口" else "未连接", conn?.ssh?.isConnected == true) { if (conn != null) openFavorite(conn, favorite) }
        }
    }
    val empty = if (query.isNotBlank()) "没有匹配的${choice}会话" else "暂无${choice}会话，可在任务菜单中添加"
    if (look != null) {
        if (count == 0) look.note(empty, false)
        if (openError.isNotBlank()) look.note(openError, true)
        look.end()
        return
    }
    if (count == 0) Text(empty, Modifier.padding(16.dp, 6.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    if (openError.isNotBlank()) Text(openError, Modifier.padding(16.dp, 6.dp), style = MaterialTheme.typography.bodySmall, color = t.danger)
    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), color = t.border)
}

/** 一行置顶 / 收藏：[look] 为 null 画经典的两行卡片（标题 + [subtitle]），否则交给 [SavedLook.row]（[navKey]、[host]、[dot] 只给它用）。 */
@Composable
private fun SavedSessionRow(look: SavedLook?, navKey: String, host: String, dot: SessionState, title: String, subtitle: String, enabled: Boolean,
    selected: Boolean = false, open: () -> Unit) {
    if (look != null) { look.row(SavedRow(navKey, title, host, dot, enabled, selected, open)); return }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(9.dp))
        .then(if (selected) Modifier.background(Tokens.current.surface3) else Modifier)
        .clickable(enabled = enabled, onClick = open).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Tokens.current.textPrimary else Tokens.current.textMuted)
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
    }
}
