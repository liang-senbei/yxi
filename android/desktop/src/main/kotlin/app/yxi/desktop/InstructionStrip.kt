package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun InstructionStrip(queue: InstructionQueue, taskKey: String, canDeliver: Boolean, onDeliver: (QueuedInstruction) -> Unit) {
    val t = Tokens.current
    val active = queue.entries.filter { it.taskKey == taskKey && it.status !in setOf(InstructionStatus.Accepted, InstructionStatus.Cancelled, InstructionStatus.Resolved) }
    var expanded by remember(taskKey) { mutableStateOf(false) }
    var editing by remember(taskKey) { mutableStateOf<QueuedInstruction?>(null) }
    var text by remember(taskKey) { mutableStateOf("") }
    var error by remember(taskKey) { mutableStateOf("") }
    fun act(block: () -> Unit) { runCatching(block).onFailure { error = it.message.orEmpty() } }
    if (queue.error.isNotEmpty()) Text(queue.error, color = t.danger, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (active.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 220.dp)
        .background(t.surface1, RoundedCornerShape(12.dp)).border(0.5.dp, t.border, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("待处理 · ${active.size}", style = MaterialTheme.typography.labelMedium, color = t.textMuted, modifier = Modifier.weight(1f))
            if (active.size > 3) TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开全部") }
        }
        androidx.compose.foundation.lazy.LazyColumn {
            items(if (expanded) active.size else minOf(3, active.size), key = { active[it].id }) { index ->
                val item = active[index]
                if (index > 0) HorizontalDivider(color = t.border, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editing = item; text = item.text; error = "" }.padding(vertical = 6.dp)) {
                        Text(item.text.ifBlank { "${item.attachments.size} 个附件" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(when(item.status) { InstructionStatus.Local -> "本地待发送"; InstructionStatus.Delivering -> "投递中"; else -> "状态待确认" } + if (item.attachments.isEmpty()) "" else " · ${item.attachments.size} 个附件", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (item.status == InstructionStatus.Local && index == 0) TextButton({ onDeliver(item) }, enabled = canDeliver) { Text("发送") }
                    TextButton({ editing = item; text = item.text; error = "" }) { Text(if (item.status == InstructionStatus.Local) "编辑" else "核对") }
                }
            }
        }
    }
    editing?.let { original ->
        val current = queue.entries.firstOrNull { it.id == original.id }
        val local = current?.status == InstructionStatus.Local && current.revision == original.revision
        WorkbenchDialog(onDismissRequest = { editing = null }, title = { Text(if (local) "编辑待发送指令" else "核对指令状态") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, readOnly = !local, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), label = { Text("指令正文") })
                original.attachments.forEach { Text(it.name, color = t.textMuted, style = MaterialTheme.typography.bodySmall) }
                Text(current?.detail.orEmpty().ifBlank { "此指令尚未投递，可以编辑、调整顺序或撤回。主输入框草稿不受影响。" }, style = MaterialTheme.typography.bodySmall)
                if (local) Row {
                    TextButton({ act { queue.cancel(original.id, original.revision); editing = null } }) { Text("撤回") }
                    val previous = active.takeWhile { it.id != original.id }.lastOrNull { it.status == InstructionStatus.Local }
                    if (previous != null) TextButton({ act { queue.moveBefore(original.id, original.revision, previous.id); editing = null } }) { Text("上移") }
                }
                if (current?.status == InstructionStatus.Unknown) Text("请先查看对话或终端确认实际结果。解除阻塞不会重新发送，也不会标记为运行器已接收。", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = t.danger)
            }
        }, confirmButton = {
            if (local) TextButton({ act { queue.edit(original.id, original.revision, text); editing = null } }, enabled = text.isNotBlank() || original.attachments.isNotEmpty()) { Text("保存") }
            else if (current?.status == InstructionStatus.Unknown) TextButton({ act { queue.resolveManually(current.id, current.revision); editing = null } }) { Text("已人工核对，解除阻塞") }
        }, dismissButton = { TextButton({ editing = null }) { Text("关闭") } })
    }
}
