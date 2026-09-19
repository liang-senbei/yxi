package app.yxi.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CodexTaskPicker(state: AppState, tasks: List<CodexTaskRecord>, busy: Boolean, onSelect: (CodexTaskRecord) -> Unit) {
    val navigation = state.navigation
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<CodexTaskRecord?>(null) }
    var name by remember { mutableStateOf("") }
    NativeOverlay(menu != null)
    val visible = tasks.filter { task ->
        navigation.archived(task.key) == showArchived && (query.isBlank() ||
            (navigation.title(task.key) ?: task.title).contains(query, true) || task.directory.contains(query, true))
    }.sortedWith(compareBy<CodexTaskRecord> { !navigation.pinned(it.key) }
        .thenBy { navigation.pinOrder(it.key) }.thenByDescending { it.createdAt })
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("搜索任务或项目路径") })
            FilterChip(showArchived, { showArchived = !showArchived }, label = { Text("已归档") }, modifier = Modifier.padding(start = 8.dp))
        }
        if (navigation.error.isNotBlank()) Text(navigation.error, color = Tokens.current.danger, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(state.codexSelectedTaskKey == task.key, { onSelect(task) }, enabled = !busy,
                        label = { Text((if (navigation.pinned(task.key)) "★ " else "") + (navigation.title(task.key) ?: task.title),
                            Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    Box {
                        TextButton({ menu = task.key }) { Text("⋯") }
                        DropdownMenu(menu == task.key, { menu = null }) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { name = navigation.title(task.key) ?: task.title; rename = task; menu = null })
                            DropdownMenuItem(text = { Text(if (navigation.pinned(task.key)) "取消置顶" else "置顶") }, onClick = { navigation.togglePin(task.key); menu = null })
                            DropdownMenuItem(text = { Text(if (showArchived) "恢复到任务列表" else "归档到本机列表") }, onClick = {
                                navigation.setArchived(task.key, !showArchived); menu = null
                            })
                        }
                    }
                }
            }
        }
        if (visible.isEmpty() && tasks.isNotEmpty()) Text("没有符合条件的任务", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
    }
    rename?.let { task ->
        WorkbenchDialog(onDismissRequest = { rename = null }, title = { Text("重命名任务") }, text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("本机显示名称") })
        }, confirmButton = {
            TextButton({ navigation.rename(task.key, name); if (navigation.error.isBlank()) rename = null }, enabled = name.trim().length <= 160) { Text("保存") }
        }, dismissButton = { TextButton({ rename = null }) { Text("取消") } })
    }
}
