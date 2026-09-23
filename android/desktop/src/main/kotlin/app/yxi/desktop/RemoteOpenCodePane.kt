package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun RemoteOpenCodePane(state: AppState) {
    val conn = state.conn
    if (conn == null) { Text("请先选择服务器"); return }
    val tasks = state.remoteOpenCodeTasks
    val record = tasks.tasks(conn.host).singleOrNull { it.key == state.remoteOpenCodeSelectedKey }
    val t = Tokens.current
    Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
        if (record != null) {
            OpenCodeConversationPane(state, record, tasks.controllers[record.key], "返回 ${conn.host.label}") { state.remoteOpenCodeSelectedKey = null }
        } else {
            val scope = rememberCoroutineScope()
            var directory by remember(conn) { mutableStateOf(state.remoteOpenCodeDirectory) }
            var title by remember(conn) { mutableStateOf("") }
            var models by remember(conn) { mutableStateOf<List<OpenCodeModel>>(emptyList()) }
            var selected by remember(conn) { mutableStateOf<OpenCodeModel?>(null) }
            var busy by remember(conn) { mutableStateOf(false) }
            var error by remember(conn) { mutableStateOf("") }
            var reviewed by remember(conn) { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${conn.host.label} · OpenCode", style = MaterialTheme.typography.headlineMedium)
                Text("使用此服务器的原生供应商配置。选择模型后创建；启动提示词先存为草稿。")
                OutlinedTextField(directory, { directory = it; models = emptyList(); selected = null }, Modifier.fillMaxWidth(), label = { Text("已存在的服务器工作目录") }, enabled = !busy)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("对话名称（可留空）") }, enabled = !busy)
                TextButton(click@{
                    if (busy) return@click
                    busy = true
                    scope.launch {
                        try { error = ""; models = tasks.models(conn, directory); selected = models.firstOrNull() }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
                        finally { busy = false }
                    }
                }, enabled = conn.ssh.isConnected && !busy && !tasks.busy) { Text("读取服务器可用模型") }
                if (models.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ expanded = true }, enabled = !busy) { Text(selected?.let { "${it.providerId} · ${it.name}" } ?: "选择模型") }
                        DropdownMenu(expanded, { expanded = false }) { models.forEach { model -> DropdownMenuItem(text = { Text("${model.providerId} · ${model.name}") }, onClick = { selected = model; expanded = false }) } }
                    }
                }
                if (tasks.registry.problem.isNotBlank()) Text(tasks.registry.problem, color = t.danger)
                if (tasks.recoverySessionId.isNotBlank() || tasks.registry.recoveryReviewRequired) {
                    Text("上次创建或恢复的记录需要核对：${tasks.recoverySessionId}")
                    Row { Checkbox(reviewed, { reviewed = it }); Text("我已核对服务器原生历史与登记") }
                    TextButton({ runCatching {
                        if (tasks.registry.recoveryReviewRequired) tasks.registry.confirmRecoveryReviewed()
                        if (tasks.recoverySessionId.isNotBlank()) tasks.confirmCreationReviewed()
                    }.onFailure { error = it.message.orEmpty() } }, enabled = reviewed && !tasks.busy && !busy) { Text("确认核对") }
                }
                if (error.isNotBlank()) Text(error, color = t.danger)
                Button(click@{
                    if (busy) return@click
                    busy = true
                    val prompt = state.remoteOpenCodePrompt
                    scope.launch {
                        try {
                            val created = tasks.create(conn, directory, title, requireNotNull(selected))
                            state.chatDrafts[created.key] = mutableStateOf(TextFieldValue(prompt))
                            state.remoteOpenCodePrompt = ""; state.remoteOpenCodeSelectedKey = created.key
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
                        finally { busy = false }
                    }
                }, enabled = !busy && !tasks.busy && selected != null && conn.ssh.isConnected && tasks.registry.problem.isBlank() && tasks.recoverySessionId.isBlank()) { Text(if (busy) "处理中…" else "创建对话") }
                Text("已登记对话", style = MaterialTheme.typography.titleMedium)
                tasks.tasks(conn.host).forEach { task -> TextButton({ state.remoteOpenCodeSelectedKey = task.key }) { Text("${task.title} · ${task.model}") } }
            }
        }
    }
}
