package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import java.util.UUID

@Composable
internal fun MemberAssignmentDialog(state: AppState, conn: Conn, target: Session, group: String, close: () -> Unit, done: () -> Unit) {
    val id = remember { UUID.randomUUID().toString() }
    val source = remember { state.session?.takeIf { state.conn === conn } }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val current = conn.sessions.firstOrNull { it.name == target.name && it.runtimeId == target.runtimeId }
    WorkbenchDialog(onDismissRequest = close, title = { Text("指派给 ${target.short}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${conn.host.label} · $group\n${target.cwd}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            Text("以你的身份创建目标任务的待发送指令，随后进入该任务处理；成员已有草稿会保留。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp),
                label = { Text("任务要求、文件归属与交付内容") }, placeholder = { Text("例如：负责 docs/ 下的文档，完成后说明改动与需要其他成员配合的事项。") })
            if (current == null) Text("目标任务已不在当前会话列表，请返回后重新选择。", color = Tokens.current.warning)
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { TextButton({
        runCatching {
            check(current != null && target.runtimeId.isNotBlank()) { "目标任务实例尚未确认" }
            val message = "[用户协作指派 · $group]\n指派 ID：$id\n主机：${conn.host.label}\n接收任务：${target.name}\n项目：${target.cwd}\n" +
                (source?.let { "发起时所在任务：${it.name}（用户操作，不代表该 Agent 发言）\n" }.orEmpty()) +
                "\n用户要求：\n${text.trim()}"
            state.instructions.enqueue(taskNavigationKey(conn.host, target), message, id = id,
                assignmentGroup = group, assignmentHost = projectKey(conn.host, "/"), sourceTask = source?.let { taskNavigationKey(conn.host, it) }.orEmpty())
            state.select(conn, target); state.tab = 0
            done()
        }.onFailure { error = it.message.orEmpty() }
    }, enabled = text.isNotBlank() && current != null && target.runtimeId.isNotBlank()) { Text("加入待发送并打开任务") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}
