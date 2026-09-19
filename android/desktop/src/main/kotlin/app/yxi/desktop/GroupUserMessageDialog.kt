package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import app.yxi.ssh.Shell
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun GroupUserMessageDialog(state: AppState, conn: Conn, target: Session, group: String, replyTo: String = "", close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = remember { UUID.randomUUID().toString() }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val current = conn.sessions.firstOrNull { it.name == target.name && it.runtimeId == target.runtimeId }
    WorkbenchDialog(onDismissRequest = { if (!sending) close() }, title = { Text("发消息给 ${target.short}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${conn.host.label} · $group\n${target.cwd}", style = MaterialTheme.typography.bodySmall)
            if (replyTo.isNotBlank()) Text("回复原消息：$replyTo", style = MaterialTheme.typography.bodySmall)
            Text("以用户身份直接投递到该成员，协作记录保留消息编号。需要服务器安装新版 yxi-hub。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, enabled = !attempted, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("消息") })
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton({
            sending = true; attempted = true
            scope.launch {
                try {
                    check(current != null && target.runtimeId.isNotBlank()) { "原任务实例已变化" }
                    val key = "hub-user:" + taskNavigationKey(conn.host, target)
                    val entry = state.instructions.enqueue(key, text.trim(), id = id, assignmentGroup = group, assignmentHost = projectKey(conn.host, "/"))
                    state.instructions.beginDelivery(entry.id, entry.revision)
                    val args = (listOf(group, target.name, target.runtimeId, text.trim(), id) + if (replyTo.isNotBlank()) listOf(replyTo) else emptyList()).joinToString(" ", transform = Shell::q)
                    val action = if (replyTo.isBlank()) "user-say" else "user-reply"
                    val result = conn.ssh.exec("\"\$HOME/.local/bin/yxi-hub\" $action $args")
                    check(result.lineSequence().any { it == "__YXI_USER_MESSAGE__:$id" }) { "未取得投递回执，请查看协作记录和目标任务，不要重复发送" }
                    val latest = state.instructions.entries.first { it.id == id }
                    state.instructions.confirmAccepted(id, latest.revision, "yxi-hub 已写入目标终端；尚无处理回执")
                    note = "已写入目标终端，尚无处理回执。消息编号：$id"
                } catch (e: Exception) {
                    state.instructions.entries.firstOrNull { it.id == id && it.status == InstructionStatus.Local }?.let {
                        runCatching { state.instructions.cancel(id, it.revision) }
                    }
                    state.instructions.entries.firstOrNull { it.id == id && it.status == InstructionStatus.Delivering }?.let {
                        runCatching { state.instructions.markUnknown(id, it.revision, "用户协作消息投递未确认，请核对原消息 $id") }
                    }
                    note = "${e.message.orEmpty()}\n消息编号：$id"
                    if (e is kotlinx.coroutines.CancellationException) throw e
                } finally { sending = false }
            }
        }, enabled = !attempted && text.isNotBlank() && current != null && conn.ssh.isConnected) { Text(if (sending) "正在投递…" else "发送用户消息") }
    }, dismissButton = { TextButton(close, enabled = !sending) { Text("关闭") } })
}
