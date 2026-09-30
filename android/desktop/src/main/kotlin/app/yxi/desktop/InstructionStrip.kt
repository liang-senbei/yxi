package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun InstructionStrip(queue: InstructionQueue, taskKey: String, canDeliver: Boolean, onDeliver: (QueuedInstruction) -> Unit, onQuery: (suspend (QueuedInstruction) -> String)? = null, compactUnknown: Boolean = false, automatic: Boolean = false, canSteer: Boolean = false, onSteer: ((QueuedInstruction) -> Unit)? = null, onAutomaticChange: ((Boolean) -> Unit)? = null) {
    val t = Tokens.current
    val code = LocalThemeSpec.current.style == UiStyle.Code
    val active = queue.entries.filter { it.taskKey == taskKey && (it.runtimeTurnState == RuntimeTurnState.InProgress || it.status !in setOf(InstructionStatus.Sent, InstructionStatus.Accepted, InstructionStatus.Cancelled, InstructionStatus.Resolved)) }
    var expanded by remember(taskKey) { mutableStateOf(false) }
    var editing by remember(taskKey) { mutableStateOf<QueuedInstruction?>(null) }
    var text by remember(taskKey) { mutableStateOf("") }
    var error by remember(taskKey) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var querying by remember(taskKey) { mutableStateOf(false) }
    var queryResult by remember(taskKey, editing?.id) { mutableStateOf("") }
    var menuId by remember(taskKey) { mutableStateOf<String?>(null) }
    NativeOverlay(menuId != null)
    if (compactUnknown && active.isNotEmpty() && active.all { it.status == InstructionStatus.Unknown } && !expanded && queue.error.isBlank()) {
        if (code) Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.End) {
            CodeTextAction("投递记录 · ${active.size}", { expanded = true })
        } else TextButton({ expanded = true }, Modifier.padding(horizontal = 12.dp)) { Text("投递记录 · ${active.size}", style = MaterialTheme.typography.labelSmall, color = t.textMuted) }
        return
    }
    fun act(block: () -> Unit) { error = ""; runCatching(block).onFailure { error = it.message.orEmpty() } }
    if (queue.error.isNotEmpty()) Text(queue.error, color = t.danger, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (error.isNotBlank() && editing == null) Text(error, color = t.danger, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    // Code 风格（规格 §3.4）：排队项就是对话末尾右对齐的用户消息，下方 8 是 24 高的操作行，步距 36 + 8 + 24 + 15.2 = 83.2；
    // 没有托盘和标题。动作和经典一样，「编辑 / 核对」= 点气泡或 ⋯ 菜单。
    if (code && active.isNotEmpty()) Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.End,
    ) {
        if (active.size > 3 || (compactUnknown && expanded && active.all { it.status == InstructionStatus.Unknown })) {   // 只在点了有变化时给：多于 3 条，或收回「投递记录」
            CodeTextAction(if (expanded) "收起" else "展开全部 · ${active.size}", { expanded = !expanded })
            Spacer(Modifier.height(4.dp))
        }
        active.take(if (expanded) active.size else 3).forEachIndexed { index, item ->
            key(item.id) {
                if (index > 0) Spacer(Modifier.height(15.2.dp))
                CodeHint(if (item.status == InstructionStatus.Local) "点击编辑" else "点击核对状态") {
                    CodeUserBubble(item.text.ifBlank { "${item.attachments.size} 个附件" }, maxLines = 6) { editing = item; text = item.text; error = "" }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    CodeActionNote(instructionState(item, automatic), Modifier.weight(1f, fill = false))
                    if (item.status == InstructionStatus.Local && onSteer != null) CodeTextAction("调整方向", { onSteer(item) }, enabled = canSteer)
                    if (!automatic && item.status == InstructionStatus.Local && index == 0) CodeTextAction("发送", { onDeliver(item) }, enabled = canDeliver)
                    if (item.status == InstructionStatus.Local) CodeIconButton(Icons.Outlined.DeleteOutline, "撤回指令", iconSize = 12.8.dp, tint = t.textMuted, tipAbove = true) {
                        act { queue.cancel(item.id, item.revision) }
                    }
                    Box {
                        CodeIconButton(Icons.Outlined.MoreHoriz, "更多指令操作", iconSize = 12.8.dp, tint = t.textMuted, held = menuId == item.id, tipAbove = true) { menuId = item.id }
                        if (menuId == item.id) CodePopup({ menuId = null }, side = PopupSide.Above, alignEnd = true) {
                            CodeMenuItem(if (item.status == InstructionStatus.Local) "编辑消息" else "查看完整内容", { menuId = null; editing = item; text = item.text; error = "" })
                            if (onAutomaticChange != null) CodeMenuItem(if (automatic) "关闭自动排队" else "开启自动排队", { menuId = null; act { onAutomaticChange(!automatic) } })
                            if (item.status == InstructionStatus.Local) {
                                val localItems = active.filter { it.status == InstructionStatus.Local }
                                val position = localItems.indexOfFirst { it.id == item.id }
                                val previous = localItems.getOrNull(position - 1)
                                val next = localItems.getOrNull(position + 1)
                                CodeMenuDivider()
                                CodeMenuItem("上移", { menuId = null; previous?.let { act { queue.moveBefore(item.id, item.revision, it.id) } } }, enabled = previous != null)
                                CodeMenuItem("下移", { menuId = null; next?.let { act { queue.moveAfter(item.id, item.revision, it.id) } } }, enabled = next != null)
                                CodeMenuItem("移到待发送首位", { menuId = null; act { queue.moveBefore(item.id, item.revision, localItems.first().id) } }, enabled = position > 0)
                            }
                        }
                    }
                }
            }
        }
    } else if (active.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 220.dp)
        .background(t.surface1, RoundedCornerShape(12.dp)).border(0.5.dp, t.border, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (automatic) "待执行" else "指令") + " · ${active.size}", style = MaterialTheme.typography.labelMedium, color = t.textMuted, modifier = Modifier.weight(1f))
            if (active.size > 3 || compactUnknown) TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开全部") }
        }
        androidx.compose.foundation.lazy.LazyColumn {
            items(if (expanded) active.size else minOf(3, active.size), key = { active[it].id }) { index ->
                val item = active[index]
                if (index > 0) HorizontalDivider(color = t.border, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editing = item; text = item.text; error = "" }.padding(vertical = 6.dp)) {
                        Text(item.text.ifBlank { "${item.attachments.size} 个附件" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(instructionState(item, automatic), color = t.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (item.status == InstructionStatus.Local && onSteer != null) TextButton({ onSteer(item) }, enabled = canSteer) { Text("调整方向") }
                    if (!automatic && item.status == InstructionStatus.Local && index == 0) TextButton({ onDeliver(item) }, enabled = canDeliver) { Text("发送") }
                    TextButton({ editing = item; text = item.text; error = "" }) { Text(if (item.status == InstructionStatus.Local) "编辑" else "核对") }
                    if (item.status == InstructionStatus.Local) IconButton({ act {
                        queue.cancel(item.id, item.revision)
                    } }, Modifier.size(32.dp)) { Icon(Icons.Outlined.DeleteOutline, "撤回指令", Modifier.size(16.dp), tint = t.textMuted) }
                    Box {
                        IconButton({ menuId = item.id }, Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreHoriz, "更多指令操作", Modifier.size(16.dp), tint = t.textMuted) }
                        DropdownMenu(menuId == item.id, { menuId = null }) {
                            DropdownMenuItem(text = { Text(if (item.status == InstructionStatus.Local) "编辑消息" else "查看完整内容") }, onClick = { menuId = null; editing = item; text = item.text; error = "" })
                            if (onAutomaticChange != null) DropdownMenuItem(
                                text = { Text(if (automatic) "关闭自动排队" else "开启自动排队") },
                                onClick = { menuId = null; act { onAutomaticChange(!automatic) } },
                            )
                            if (item.status == InstructionStatus.Local) {
                                val localItems = active.filter { it.status == InstructionStatus.Local }
                                val position = localItems.indexOfFirst { it.id == item.id }
                                val previous = localItems.getOrNull(position - 1)
                                val next = localItems.getOrNull(position + 1)
                                DropdownMenuItem(text = { Text("上移") }, enabled = previous != null, onClick = { menuId = null; previous?.let { act { queue.moveBefore(item.id, item.revision, it.id) } } })
                                DropdownMenuItem(text = { Text("下移") }, enabled = next != null, onClick = { menuId = null; next?.let { act { queue.moveAfter(item.id, item.revision, it.id) } } })
                                DropdownMenuItem(text = { Text("移到待发送首位") }, enabled = position > 0, onClick = { menuId = null; act { queue.moveBefore(item.id, item.revision, localItems.first().id) } })
                            }
                        }
                    }
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
                if (!current?.deliveryObservation.isNullOrBlank()) {
                    Text("最近查询 · ${java.time.Instant.ofEpochMilli(current.deliveryObservedAt)}", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                    Text(current.deliveryObservation, style = MaterialTheme.typography.bodySmall)
                }
                if (local) Row {
                    TextButton({ act {
                        queue.cancel(original.id, original.revision)
                        editing = null
                    } }) { Text("撤回") }
                    val previous = active.takeWhile { it.id != original.id }.lastOrNull { it.status == InstructionStatus.Local }
                    if (previous != null) TextButton({ act { queue.moveBefore(original.id, original.revision, previous.id); editing = null } }) { Text("上移") }
                }
                if (current?.status == InstructionStatus.Unknown) Text("请先查看对话或终端确认实际结果。解除阻塞不会重新发送，也不会标记为运行器已接收。", style = MaterialTheme.typography.bodySmall)
                if (current?.status == InstructionStatus.Unknown && onQuery != null) {
                    TextButton({
                        querying = true
                        scope.launch {
                            try {
                                val observation = onQuery(current)
                                queue.recordDeliveryObservation(current.id, current.revision, observation)
                                queryResult = ""
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { queryResult = "查询或记录保存未完成：${e.message}" }
                            finally { querying = false }
                        }
                    }, enabled = !querying) { Text(if (querying) "查询中…" else "查询投递记录") }
                    if (queryResult.isNotBlank()) Text(queryResult, style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, color = t.danger)
            }
        }, confirmButton = {
            if (local) TextButton({ act { queue.edit(original.id, original.revision, text); editing = null } }, enabled = text.isNotBlank() || original.attachments.isNotEmpty()) { Text("保存") }
            else if (current?.status == InstructionStatus.Unknown) TextButton({ act { queue.resolveManually(current.id, current.revision); editing = null } }) { Text("已人工核对，解除阻塞") }
        }, dismissButton = { TextButton({ editing = null }) { Text("关闭") } })
    }
}

/** 排队项下面那行状态字，两种外观共用。 */
private fun instructionState(item: QueuedInstruction, automatic: Boolean): String =
    when(item.status) { InstructionStatus.Local -> if (automatic) "本轮结束后自动执行" else "本地待发送"; InstructionStatus.Delivering -> "投递中"; InstructionStatus.Accepted -> "运行器已接收 · 本轮进行中"; else -> "状态待确认" } + if (item.attachments.isEmpty()) "" else " · ${item.attachments.size} 个附件"


