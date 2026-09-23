package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun NewLocalConversationDialog(state: AppState, close: () -> Unit, created: (LocalCodexTaskRecord) -> Unit) {
    val workspace = state.localWorkspace
    val tasks = state.localCodexTasks
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(workspace.projects.firstOrNull() ?: System.getProperty("user.home")) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var reviewCreation by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    if (reviewCreation) WorkbenchDialog(onDismissRequest = { reviewCreation = false }, title = { Text("核对上次创建") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("原生会话：${tasks.recoveryThreadId}\n请先在原生 Codex 或只读历史中核对。解除记录只允许下一次新建，不会重发消息或删除原会话。")
            Row { Checkbox(reviewed, { reviewed = it }); Text("我已人工核对上次创建结果") }
        }
    }, confirmButton = { TextButton({
        runCatching { tasks.confirmCreationReviewed(); reviewCreation = false }.onFailure { error = it.message.orEmpty(); reviewCreation = false }
    }, enabled = reviewed && !tasks.busy) { Text("解除创建占用") } }, dismissButton = { TextButton({ reviewCreation = false }) { Text("取消") } })
    WorkbenchDialog(onDismissRequest = { if (!tasks.busy) close() }, title = { Text("在本地新建对话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Codex · 官方订阅", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), label = { Text("本机工作目录") }, singleLine = true, enabled = !tasks.busy)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("对话名称（可留空）") }, singleLine = true, enabled = !tasks.busy)
            TextButton(workspace::refreshOfficialModels, enabled = !workspace.officialModelsLoading && !tasks.busy) { Text(if (workspace.officialModelsLoading) "正在读取模型…" else "读取官方模型") }
            if (workspace.officialModels.isNotEmpty()) CompactModelInput(workspace.officialModelId, workspace.officialModels.map { ProviderModels.Model(it.id) },
                workspace::chooseOfficialModel, "新对话模型", Modifier.fillMaxWidth())
            Text("使用所选本机运行器的官方登录；创建后由你发送第一条消息。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (workspace.officialModelsError.isNotBlank()) Text(workspace.officialModelsError, color = Tokens.current.danger)
            if (tasks.registry.problem.isNotBlank()) Text(tasks.registry.problem, color = Tokens.current.danger)
            if (tasks.recoveryThreadId.isNotBlank()) {
                Text("上次创建结果需要核对：${tasks.recoveryThreadId}", color = Tokens.current.warning)
                TextButton({ reviewed = false; reviewCreation = true }, enabled = !tasks.busy) { Text("已检查原生历史，继续核对") }
            }
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { TextButton(click@{
        if (submitting || tasks.busy) return@click
        submitting = true
        scope.launch {
        error = ""
        try {
            val runtime = workspace.selectedRuntime ?: throw IllegalStateException("请先选择可用的本机 Codex")
            created(tasks.create(runtime, directory.trim(), title, workspace.selectedOfficialModel().id))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "创建未完成" }
        finally { submitting = false }
    } }, enabled = !submitting && !tasks.busy && !workspace.officialModelsLoading && workspace.officialModels.any { it.id == workspace.officialModelId } && tasks.recoveryThreadId.isBlank() && tasks.registry.problem.isBlank()) {
        Text(if (tasks.busy) "正在创建…" else "创建对话")
    } }, dismissButton = { TextButton(close, enabled = !tasks.busy) { Text("取消") } })
}

