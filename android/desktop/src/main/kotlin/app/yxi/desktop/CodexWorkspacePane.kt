package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun CodexWorkspacePane(state: AppState) {
    val workspace = state.codexWorkspace
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf(state.session?.cwd.orEmpty()) }
    var title by remember { mutableStateOf("") }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            error = ""
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作未完成" }
        }
    }
    val conn = state.conn
    val tasks = conn?.let { workspace.tasks(it.host) }.orEmpty()
    val selected = tasks.firstOrNull { it.key == state.codexSelectedTaskKey }
    val controller = selected?.let { workspace.controllers[it.key] }
    LaunchedEffect(controller, state.instructions.entries.toList()) { controller?.queueChanged() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Codex 任务", style = MaterialTheme.typography.titleLarge)
                Text(conn?.host?.label ?: "请先从左侧选择服务器", color = Tokens.current.textMuted)
            }
            TextButton({ state.page = Page.Workspace }) { Text("返回工作区") }
            Button({ creating = !creating }, enabled = conn?.ssh?.isConnected == true && !workspace.busy) { Text("新任务") }
        }
        if (workspace.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (workspace.registry.error.isNotBlank()) Text(workspace.registry.error, color = Tokens.current.danger)
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        if (workspace.recoveryThreadId.isNotBlank()) SelectionContainer {
            Text("任务已在服务器创建，请保留恢复编号：${workspace.recoveryThreadId}")
        }
        if (creating && conn != null) {
            OutlinedTextField(title, { title = it }, label = { Text("任务名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(directory, { directory = it }, label = { Text("服务器项目目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val target = conn
                    val path = directory.trim()
                    val name = title
                    act {
                        val record = workspace.create(target, path, name)
                        state.codexSelectedTaskKey = record.key
                        creating = false
                    }
                }, enabled = !workspace.busy && directory.startsWith('/')) { Text("创建任务") }
                TextButton({ creating = false }) { Text("取消") }
            }
            Text("使用所选服务器的 Codex 登录和模型配置。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tasks.forEach { task ->
                FilterChip(selected = selected?.key == task.key, onClick = {
                    state.codexSelectedTaskKey = task.key
                    if (conn != null) act { workspace.open(conn, task) }
                }, enabled = !workspace.busy, label = { Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        if (selected == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (tasks.isEmpty()) "从项目目录开始一个任务" else "选择任务，继续上次的工作", color = Tokens.current.textMuted)
            }
        } else {
            Text(selected.directory, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (controller == null || !controller.ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(controller?.note ?: "连接后恢复对话", Modifier.weight(1f))
                    TextButton({ if (conn != null) act { workspace.open(conn, selected) } }, enabled = !workspace.busy && conn?.ssh?.isConnected == true) { Text("连接任务") }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(controller?.messages?.toList().orEmpty(), key = { it.id }) { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(message.author, color = Tokens.current.textMuted, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(message.text) }
                    }
                }
                items(controller?.pendingRequests?.values?.toList().orEmpty(), key = { it.get("id").toString() }) { request ->
                    val method = request.optString("method")
                    val supported = method == "item/commandExecution/requestApproval" || method == "item/fileChange/requestApproval"
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (supported) "需要你的批准" else "运行器需要进一步输入", style = MaterialTheme.typography.titleSmall)
                            val params = request.optJSONObject("params") ?: JSONObject()
                            val description = buildString {
                                if (method == "item/fileChange/requestApproval") append("允许运行器修改文件\n")
                                params.optString("command").takeIf { it.isNotBlank() && it != "null" }?.let { append(it).append('\n') }
                                params.optString("cwd").takeIf { it.isNotBlank() && it != "null" }?.let { append("目录：").append(it).append('\n') }
                                params.optString("reason").takeIf { it.isNotBlank() && it != "null" }?.let { append(it) }
                            }
                            SelectionContainer { Text(description.ifBlank { "请查看请求详情后再决定。" }, style = MaterialTheme.typography.bodySmall) }
                            var details by remember(request) { mutableStateOf(false) }
                            TextButton({ details = !details }) { Text(if (details) "收起详情" else "查看请求详情") }
                            if (details) SelectionContainer { Text(params.toString(2), style = MaterialTheme.typography.bodySmall) }
                            if (supported && controller != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ act { controller.answerRequest(request.get("id"), JSONObject().put("decision", "accept")) } }, enabled = controller.ready) { Text("仅允许本次") }
                                OutlinedButton({ act { controller.answerRequest(request.get("id"), JSONObject().put("decision", "decline")) } }, enabled = controller.ready) { Text("拒绝") }
                            } else Text("此类输入暂未接入，可中断当前轮次后调整任务。", color = Tokens.current.textMuted)
                        }
                    }
                }
            }
            if (controller != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(controller.autoDispatch, { controller.setAutoDispatch(it) }, enabled = controller.ready)
                Text("自动逐轮发送", style = MaterialTheme.typography.bodySmall)
                TextButton({ act { controller.steerNext() } }, enabled = controller.ready && !controller.sending && controller.activeTurnId != null && controller.pendingRequests.isEmpty()) { Text("用下一条引导") }
                TextButton({ act { controller.interrupt() } }, enabled = controller.ready && controller.activeTurnId != null) { Text("中断") }
                Text(controller.note, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            }
            InstructionStrip(state.instructions, selected.key,
                canDeliver = controller?.ready == true && !controller.sending && controller.activeTurnId == null && controller.pendingRequests.isEmpty(),
                onDeliver = { instruction -> if (controller != null) act { controller.sendNext(instruction.id) } })
            val draft = state.chatDrafts.getOrPut(selected.key) { mutableStateOf(TextFieldValue()) }
            OutlinedTextField(draft.value, { draft.value = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                placeholder = { Text("描述任务，或补充下一步要求…") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button({
                    try {
                        state.instructions.enqueue(selected.key, draft.value.text)
                        draft.value = TextFieldValue()
                        controller?.queueChanged()
                        error = ""
                    } catch (e: Exception) { error = e.message.orEmpty() }
                }, enabled = draft.value.text.isNotBlank()) { Text(if (controller?.autoDispatch == true) "发送到队列" else "加入队列") }
            }
        }
    }
}
