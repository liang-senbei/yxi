package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private class AcpPendingAuthentication(val plan: AcpTerminalAuthPlan, val completion: kotlinx.coroutines.CompletableDeferred<Int>)

@Composable internal fun NewAcpConversationDialog(state: AppState, runtime: LocalRuntimeInstallation, dismiss: () -> Unit, initialDirectory: String? = null, created: (LocalCodexTaskRecord) -> Unit) {
    val tasks = state.localAcpTasks
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(initialDirectory ?: System.getProperty("user.home")) }
    var title by remember { mutableStateOf("") }
    var connectedDirectory by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var reviewed by remember(tasks.recoverySessionId) { mutableStateOf(false) }
    var terminalAuthentication by remember { mutableStateOf<AcpPendingAuthentication?>(null) }
    DisposableEffect(tasks) { onDispose { tasks.abandonPreparation() } }
    terminalAuthentication?.let { pending ->
        AcpAuthenticationDialog(pending.plan) { code, message ->
            if (code == null) pending.completion.completeExceptionally(IllegalStateException(message ?: "认证未完成"))
            else pending.completion.complete(code)
        }
        return
    }
    WorkbenchDialog(onDismissRequest = { if (!tasks.busy) dismiss() }, title = { Text("本地 · ${LocalRuntimeDiscovery.title(runtime.engine)}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("对话接入预览 · 使用运行器自己的账号与配置；创建后不会自动发送消息。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(directory, { directory = it }, label = { Text("本机工作目录") }, enabled = !tasks.busy, singleLine = true)
            OutlinedTextField(title, { title = it }, label = { Text("对话名称") }, enabled = !tasks.busy, singleLine = true)
            TextButton({ scope.launch {
                error = ""; connectedDirectory = ""
                runCatching { tasks.prepare(runtime, directory); connectedDirectory = directory }.onFailure { error = it.message.orEmpty() }
            } }, enabled = !tasks.busy && File(directory).isDirectory) { Text("连接运行器") }
            if (connectedDirectory == directory && connectedDirectory.isNotBlank()) {
                Text("已连接。若尚未登录，选择运行器提供的认证方式：", style = MaterialTheme.typography.bodySmall)
                val methods = tasks.initialization?.optJSONArray("authMethods")
                for (index in 0 until (methods?.length() ?: 0)) {
                    val method = methods!!.getJSONObject(index)
                    TextButton({ scope.launch { error = ""; runCatching {
                        if (method.optString("type", "agent") == "terminal") tasks.authenticateTerminal(method.getString("id")) { plan ->
                            val completion = kotlinx.coroutines.CompletableDeferred<Int>()
                            terminalAuthentication = AcpPendingAuthentication(plan, completion)
                            try { completion.await() } finally { terminalAuthentication = null }
                        } else tasks.authenticate(method.getString("id"))
                    }.onFailure { error = it.message.orEmpty(); if (tasks.initialization == null) connectedDirectory = "" } } }, enabled = !tasks.busy) {
                        Text(method.optString("name").ifBlank { method.getString("id") })
                    }
                }
            }
            if (tasks.recoverySessionId.isNotBlank()) {
                Text("上次创建需要核对：${tasks.recoverySessionId}", color = MaterialTheme.colorScheme.error)
                Row {
                    Checkbox(reviewed, { reviewed = it }, enabled = !tasks.busy)
                    Text("我已在原运行器核对历史，允许重新创建；不会重发旧消息。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ runCatching { tasks.confirmCreationReviewed(); connectedDirectory = "" }.onFailure { error = it.message.orEmpty() } },
                    enabled = reviewed && !tasks.busy) { Text("确认已核对") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (tasks.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton({ scope.launch {
        error = ""; runCatching { tasks.create(title) }.onSuccess(created).onFailure { error = it.message.orEmpty() }
    } }, enabled = !tasks.busy && connectedDirectory == directory && connectedDirectory.isNotBlank() && tasks.recoverySessionId.isBlank()) { Text("创建对话") } },
        dismissButton = { TextButton(dismiss, enabled = !tasks.busy) { Text("取消") } })
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun AcpConversationPane(state: AppState, record: LocalCodexTaskRecord,
    controller: AcpTaskController? = state.localAcpTasks.controllers[record.key], backLabel: String = "返回本地",
    back: () -> Unit = { state.localSelectedTaskKey = null }) {
    val scope = rememberCoroutineScope()
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    var error by remember(record.key) { mutableStateOf("") }
    var sending by remember(record.key) { mutableStateOf(false) }
    var modeMenu by remember(record.key) { mutableStateOf(false) }
    var modelMenu by remember(record.key) { mutableStateOf(false) }
    var configMenu by remember(record.key) { mutableStateOf<String?>(null) }
    val view = remember(record.key) { state.codexConversationViews.getOrPut(record.key) { CodexConversationView() } }
    var followLatest by view.followLatest
    val dragging by view.scroll.interactionSource.collectIsDraggedAsState()
    var observedPosition by remember(record.key) { mutableStateOf(0 to 0) }
    LaunchedEffect(view, record.key) {
        snapshotFlow { Triple(view.scroll.firstVisibleItemIndex to view.scroll.firstVisibleItemScrollOffset,
            view.scroll.isScrollInProgress, view.scroll.canScrollForward) }.collect { (position, scrolling, forward) ->
            if (scrolling && (position.first < observedPosition.first || position.first == observedPosition.first && position.second < observedPosition.second)) followLatest = false
            if (!forward && !dragging && view.scroll.layoutInfo.totalItemsCount > 0) followLatest = true
            observedPosition = position
        }
    }
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    val messages = controller?.messages?.toList().orEmpty()
    val approvals = controller?.pendingApprovals?.values?.toList().orEmpty()
    val scrollWatch = remember(record.key) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && consumed.y < 0f && !view.scroll.canScrollForward) followLatest = true
            return Offset.Zero
        }
    } }
    LaunchedEffect(messages, approvals.size, followLatest) {
        if (followLatest && !dragging) view.scroll.scrollToItem(messages.size + approvals.size, view.scroll.layoutInfo.viewportSize.height.coerceAtLeast(1))
    }
    fun send() {
        if (controller == null || !controller.ready || sending || controller.busy || controller.changingMode || controller.pendingApprovals.isNotEmpty() || draft.value.text.isBlank()) return
        try { controller.enqueue(draft.value.text) } catch (e: Exception) { error = e.message.orEmpty(); return }
        draft.value = TextFieldValue(); error = ""; sending = true
        val delivery = controller.dispatchNext()
        scope.launch { try { delivery.join() } finally { sending = false } }
    }
    // 切换 / 审批 / 停止：求值时机和出错处理同经典（都在 launch + runCatching 里，异常只写进 error）
    fun act(block: suspend AcpTaskController.() -> Unit) {
        val c = controller ?: return
        scope.launch { runCatching { c.block() }.onFailure { error = it.message.orEmpty() } }
    }
    // Code 的停止方块运行中一直能点，所以自己判断；经典按钮本来就只在 busy && !cancelling 时可点
    fun stop() {
        if (controller == null || !controller.busy || controller.cancelling) return
        act { cancelTurn() }
    }
    if (LocalThemeSpec.current.style == UiStyle.Code) {
        // Code 风格（PRD §5.1）：会话头 + 居中的对话列 + 停靠区；状态、函数和列表顺序同经典。服务器上的 ACP 对话（RemoteAcpPane）也走这里（PRD G3），
        // 会话头换成主机图标。不放「返回」（本机、服务器都一样）：侧栏列着这些对话，新会话页也能建。
        // 比经典多两样（本机对齐 P3，规划图 handover/本机对齐-规划.md）：停靠区的排队条和自动排队（本机、服务器都有）；
        // 本机记录进页面自动接回一次，失败留原因和「重新连接」「重新检测运行器」（同本机 Codex 的 Code 对话页）
        val t = Tokens.current
        val runner = LocalRuntimeDiscovery.title(record.engine)
        val focus = remember(record.key) { FocusRequester() }
        LaunchedEffect(record.key) { runCatching { focus.requestFocus() } }   // 焦点先落输入框，同 SSH 对话页（没有输入区时吞掉异常）
        // 服务器上的记录由 RemoteAcpTasks 管连接，这里不碰本机的会话管理和运行器检测
        val local = record.hostKey.isBlank()
        val tasks = if (local) state.localAcpTasks else null
        val workspace = if (local) state.localWorkspace else null
        var connecting by remember(record.key) { mutableStateOf(false) }
        var attempted by remember(record.key) { mutableStateOf(false) }
        // 原数据目录的同种运行器（核对同 LocalAcpTasks.open）；有多个时优先当前选中的那个
        val installations = workspace?.installations.orEmpty()
        val matching = remember(record.engine, record.runtimeHome, installations) {
            installations.filter { it.engine == record.engine && runCatching { File(it.home).canonicalPath == record.runtimeHome }.getOrDefault(false) }
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
        // 进页面自动接回一次（同本机 Codex）：失败只留原因和按钮，不反复重试；运行器检测由侧栏发起，这里不重复扫描
        LaunchedEffect(record.key, runtime?.id, tasks?.busy) {
            if (tasks != null && !attempted && !tasks.busy && tasks.controllers[record.key] == null && runtime != null) { attempted = true; connect(runtime) }
        }
        // 「马上要接回」也算接回中：免得进页面先闪一下「还没接回」
        val opening = local && (connecting || controller == null && !attempted && runtime != null)
        // 排队同服务器工作台：队列变了交给控制器（自动排队开着时由它发下一条）；偏好按会话存，每个连接套用一次
        LaunchedEffect(controller, state.instructions.entries.toList()) { controller?.queueChanged() }
        LaunchedEffect(controller, controller?.ready) { controller?.takeIf { it.ready }?.applyQueuePreference(QueuePreferences.enabled(record.key)) }
        fun setAutomatic(enabled: Boolean) {
            val c = controller ?: return
            QueuePreferences.setEnabled(record.key, enabled)
            c.setAutoDispatch(QueuePreferences.enabled(record.key))
        }
        val idle = controller != null && controller.ready && !controller.busy && !controller.cancelling && !controller.changingMode && controller.pendingApprovals.isEmpty()
        // 发队首：整轮在控制器的 scope 里跑（离开页面不打断，同经典的发送），sending 只管按钮
        fun deliver() {
            val c = controller ?: return
            if (sending) return
            error = ""; sending = true
            val delivery = c.dispatchNext()
            scope.launch { try { delivery.join() } finally { sending = false } }
        }
        // 先入队再清草稿（同服务器工作台）。自动排队开着：交给上面的队列监听；关着且空闲：发队首；否则只排队
        fun queueSend() {
            val c = controller ?: return
            if (!c.ready || draft.value.text.isBlank()) return
            try { c.enqueue(draft.value.text) } catch (e: Exception) { error = e.message.orEmpty(); return }
            draft.value = TextFieldValue(); error = ""
            if (!c.autoDispatch && !c.busy && !c.cancelling && !c.changingMode && c.pendingApprovals.isEmpty() && !sending) deliver()
        }
        Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
            Column(Modifier.fillMaxSize()) {
                // 「改动」「文件」开着时对话列换成本机的 Git 改动 / 文件视图（本机对齐 P6a / P6b，同服务器 Codex 页），停靠区不变；只给本机记录，服务器 ACP 不变
                var tool by remember(record.key) { mutableStateOf<String?>(null) }
                CodeLocalSessionHeader(state.navigation.title(record.key) ?: record.title, record.directory, runner,
                    host = if (record.hostKey.isBlank()) null else state.conn?.host?.label ?: "服务器",
                    end = if (!local) null else ({ CodeLocalToolButtons(tool) { tool = it } }))
                if (local && tool == "changes") Box(Modifier.weight(1f).fillMaxWidth()) { CodeLocalChanges(state, record) { tool = null } }
                else if (local && tool == "files") Box(Modifier.weight(1f).fillMaxWidth()) { LocalFilesPane(record.key, record.directory) }
                else Box(Modifier.weight(1f).fillMaxWidth()) {
                    // M3f：运行中的状态行（对话末尾）；开始时间记在这一层，列表滚动时不会从 0 算起
                    val transcriptMode = state.transcriptView(record.key)   // M3g：对话视图（Ctrl+O 轮换）
                    val runningSince = rememberRunningSince(controller?.busy == true && approvals.isEmpty())
                    val transcript = messages.map(Transcripts::acp)
                    val thinkingSince = rememberRunningSince(runningSince > 0L && transcript.lastOrNull() is TranscriptItem.Thinking)   // M3h-4：思考行
                    LazyColumn(Modifier.fillMaxSize().nestedScroll(scrollWatch).onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                        if (event.changes.any { it.scrollDelta.y < 0f }) followLatest = false
                    }, state = view.scroll, contentPadding = PaddingValues(40.dp, 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // 顺序和 key 同经典：跟随最新滚到下标 messages + approvals（末尾的占位）
                        // M3b / M3c：统一对话项，连着的工具调用合成摘要行 + 卡片
                        items(Transcripts.rows(Transcripts.visible(transcript, transcriptMode)), key = { "row:" + it.key }) { row -> CodeColumn(true) { CodeTranscriptLine(row, view = transcriptMode) } }
                        if (runningSince > 0L) item(key = "status") { CodeColumn(true) { CodeStatusLine("正在生成回复", runningSince, thinkingSince) } }
                        item(key = "acp-end") { Spacer(Modifier.height(1.dp)) }
                    }
                    // 接回时列表顶端一条 2 高的进度条（同本机 Codex）
                    if (opening) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                    if (!followLatest) JumpToLatestButton { followLatest = true }
                }
                CodeDock(true) {
                    InstructionStrip(state.instructions, record.key, canDeliver = idle && !sending, onDeliver = { deliver() },
                        automatic = controller?.autoDispatch == true, onAutomaticChange = if (controller == null) null else { enabled -> setAutomatic(enabled) })
                    if (controller == null && !local) {
                        // 服务器上没连着的记录只留说明，不画输入区（经典也不画）
                        Note("此记录来自已关闭的进程。原生恢复尚未接入，请在原运行器核对历史；不会自动创建或重发。", t.textMuted)
                        // 补上页脚的位置：说明字和有输入区时页脚的字同高（页脚 28 高、下面留 4）
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Note(when {
                            controller != null -> controller.note
                            opening -> "正在接回会话…"
                            runtime != null -> "会话还没接回。"
                            workspace?.detecting == true -> "正在检测本机运行器…"
                            unavailable != null -> "$runner 运行器不可用：" + unavailable.problem.ifBlank { "未检测到版本" }
                            workspace?.scanned != true -> "还没检测本机运行器。"
                            else -> "没有找到使用原数据目录（${record.runtimeHome}）的 $runner 运行器，请重新检测。"
                        }, t.textMuted)
                        if (local && controller?.ready != true) {
                            // 9.6 = Note 的 16 − chip 的 6.4：chip 的字和上面的提示字对齐
                            Row(Modifier.padding(horizontal = 9.6.dp), verticalAlignment = Alignment.CenterVertically) {
                                CodeFooterChip(if (opening) "正在连接…" else "重新连接", { runtime?.let(::connect) }, enabled = runtime != null && !opening && tasks?.busy != true)
                                CodeFooterChip(if (workspace?.detecting == true) "正在检测…" else "重新检测运行器", { workspace?.refresh() },
                                    enabled = workspace?.detecting != true && !connecting)
                            }
                        }
                        if (error.isNotBlank()) Note(error, t.danger)
                        val settable = controller != null && controller.ready && !controller.busy && !controller.changingMode && controller.pendingApprovals.isEmpty()
                        // 同经典：有 configOptions 就只画它（mode 类放左段，其余按运行器给的顺序放右段），没有时才画旧式模式（左）/ 模型（右）
                        val options = controller?.configOptions
                        val legacy = options == null || options.length() == 0
                        val selectors = if (options == null || legacy) emptyList() else acpConfigSelectors(options)
                        // M3d-2：审批卡停靠在输入卡上方、和输入卡同宽，不进对话滚动区（规格表 §3.3）；下缘到输入卡 6.4
                        approvals.forEach { request ->
                            key(org.json.JSONArray().put(request.get("id")).toString()) {
                                CodeAcpApproval(request.getJSONObject("params"), controller?.ready == true && !controller.cancelling) { option -> act { answerPermission(request.get("id"), option) } }
                            }
                        }
                        CodeLocalComposer(draft.value, { draft.value = it }, onKey = { e -> isTranscriptViewKey(e) && run { state.cycleTranscriptView(record.key); true } },
                            hint = when {
                                controller == null || !controller.ready -> if (!local) "此连接已不能再发送" else if (opening) "正在接回会话…" else "接回会话后可发送"
                                controller.pendingApprovals.isNotEmpty() -> "先处理上面的审批；现在发送会先排队"
                                controller.busy || controller.cancelling || controller.changingMode -> "本轮进行中；现在发送会先排队"
                                else -> "跟它说点什么… Enter 发送，Shift+Enter 换行"
                            },
                            focus = focus, canSend = controller != null && controller.ready && draft.value.text.isNotBlank(), onSend = ::queueSend,
                            running = controller?.busy == true, onStop = ::stop,
                            start = {
                                if (controller != null) {
                                    if (legacy) controller.modes?.let { modes ->
                                        CodeFooterSelect(record.key + ":mode", "模式", modes.optString("currentModeId"),
                                            acpLegacyChoices(modes.optJSONArray("availableModes"), "id"), settable, alignEnd = false) { id -> act { changeMode(id) } }
                                    }
                                    else selectors.filter { it.category == "mode" }.forEach { s ->
                                        CodeFooterSelect(record.key + ":" + s.id, s.name, s.current, s.values, settable, alignEnd = false) { v -> act { changeConfig(s.id, v) } }
                                    }
                                    if (controller.cancelling) CodeFooterNote("停止中…", t.textMuted)
                                }
                            },
                            end = {
                                when {
                                    // 还没接回：先显示记录里的模型（运行器自己的默认值不显示）
                                    controller == null -> record.model.takeIf { it.isNotBlank() && it != "native-default" }?.let { CodeFooterNote(it) }
                                    controller.changingMode -> CodeFooterNote("正在应用设置…", t.textMuted)
                                    legacy -> controller.models?.let { models ->
                                        CodeFooterSelect(record.key + ":model", "模型", models.optString("currentModelId"),
                                            acpLegacyChoices(models.optJSONArray("availableModels"), "modelId"), settable, alignEnd = true) { id -> act { changeModel(id) } }
                                    }
                                    else -> selectors.filter { it.category != "mode" }.forEach { s ->
                                        CodeFooterSelect(record.key + ":" + s.id, s.name, s.current, s.values, settable, alignEnd = true) { v -> act { changeConfig(s.id, v) } }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            TextButton(back) { Text(backLabel) }
            Text("${record.title} · ${LocalRuntimeDiscovery.title(record.engine)}", style = MaterialTheme.typography.titleLarge)
        }
        Text(record.directory, style = MaterialTheme.typography.bodySmall)
        if (controller == null) {
            Text("此记录来自已关闭的进程。原生恢复尚未接入，请在原运行器核对历史；不会自动创建或重发。")
            return@Column
        }
        Text(controller.note, style = MaterialTheme.typography.bodySmall)
        if (controller.configOptions.length() > 0) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            acpConfigSelectors(controller.configOptions).forEach { selector -> Box {
                QuietChoice(configMenu == selector.id, { configMenu = selector.id }, enabled = controller.ready && !controller.busy && !controller.changingMode && controller.pendingApprovals.isEmpty(), label = {
                    Text(selector.name + " · " + (selector.values.firstOrNull { it.id == selector.current }?.name ?: selector.current))
                })
                DropdownMenu(configMenu == selector.id, { configMenu = null }) {
                    selector.values.forEach { option -> DropdownMenuItem(text = { Text(option.name) },
                        modifier = if (option.id == selector.current) Modifier.background(Tokens.current.selected, RoundedCornerShape(8.dp)) else Modifier, onClick = {
                        configMenu = null
                        act { changeConfig(selector.id, option.id) }
                    }) }
                }
            } }
        }
        if (controller.configOptions.length() == 0) controller.models?.let { models ->
            val available = models.optJSONArray("availableModels")
            val options = (0 until (available?.length() ?: 0)).map { available!!.getJSONObject(it) }
            val current = models.optString("currentModelId")
            Box {
                QuietChoice(modelMenu, { modelMenu = true }, enabled = options.isNotEmpty() && controller.ready && !controller.busy && !controller.changingMode && controller.pendingApprovals.isEmpty(), label = {
                    Text("模型 · " + (options.firstOrNull { it.optString("modelId") == current }?.optString("name")?.takeIf { it.isNotBlank() } ?: current))
                })
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(option.optString("name").ifBlank { option.getString("modelId") }) },
                        modifier = if (option.optString("modelId") == current) Modifier.background(Tokens.current.selected, RoundedCornerShape(8.dp)) else Modifier, onClick = {
                            modelMenu = false
                            act { changeModel(option.getString("modelId")) }
                        }) }
                }
            }
        }
        if (controller.configOptions.length() == 0) controller.modes?.let { modes ->
            val available = modes.optJSONArray("availableModes")
            val current = modes.optString("currentModeId")
            val options = (0 until (available?.length() ?: 0)).map { available!!.getJSONObject(it) }
            Box {
                QuietChoice(modeMenu, { modeMenu = true }, enabled = controller.ready && !controller.busy && !controller.changingMode && controller.pendingApprovals.isEmpty(), label = {
                    Text("模式 · " + (options.firstOrNull { it.optString("id") == current }?.optString("name")?.takeIf { it.isNotBlank() } ?: current))
                })
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(option.optString("name").ifBlank { option.getString("id") }) },
                        modifier = if (option.optString("id") == current) Modifier.background(Tokens.current.selected, RoundedCornerShape(8.dp)) else Modifier, onClick = {
                        modeMenu = false
                        act { changeMode(option.getString("id")) }
                    }) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollWatch).onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
            if (event.changes.any { it.scrollDelta.y < 0f }) followLatest = false
        }, state = view.scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { "message:${it.id}" }) { AcpMessageItem(it, LocalRuntimeDiscovery.title(record.engine)) }
            items(approvals, key = { "approval:" + org.json.JSONArray().put(it.get("id")).toString() }) { request ->
                AcpPermissionCard(request.getJSONObject("params"), controller.ready && !controller.cancelling) { option ->
                    act { answerPermission(request.get("id"), option) }
                }
            }
            item(key = "acp-end") { Spacer(Modifier.height(1.dp)) }
        }
        if (!followLatest) TextButton({ followLatest = true }) { Text("回到最新消息 ↓") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(draft.value, { draft.value = it }, Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && draft.value.composition == null &&
                (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed) { send(); true } else false
        }, label = { Text("消息 · Enter 发送，Shift+Enter 换行") }, minLines = 2, maxLines = 6)
        Row {
            TextButton(::send, enabled = controller.ready && !sending && !controller.busy && !controller.changingMode && controller.pendingApprovals.isEmpty() && draft.value.text.isNotBlank()) { Text("发送") }
            TextButton(::stop, enabled = controller.busy && !controller.cancelling) { Text("停止") }
        }
    }
}

/** 一条 ACP 消息（两种风格共用）：作者（· 状态）+ 正文；助手回复按 Markdown 画，工具输出和用户消息原样可选中。 */
@Composable private fun AcpMessageItem(message: AcpMessage, runner: String) {
    Column {
        val author = when (message.author) { "User" -> "你"; "Assistant" -> runner; else -> message.author }
        Text(author + if (message.status.isBlank()) "" else " · ${message.status}", style = MaterialTheme.typography.labelLarge)
        if (message.author == "Assistant" && message.kind == "message") AssistantBody(message.text)
        else SelectionContainer { Text(message.text) }
    }
}

/** 旧式 modes / models（没有 configOptions 时）的选项：[idKey] 是 id 字段名；缺 id 的项跳过（经典会抛），名字空时用 id。 */
private fun acpLegacyChoices(available: org.json.JSONArray?, idKey: String): List<AcpConfigValue> {
    if (available == null) return emptyList()
    return (0 until available.length()).mapNotNull { i ->
        val o = available.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString(idKey).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AcpConfigValue(id, o.optString("name").ifBlank { id })
    }
}
