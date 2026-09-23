package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun NewOpenCodeConversationDialog(state: AppState, runtime: LocalRuntimeInstallation, close: () -> Unit, created: (LocalCodexTaskRecord) -> Unit) {
    val tasks = state.localOpenCodeTasks
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(state.localWorkspace.projects.firstOrNull() ?: System.getProperty("user.home")) }
    var title by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<OpenCodeModel>>(emptyList()) }
    var selected by remember { mutableStateOf<OpenCodeModel?>(null) }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    WorkbenchDialog(onDismissRequest = { if (!submitting && !tasks.busy) close() }, title = { Text("本地新建 · OpenCode") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(directory, { directory = it; models = emptyList(); selected = null }, label = { Text("本机工作目录") }, enabled = !tasks.busy && !submitting)
            OutlinedTextField(title, { title = it }, label = { Text("对话名称") }, enabled = !tasks.busy && !submitting)
            TextButton({ submitting = true; scope.launch {
                try { error = ""; models = tasks.models(runtime, directory); selected = models.firstOrNull() }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
                finally { submitting = false }
            } }, enabled = !tasks.busy && !submitting) { Text("读取已连接供应商的模型") }
            if (models.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton({ expanded = true }, enabled = !tasks.busy && !submitting) { Text(selected?.let { "${it.providerId} · ${it.name}" } ?: "选择模型") }
                    DropdownMenu(expanded, { expanded = false }) { models.forEach { model ->
                        DropdownMenuItem(text = { Text("${model.providerId} · ${model.name}") }, onClick = { selected = model; expanded = false })
                    } }
                }
            }
            Text("沿用此 OpenCode 安装的原生供应商登录。创建不会自动发送任务。", style = MaterialTheme.typography.bodySmall)
            if (tasks.recoverySessionId.isNotBlank() || tasks.registry.recoveryReviewRequired) {
                Text("先核对原生历史：${tasks.recoverySessionId.ifBlank { tasks.registry.problem }}")
                Row { Checkbox(reviewed, { reviewed = it }); Text("我已核对原生历史与恢复的索引") }
                TextButton({ runCatching {
                    if (tasks.registry.recoveryReviewRequired) tasks.registry.confirmRecoveryReviewed()
                    if (tasks.recoverySessionId.isNotBlank()) tasks.confirmCreationReviewed()
                }.onFailure { error = it.message.orEmpty() } }, enabled = reviewed && !tasks.busy) { Text("确认核对") }
            } else if (tasks.registry.problem.isNotBlank()) Text(tasks.registry.problem, color = Tokens.current.danger)
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { TextButton(click@{
        if (submitting || tasks.busy) return@click
        submitting = true
        scope.launch {
            try { created(tasks.create(runtime, directory, title, requireNotNull(selected))) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
            finally { submitting = false }
        }
    }, enabled = selected != null && !tasks.busy && !submitting && tasks.recoverySessionId.isBlank() && tasks.registry.problem.isBlank()) { Text("创建对话") } },
        dismissButton = { TextButton(close, enabled = !tasks.busy && !submitting) { Text("取消") } })
}