@Composable internal fun LocalConversationPane(state: AppState, record: LocalCodexTaskRecord) {
    val controller = state.localCodexTasks.controllers[record.key]
    val scope = rememberCoroutineScope()
    var error by remember(record.key) { mutableStateOf("") }
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    fun act(block: suspend () -> Unit) { scope.launch {
        try { error = ""; block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "操作未完成" }
    } }
    fun submitDraft() {
        val text = draft.value.text.takeIf { it.isNotBlank() } ?: return
        if (controller == null || !controller.ready || controller.sending) return
        try {
            state.instructions.enqueue(record.key, text)
            draft.value = TextFieldValue()
        } catch (e: Exception) { error = e.message ?: "草稿未能保存，未发送"; return }
        act { if (controller.activeTurnId == null && controller.pendingRequests.isEmpty()) controller.sendNext() }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text(record.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton({ state.localSelectedTaskKey = null }) { Text("返回本地工作台") }
        }
        Text("${record.directory}\n创建配置：官方订阅 · ${record.model}", color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
        if (controller == null) {
            Text("此会话当前未由 Yxi 持有。核对原生历史后再继续，不自动接管原应用会话。")
            TextButton({
                state.localSelectedTaskKey = null
                state.localWorkspace.openThread(NativeHistoryThread(record.threadId, record.title, record.directory, "openai", "appServer", record.createdAt / 1000, "unknown"))
            }, enabled = !state.localWorkspace.loading && state.localWorkspace.selectedRuntime?.home?.let { java.io.File(it).canonicalPath } == record.runtimeHome) { Text("只读查看历史") }
            return@Column
        }
        Text(controller.note, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(controller.messages, key = { it.id }) { message ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(message.author, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                    SelectionContainer { Text(message.text) }
                } }
            }
            items(controller.pendingRequests.values.toList(), key = { it.get("id").toString() }) { request ->
                val params = request.optJSONObject("params") ?: JSONObject()
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("需要你处理", style = MaterialTheme.typography.titleSmall)
                    when (request.optString("method")) {
                        "item/tool/requestUserInput" -> CodexQuestionForm(controller, request)
                        "item/permissions/requestApproval" -> CodexPermissionRequest(params, controller.ready) { response -> act { controller.answerRequest(request.get("id"), response) } }
                        "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                            SelectionContainer { Text(params.optString("command").ifBlank { params.optString("reason").ifBlank { "运行器请求修改文件" } }) }
                            var details by remember(request) { mutableStateOf(false) }
                            TextButton({ details = !details }) { Text(if (details) "收起详情" else "查看请求详情") }
                            if (details) SelectionContainer { Text(params.toString(2)) }
                            Row { TextButton({ act { controller.answerRequest(request.get("id"), JSONObject().put("decision", "accept")) } }, enabled = controller.ready) { Text("仅允许本次") }
                                TextButton({ act { controller.answerRequest(request.get("id"), JSONObject().put("decision", "decline")) } }, enabled = controller.ready) { Text("拒绝") } }
                        }
                        else -> Text("此类请求尚未接入，可停止当前轮次后调整任务。")
                    }
                } }
            }
        }
        val pending = state.instructions.entries.filter { it.taskKey == record.key && it.status in setOf(InstructionStatus.Local, InstructionStatus.Delivering, InstructionStatus.Unknown) }
        pending.forEach { instruction -> Text(when (instruction.status) {
            InstructionStatus.Local -> "待发送：${instruction.text.take(100)}"
            InstructionStatus.Delivering -> "正在投递，请勿重复发送"
            else -> "投递结果未确认，请先核对原生会话"
        }, style = MaterialTheme.typography.bodySmall) }
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        OutlinedTextField(draft.value, { draft.value = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text("给本地 Agent 的任务") })
        Row {
            Button(::submitDraft, enabled = draft.value.text.isNotBlank() && controller.ready && !controller.sending) { Text(if (controller.activeTurnId == null) "发送" else "加入队列") }
            if (pending.any { it.status == InstructionStatus.Local }) TextButton({ act { controller.sendNext() } }, enabled = controller.ready && !controller.sending && controller.activeTurnId == null && controller.pendingRequests.isEmpty()) { Text("发送下一条") }
            TextButton({ act { controller.reconcile() } }, enabled = !controller.sending && state.instructions.entries.any { it.taskKey == record.key && it.status != InstructionStatus.Local }) { Text("核对状态") }
            if (controller.activeTurnId != null) TextButton({ act { controller.interrupt() } }) { Text("停止") }
        }
    }
}
