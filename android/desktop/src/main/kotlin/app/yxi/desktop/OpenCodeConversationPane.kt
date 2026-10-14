package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun NewOpenCodeConversationDialog(state: AppState, runtime: LocalRuntimeInstallation, close: () -> Unit, initialDirectory: String? = null, created: (LocalCodexTaskRecord) -> Unit) {
    val tasks = state.localOpenCodeTasks
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(initialDirectory ?: state.localWorkspace.projects.firstOrNull() ?: System.getProperty("user.home")) }
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

@Composable internal fun OpenCodeConversationPane(state: AppState, record: LocalCodexTaskRecord,
    controller: OpenCodeTaskController? = state.localOpenCodeTasks.controllers[record.key],
    backLabel: String = "返回本地", back: () -> Unit = { state.localSelectedTaskKey = null }) {
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
    // Code 的停止方块运行中一直能点，所以自己判断；经典的「停止」本来就只在这几种情况下出现
    fun stop() {
        if (controller == null || !(controller.nativeBusy || controller.permissions.isNotEmpty() || controller.questions.isNotEmpty())) return
        act { controller.abort() }
    }
    LaunchedEffect(controller) {
        if (controller != null) while (true) {
            try { controller.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
            delay(2000)
        }
    }
    if (LocalThemeSpec.current.style == UiStyle.Code) {
        // Code 风格（PRD §5.1）：会话头 + 居中的对话列 + 停靠区；状态、函数（act / send / 2 秒轮询）和列表顺序同经典。服务器上的 OpenCode 对话
        // （RemoteOpenCodePane）也走这里（PRD G3），会话头换成主机图标。不放「返回」（同 ACP）；不跟随最新（同经典：每次轮询都换一份 messages，留给 M3）
        // 本机记录进页面自动接回一次（本机对齐 P4，同本机 Codex / ACP 的 Code 对话页）：失败留原因和「重新连接」「重新检测运行器」，不反复重试
        val t = Tokens.current
        val runner = LocalRuntimeDiscovery.title(record.engine)
        val focus = remember(record.key) { FocusRequester() }
        LaunchedEffect(record.key) { runCatching { focus.requestFocus() } }   // 同 ACP：焦点先落输入框（没有输入区时吞掉异常）
        // 服务器上的记录由 RemoteOpenCodeTasks 管连接，这里不碰本机的会话管理和运行器检测
        val local = record.hostKey.isBlank()
        val tasks = if (local) state.localOpenCodeTasks else null
        val workspace = if (local) state.localWorkspace else null
        var connecting by remember(record.key) { mutableStateOf(false) }
        var attempted by remember(record.key) { mutableStateOf(false) }
        // 原数据目录的 OpenCode 运行器（核对同 LocalOpenCodeTasks.open）；有多个时优先当前选中的那个
        val installations = workspace?.installations.orEmpty()
        val matching = remember(record.engine, record.runtimeHome, installations) {
            installations.filter { it.engine == record.engine && runCatching { java.io.File(it.home).canonicalPath == record.runtimeHome }.getOrDefault(false) }
        }
        val runtime = matching.filter { it.ready }.let { found -> found.firstOrNull { it.id == workspace?.selectedRuntime?.id } ?: found.firstOrNull() }
        val unavailable = matching.firstOrNull { !it.ready }
        fun connect(target: LocalRuntimeInstallation) {
            if (tasks == null || connecting || tasks.busy) return
            connecting = true; error = ""
            scope.launch {
                try { tasks.open(target, record.key) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "接回未完成" }
                finally { connecting = false }
            }
        }
        // 运行器检测由侧栏发起，这里不重复扫描
        LaunchedEffect(record.key, runtime?.id, tasks?.busy) {
            if (tasks != null && !attempted && !tasks.busy && tasks.controllers[record.key] == null && runtime != null) { attempted = true; connect(runtime) }
        }
        // 「马上要接回」也算接回中：免得进页面先闪一下「还没接回」
        val opening = local && (connecting || controller == null && !attempted && runtime != null)
        Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
            Column(Modifier.fillMaxSize()) {
                // 「改动」「文件」开着时对话列换成本机的 Git 改动 / 文件视图（本机对齐 P6a / P6b，同服务器 Codex 页），停靠区不变；只给本机记录，服务器 OpenCode 不变
                var tool by remember(record.key) { mutableStateOf<String?>(null) }
                CodeLocalSessionHeader(state.navigation.title(record.key) ?: record.title, record.directory, runner,
                    host = if (record.hostKey.isBlank()) null else state.conn?.host?.label ?: "服务器",
                    end = if (!local) null else ({ CodeLocalToolButtons(tool) { tool = it } }))
                if (local && tool == "changes") Box(Modifier.weight(1f).fillMaxWidth()) { CodeLocalChanges(state, record) { tool = null } }
                else if (local && tool == "files") Box(Modifier.weight(1f).fillMaxWidth()) { LocalFilesPane(record.key, record.directory) }
                else Box(Modifier.weight(1f).fillMaxWidth()) {
                    // M3f：运行中的状态行（对话末尾）；开始时间记在这一层，列表滚动时不会从 0 算起
                    val runningSince = rememberRunningSince(controller?.nativeBusy == true && controller.permissions.isEmpty() && controller.questions.isEmpty())
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(40.dp, 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (controller != null) {
                            // M3b / M3c：每条 OpenCode 消息拆成几项（正文、思考、工具），连着的工具调用跨消息合成摘要行 + 卡片
                            items(Transcripts.rows(controller.messages.flatMap(Transcripts::openCode)), key = { "row:" + it.key }) { row -> CodeColumn(true) { CodeTranscriptLine(row) } }
                            if (runningSince > 0L) item(key = "status") { CodeColumn(true) { CodeStatusLine("正在运行", runningSince) } }
                            items(controller.questions, key = { "question:${it.getString("id")}" }) { request ->
                                CodeColumn(true) { OpenCodeQuestions(request, controller, ::act, Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                    // 接回时列表顶端一条 2 高的进度条（同本机 Codex / ACP）
                    if (opening) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                }
                CodeDock(true) {
                    // 服务器上没有控制器（不是当前进程建的）时只留说明，不画输入区（经典也不画）
                    if (controller == null && !local) {
                        Note("此会话未由当前 Yxi 进程持有。原生会话与本地登记已保留，跨重启接管仍待接入。", t.textMuted)
                        Spacer(Modifier.height(8.dp))   // 同 ACP：说明字和有输入区时页脚的字同高
                    } else if (controller == null) {
                        // 本机还没接回：说明 + 两个 chip，输入区照画但不能发送（同 ACP）
                        Note(when {
                            opening -> "正在接回会话…"
                            runtime != null -> "会话还没接回。"
                            workspace?.detecting == true -> "正在检测本机运行器…"
                            unavailable != null -> "$runner 运行器不可用：" + unavailable.problem.ifBlank { "未检测到版本" }
                            workspace?.scanned != true -> "还没检测本机运行器。"
                            else -> "没有找到使用原数据目录（${record.runtimeHome}）的 $runner 运行器，请重新检测。"
                        }, t.textMuted)
                        // 9.6 = Note 的 16 − chip 的 6.4：chip 的字和上面的提示字对齐
                        Row(Modifier.padding(horizontal = 9.6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CodeFooterChip(if (opening) "正在连接…" else "重新连接", { runtime?.let(::connect) }, enabled = runtime != null && !opening && tasks?.busy != true)
                            CodeFooterChip(if (workspace?.detecting == true) "正在检测…" else "重新检测运行器", { workspace?.refresh() },
                                enabled = workspace?.detecting != true && !connecting)
                        }
                        if (error.isNotBlank()) Note(error, t.danger)
                        CodeLocalComposer(draft.value, { draft.value = it }, hint = if (opening) "正在接回会话…" else "接回会话后可发送",
                            focus = focus, canSend = false, onSend = {}, running = false, onStop = {},
                            end = { CodeFooterNote("${record.provider} · ${record.model}") })
                    } else {
                        // 待发送 / 待核对的指令画成对话末尾的排队气泡（同 Claude）；「发送」只给排第一的待发送项
                        InstructionStrip(state.instructions, record.key, !controller.busy, { act { controller.sendNext() } })
                        Note(controller.note, t.textMuted)
                        if (error.isNotBlank()) Note(error, t.danger)
                        // M3d-2：审批卡停靠在输入卡上方、和输入卡同宽，不进对话滚动区（规格表 §3.3）；下缘到输入卡 6.4
                        controller.permissions.forEach { request -> key(request.getString("id")) { CodeOpenCodeApproval(request, controller, ::act) } }
                        CodeLocalComposer(draft.value, { draft.value = it },
                            // 不看 ready：每次轮询 refresh 都会把它短暂置 false，提示会闪
                            hint = when {
                                controller.permissions.isNotEmpty() -> "先处理上面的审批"
                                controller.questions.isNotEmpty() -> "先回答上面的提问"
                                controller.busy || controller.nativeBusy -> "本轮结束后可发送"
                                else -> "跟它说点什么… Enter 发送，Shift+Enter 换行"
                            },
                            focus = focus, canSend = controller.ready && !controller.busy && draft.value.text.isNotBlank(), onSend = ::send,
                            // 停止方块的条件同经典的「停止」：本轮在跑，或卡在审批 / 提问上
                            running = controller.nativeBusy || controller.permissions.isNotEmpty() || controller.questions.isNotEmpty(), onStop = ::stop,
                            // 「核对状态」照经典保留：轮询成功不会清掉错误，点它（act 先清）才清
                            start = { CodeFooterChip("核对状态", { act { controller.refresh() } }) },
                            end = { CodeFooterNote("${record.provider} · ${record.model}") },   // 照经典副标题
                        )
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { Text(record.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(back) { Text(backLabel) } }
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
            items(controller.permissions, key = { "permission:${it.getString("id")}" }) { request -> OpenCodePermissionCard(request, controller, ::act) }
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

@Composable private fun OpenCodeQuestions(request: JSONObject, controller: OpenCodeTaskController, act: (suspend () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    val questions = request.getJSONArray("questions")
    val answers = remember(request.getString("id")) { mutableStateMapOf<Int, List<String>>() }
    OutlinedCard(modifier) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** 一条待审批（两种风格共用，经典原样）：权限名 + 原始 JSON + 三个回复。Code 下 [modifier] 给满列宽，同 ACP 的审批卡。 */
@Composable private fun OpenCodePermissionCard(request: JSONObject, controller: OpenCodeTaskController, act: (suspend () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) { Column(Modifier.padding(12.dp)) {
        Text("需要审批：${request.optString("permission")}")
        SelectionContainer { Text(request.toString(2)) }
        Row { OpenCodePermissionReply.entries.forEach { reply -> TextButton({ act { controller.replyPermission(request.getString("id"), reply) } }) { Text(reply.label) } } }
    } }
}

/** Code 风格的 OpenCode 审批（M3d-2）：标题「需要审批：权限名」；匹配规则（patterns）放进命令块，没有就放原始请求；「拒绝」在左，「仅允许本次」是主按钮。 */
@Composable private fun CodeOpenCodeApproval(request: JSONObject, controller: OpenCodeTaskController, act: (suspend () -> Unit) -> Unit) {
    val id = request.getString("id")
    fun reply(r: OpenCodePermissionReply) = CodeApprovalAction(r.label, primary = r == OpenCodePermissionReply.Once) { act { controller.replyPermission(id, r) } }
    val patterns = request.optJSONArray("patterns")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
    CodeApprovalCard("需要审批：" + request.optString("permission").ifBlank { "操作" }, reply(OpenCodePermissionReply.Reject),
        listOf(reply(OpenCodePermissionReply.Always), reply(OpenCodePermissionReply.Once)), Modifier.padding(bottom = 6.4.dp)) {
        CodeCommandBlock(if (patterns.isNotEmpty()) patterns.joinToString("\n") else request.toString(2))
    }
}

