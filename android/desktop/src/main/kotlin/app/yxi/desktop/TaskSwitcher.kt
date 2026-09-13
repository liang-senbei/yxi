package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TaskSwitcherDialog(state: AppState) {
    var query by remember { mutableStateOf("") }
    var includeArchived by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matches = state.conns.flatMap { c -> c.sessions.map { c to it } }
        .filter { (c, s) ->
            val key = taskNavigationKey(c.host, s)
            val searchable = listOf(state.navigation.title(key).orEmpty(), s.name, s.cwd, c.host.label, c.host.region).joinToString(" ")
            (includeArchived || !state.navigation.archived(key)) && terms.all { searchable.contains(it, ignoreCase = true) }
        }
        .sortedByDescending { (c, s) -> state.navigation.pinned(taskNavigationKey(c.host, s)) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    WorkbenchDialog(
        onDismissRequest = { state.showTaskSwitcher = false },
        title = { Text("切换任务") },
        confirmButton = { TextButton({ state.showTaskSwitcher = false }) { Text("关闭") } },
        text = {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("任务、项目路径、服务器或地区") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && matches.isNotEmpty()) {
                            val (c, s) = matches.first()
                            state.select(c, s); state.showTaskSwitcher = false
                            true
                        } else false
                    })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${matches.size} 个任务", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Checkbox(includeArchived, { includeArchived = it })
                    Text("包含归档", style = MaterialTheme.typography.bodySmall)
                }
                if (matches.isEmpty()) Text("暂无匹配任务，请连接服务器或调整搜索。", color = Tokens.current.textMuted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matches) { (c, s) ->
                        val key = taskNavigationKey(c.host, s)
                        OutlinedCard(Modifier.fillMaxWidth().clickable {
                            state.select(c, s); state.showTaskSwitcher = false
                        }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(state.navigation.title(key) ?: s.short, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(listOf(c.host.label, c.host.region, s.runtimeId,
                                    if (state.navigation.archived(key)) "已归档" else "",
                                    if (state.navigation.pinned(key)) "置顶" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                                Text(s.cwd, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = Tokens.current.textSecondary)
                            }
                        }
                    }
                }
            }
        },
    )
}