@Composable internal fun OpenCodeConversationPane(state: AppState, record: LocalCodexTaskRecord) {
    val controller = state.localOpenCodeTasks.controllers[record.key]
    val scope = rememberCoroutineScope()
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    var error by remember(record.key) { mutableStateOf("") }
    fun act(block: suspend () -> Unit) { scope.launch {
        try { error = ""; block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
    } }
    fun send() {
        if (controller == null || !controller.ready || controller.busy || draft.value.text.isBlank()) return
        try { controller.enqueue(draft.value.text); draft.value = TextFieldValue() }
        catch (e: Exception) { error = e.message.orEmpty(); return }
        act { controller.sendNext() }
    }
    LaunchedEffect(controller) {
        if (controller != null) while (true) {
            try { controller.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { Text(record.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton({ state.localSelectedTaskKey = null }) { Text("返回本地") } }
        Text("OpenCode · ${record.provider} · ${record.model}\n${record.directory}", style = MaterialTheme.typography.bodySmall)
        if (controller == null) { Text("此会话未由当前 Yxi 进程持有。原生会话与本地登记已保留，跨重启接管仍待接入。"); return@Column }
        Text(controller.note)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(controller.messages, key = { it.getJSONObject("info").getString("id") }) { message ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(message.getJSONObject("info").optString("role"), style = MaterialTheme.typography.labelSmall)
                    val parts = message.getJSONArray("parts")
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        SelectionContainer { Text(if (part.optString("type") in setOf("text", "reasoning")) part.optString("text") else part.toString(2)) }
                    }
                } }
            }
            items(controller.permissions, key = { "permission:${it.getString("id")}" }) { request ->
                OutlinedCard { Column(Modifier.padding(12.dp)) {
                    Text("需要审批：${request.optString("permission")}")
                    SelectionContainer { Text(request.toString(2)) }
                    Row { OpenCodePermissionReply.entries.forEach { reply -> TextButton({ act { controller.replyPermission(request.getString("id"), reply) } }) { Text(reply.label) } } }
                } }
            }
            items(controller.questions, key = { "question:${it.getString("id")}" }) { request -> OpenCodeQuestions(request, controller, ::act) }
        }
        val pending = state.instructions.entries.filter { it.taskKey == record.key && it.status in setOf(InstructionStatus.Local, InstructionStatus.Unknown) }
        pending.forEach { Text("${if (it.status == InstructionStatus.Local) "待发送" else "待核对"}：${it.text.take(100)}") }
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        OutlinedTextField(draft.value, { draft.value = it }, Modifier.fillMaxWidth().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && draft.value.composition == null && (it.key == Key.Enter || it.key == Key.NumPadEnter) && !it.isShiftPressed) { send(); true } else false
        }, minLines = 2, maxLines = 5, placeholder = { Text("Enter 发送，Shift+Enter 换行") })
        Row {
            Button(::send, enabled = controller.ready && !controller.busy && draft.value.text.isNotBlank()) { Text("发送") }
            TextButton({ act { controller.sendNext() } }, enabled = pending.any { it.status == InstructionStatus.Local } && !controller.busy) { Text("发送下一条") }
            TextButton({ act { controller.refresh() } }) { Text("核对状态") }
            if (controller.nativeBusy || controller.permissions.isNotEmpty() || controller.questions.isNotEmpty()) TextButton({ act { controller.abort() } }) { Text("停止") }
        }
    }
}

@Composable private fun OpenCodeQuestions(request: JSONObject, controller: OpenCodeTaskController, act: (suspend () -> Unit) -> Unit) {
    val questions = request.getJSONArray("questions")
    val answers = remember(request.getString("id")) { mutableStateMapOf<Int, List<String>>() }
    OutlinedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until questions.length()) {
            val question = questions.getJSONObject(i)
            Text(question.getString("question"))
            val options = question.getJSONArray("options")
            for (j in 0 until options.length()) {
                val option = options.getJSONObject(j); val label = option.getString("label")
                QuietChoice(label in answers[i].orEmpty(), {
                    val old = answers[i].orEmpty()
                    answers[i] = if (!question.optBoolean("multiple")) listOf(label) else if (label in old) old - label else old + label
                }, label = { Text("$label · ${option.optString("description")}") })
            }
            if (question.optBoolean("custom", true)) {
                var custom by remember(request.getString("id"), i) { mutableStateOf("") }
                OutlinedTextField(custom, { custom = it; answers[i] = listOf(it) }, label = { Text("自定义回答") })
            }
        }
        Row {
            TextButton({ act { controller.replyQuestion(request.getString("id"), (0 until questions.length()).map { answers[it].orEmpty() }) } }, enabled = (0 until questions.length()).all { answers[it]?.any { answer -> answer.isNotBlank() } == true }) { Text("提交回答") }
            TextButton({ act { controller.rejectQuestion(request.getString("id")) } }) { Text("拒绝回答") }
        }
    } }
}
