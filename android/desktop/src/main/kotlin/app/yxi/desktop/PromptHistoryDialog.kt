package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PromptHistoryDialog(queue: InstructionQueue, taskKey: String, close: () -> Unit, choose: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows = queue.entries.asReversed().filter { it.taskKey == taskKey && it.text.isNotBlank() && it.text.contains(query, ignoreCase = true) }
    WorkbenchDialog(onDismissRequest = close, title = { Text("本任务输入历史") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索之前的提示词") })
            Text("选择后追加到输入框，保留现有草稿，不会自动发送。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rows.isEmpty()) item { Text("没有匹配的输入记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.id }) { item ->
                    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.text, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(when(item.status) {
                                InstructionStatus.Local -> "本地待发送"
                                InstructionStatus.Delivering -> "投递中"
                                InstructionStatus.Unknown -> "结果待确认"
                                InstructionStatus.Accepted -> "已确认"
                                InstructionStatus.Cancelled -> "已撤回"
                                InstructionStatus.Resolved -> "已人工处理"
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.attachments.isNotEmpty()) Text("历史附件需重新添加，仅复用文字。", style = MaterialTheme.typography.bodySmall)
                            TextButton({ choose(item.text) }) { Text("追加到输入框") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}
