package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SidebarSavedSessions(state: AppState, hosts: List<Host>, query: String, openFavorite: (Conn, FavoriteLaunch) -> Unit) {
    val nav = state.navigation
    val t = Tokens.current
    var choice by remember { mutableStateOf(Store.pref("sidebarSavedKind", "置顶").takeIf { it in listOf("置顶", "收藏") } ?: "置顶") }
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
    val scopedHosts = hosts.filter { state.hostScope.isEmpty() || state.hostScope == it.id }
    var count = 0
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        val conn = state.conns.firstOrNull { it.host == host }
        val live = conn?.sessions.orEmpty().filter { session ->
            val key = taskNavigationKey(host, session)
            (if (choice == "置顶") nav.pinned(key) else nav.favorite(key)) && nav.visible(key, session.state) &&
                listOf(nav.title(key).orEmpty(), session.short, session.cwd, host.label).any { it.contains(query, true) }
        }.sortedBy { nav.pinOrder(taskNavigationKey(host, it)) }
        live.forEach { session ->
            count++
            SavedSessionRow(nav.title(taskNavigationKey(host, session)) ?: session.short, host.label + " · " + session.cwd, conn?.ssh?.isConnected == true) { if (conn != null) state.select(conn, session) }
        }
        if (choice == "收藏" && nav.mode == "全部") nav.favorites(host).filter { favorite ->
            conn?.sessions?.none { taskNavigationKey(host, it) == favorite.key } != false &&
                listOf(favorite.title, favorite.directory, host.label).any { it.contains(query, true) }
        }.forEach { favorite ->
            count++
            SavedSessionRow(favorite.title, host.label + " · " + if (conn?.ssh?.isConnected == true) "启动入口" else "未连接", conn?.ssh?.isConnected == true) { if (conn != null) openFavorite(conn, favorite) }
        }
    }
    if (count == 0) Text(if (query.isNotBlank()) "没有匹配的${choice}会话" else "暂无${choice}会话，可在任务菜单中添加", Modifier.padding(16.dp, 6.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), color = t.border)
}

@Composable
private fun SavedSessionRow(title: String, subtitle: String, enabled: Boolean, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open).padding(horizontal = 18.dp, vertical = 7.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Tokens.current.textPrimary else Tokens.current.textMuted)
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
    }
}
