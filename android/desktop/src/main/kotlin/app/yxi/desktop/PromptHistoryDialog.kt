package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PromptHistoryDialog(queue: InstructionQueue, taskKey: String, hasDraft: Boolean, close: () -> Unit, choose: (String, Boolean) -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf<String?>(null) }
    val rows = queue.entries.asReversed().filter { it.taskKey == taskKey &&
        (it.text.isNotBlank() || it.attachments.isNotEmpty()) &&
        (it.text.contains(query, ignoreCase = true) || it.attachments.any { attachment -> attachment.name.contains(query, ignoreCase = true) }) }
    WorkbenchDialog(onDismissRequest = close, title = { Text("本任务输入历史") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索之前的提示词") })
            Text("追加会保留草稿；替换需确认。两种操作都不会自动发送。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rows.isEmpty()) item { Text("没有匹配的输入记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.id }) { item ->
                    var expanded by remember(item.id) { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectionContainer { Text(item.text.ifBlank { "仅附件输入" }, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                            TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "查看全文") }
                            Text(when(item.status) {
                                InstructionStatus.Local -> "本地待发送"
                                InstructionStatus.Delivering -> "投递中"
                                InstructionStatus.Unknown -> "结果待确认"
                                InstructionStatus.Accepted -> when (item.runtimeTurnState) {
                                    RuntimeTurnState.None -> "已确认接收"
                                    RuntimeTurnState.InProgress -> "本轮进行中"
                                    RuntimeTurnState.Completed -> "本轮已完成"
                                    RuntimeTurnState.Failed -> "本轮失败"
                                    RuntimeTurnState.Interrupted -> "本轮已中断"
                                }
                                InstructionStatus.Sent -> "已投递到终端"
                                InstructionStatus.Cancelled -> "已撤回"
                                InstructionStatus.Resolved -> "已人工处理"
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.detail.isNotBlank()) Text(item.detail, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (item.attachments.isNotEmpty()) Text(item.attachments.joinToString("、") { it.name } + "\n历史附件需重新添加，仅复用文字。", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton({ choose(item.text, false) }, enabled = item.text.isNotBlank()) { Text("追加到输入框") }
                                TextButton({ if (hasDraft) replacing = item.text else choose(item.text, true) }, enabled = item.text.isNotBlank()) { Text("替换草稿") }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
    replacing?.let { text -> WorkbenchDialog(onDismissRequest = { replacing = null }, title = { Text("替换当前草稿？") },
        text = { Text("当前输入框的文字将被所选提示词替换，附件保持不变。") },
        confirmButton = { TextButton({ replacing = null; choose(text, true) }) { Text("替换") } },
        dismissButton = { TextButton({ replacing = null }) { Text("取消") } }) }
}
