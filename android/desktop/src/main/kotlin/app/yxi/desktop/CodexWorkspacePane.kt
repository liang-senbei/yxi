package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.mikepenz.markdown.m3.Markdown

@Composable
internal fun CodexWorkspacePane(state: AppState) {
    val conn = state.conn
    val task = conn?.let { state.codexWorkspace.tasks(it.host).firstOrNull { task -> task.key == state.codexSelectedTaskKey } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panel = conn != null && task != null && (state.filePanelOpen || state.browserPanelOpen)
        val compact = maxWidth < 850.dp || state.previewExpanded
        val panelWidth = state.filePanelWidth.coerceAtMost((maxWidth.value - 350).coerceAtLeast(340f)).dp
        Row(Modifier.fillMaxSize()) {
            if (!panel || !compact) Box(Modifier.weight(1f).fillMaxHeight()) { CodexConversationPane(state) }
            if (panel && conn != null && task != null) {
                VerticalDivider()
                Box(if (compact) Modifier.fillMaxSize() else Modifier.width(panelWidth).fillMaxHeight()) {
                    key(task.key, state.browserPanelOpen) {
                        if (state.browserPanelOpen) BrowserPane(state, conn, task.key, task.directory) { state.appendCodexQuote(task, it) }
                        else DocumentPane(state, conn, task.key,
                            { it.task == task.key && it.runtimeId == task.key },
                            { state.openCodexDocument(conn, task, it) },
                            { state.appendCodexQuote(task, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CodexConversationPane(state: AppState) {
    val workspace = state.codexWorkspace
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf(state.session?.cwd.orEmpty()) }
    var title by remember { mutableStateOf("") }
    var filePath by remember(state.codexSelectedTaskKey) { mutableStateOf("") }
    var fileEntry by remember(state.codexSelectedTaskKey) { mutableStateOf(false) }
    var showFiles by remember(state.codexSelectedTaskKey) { mutableStateOf(false) }
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
    val conversationScroll = rememberLazyListState()
    val dragging by conversationScroll.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(selected?.key) { mutableStateOf(true) }
    var observedIndex by remember(selected?.key) { mutableStateOf(0) }
    var observedOffset by remember(selected?.key) { mutableStateOf(0) }
    // Mouse wheel and scrollbar navigation must also disable following, not only touch dragging.
    LaunchedEffect(conversationScroll, selected?.key) {
        snapshotFlow { Triple(conversationScroll.firstVisibleItemIndex, conversationScroll.firstVisibleItemScrollOffset, conversationScroll.isScrollInProgress) }
            .collect { (index, offset, scrolling) ->
                if (scrolling && (index < observedIndex || index == observedIndex && offset < observedOffset)) followLatest = false
                if (scrolling && !conversationScroll.canScrollForward) followLatest = true
                observedIndex = index; observedOffset = offset
            }
    }
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    val messages = controller?.messages?.toList().orEmpty()
    val requests = controller?.pendingRequests?.values?.toList().orEmpty()
    LaunchedEffect(selected?.key, messages.lastOrNull(), messages.size, requests.size, followLatest, showFiles) {
        if (followLatest && !dragging && !showFiles) conversationScroll.scrollToItem(messages.size + requests.size)
    }
    fun openTaskFile(path: String) {
        val target = conn ?: return
        val task = selected ?: return
        act {
            state.openCodexDocument(target, task, path)
            if (state.conn === target && state.codexSelectedTaskKey == task.key) {
                state.filePanelOpen = true; state.browserPanelOpen = false
            }
        }
    }
    val externalUris = LocalUriHandler.current
    val taskUris = object : UriHandler {
        override fun openUri(uri: String) {
            val parsed = runCatching { java.net.URI(uri) }.getOrNull()
            when {
                parsed == null -> error = "无法识别链接"
                parsed.scheme in listOf("http", "https", "mailto") -> runCatching { externalUris.openUri(uri) }.onFailure { error = it.message.orEmpty() }
                parsed.scheme == null && !parsed.path.isNullOrBlank() -> openTaskFile(parsed.path)
                else -> error = "暂不支持此链接，请从文件列表打开"
            }
        }
    }
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
                TextButton({ showFiles = !showFiles }) { Text(if (showFiles) "返回对话" else "项目文件") }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ fileEntry = !fileEntry }) { Text("打开文件") }
                TextButton({ state.browserPanelOpen = true; state.filePanelOpen = false }) { Text("网页预览") }
                if (state.documents.any { it.task == selected.key }) TextButton({ state.filePanelOpen = true; state.browserPanelOpen = false }) { Text("文件侧栏") }
            }
            if (fileEntry && conn != null) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filePath, { filePath = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("文件路径，如 README.md") })
                TextButton({
                    val path = filePath.trim()
                    act {
                        state.openCodexDocument(conn, selected, path)
                        if (state.codexSelectedTaskKey == selected.key && state.conn === conn) {
                            state.filePanelOpen = true; state.browserPanelOpen = false; fileEntry = false
                        }
                    }
                }, enabled = filePath.isNotBlank()) { Text("打开") }
            }
            if (controller == null || !controller.ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(controller?.note ?: "连接后恢复对话", Modifier.weight(1f))
                    TextButton({ if (conn != null) act { workspace.open(conn, selected) } }, enabled = !workspace.busy && conn?.ssh?.isConnected == true) { Text("连接任务") }
                }
            }
            if (showFiles && conn != null) Box(Modifier.weight(1f).fillMaxWidth()) {
                key(selected.key) { FilesPane(conn, selected.key, selected.directory, ::openTaskFile) }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = conversationScroll, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(messages, key = { "message:" + it.id }) { message ->
                    if (message.kind != "message") CodexActivityCard(message, ::openTaskFile)
                    else
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(message.author, color = Tokens.current.textMuted, style = MaterialTheme.typography.labelMedium)
                        CompositionLocalProvider(LocalUriHandler provides taskUris) {
                            SelectionContainer {
                                if (message.author == "Codex") Markdown(message.text) else Text(message.text)
                            }
                        }
                    }
                }
                items(requests, key = { "request:" + it.get("id").toString() }) { request ->
                    val method = request.optString("method")
                    val supported = method == "item/commandExecution/requestApproval" || method == "item/fileChange/requestApproval"
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (supported) "需要你的批准" else "运行器需要进一步输入", style = MaterialTheme.typography.titleSmall)
                            val params = request.optJSONObject("params") ?: JSONObject()
                            val related = controller?.messages?.firstOrNull { it.id == params.optString("itemId") && it.kind != "message" }
                            if (related != null) CodexActivityCard(related, ::openTaskFile, initiallyExpanded = true)
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
                            } else if (method == "item/tool/requestUserInput" && controller != null) {
                                CodexQuestionForm(controller, request)
                            } else Text("此类输入暂未接入，可中断当前轮次后调整任务。", color = Tokens.current.textMuted)
                        }
                    }
                }
                item(key = "latest") { Spacer(Modifier.height(1.dp)) }
            }
            if (!showFiles && !followLatest) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton({ followLatest = true }) { Text("回到最新消息 ↓") }
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
            var voiceOpen by remember(selected.key) { mutableStateOf(false) }
            var historyOpen by remember(selected.key) { mutableStateOf(false) }
            if (voiceOpen && conn != null) VoiceInputDialog(conn, { voiceOpen = false }) { text ->
                state.appendCodexQuote(selected, text)
                voiceOpen = false
            }
            if (historyOpen) PromptHistoryDialog(state.instructions, selected.key, draft.value.text.isNotBlank(), { historyOpen = false }) { text, replace ->
                if (replace) draft.value = TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
                else state.appendCodexQuote(selected, text)
                historyOpen = false
            }
            val attachments = workspace.attachments[selected.key]
            val allUploaded = attachments.orEmpty().all { it.state is DraftState.Done }
            fun pasteImage() {
                if (conn == null || !conn.ssh.isConnected || !Attach.pasteBusy.compareAndSet(false, true)) return
                act {
                    try {
                        val image = Attach.clipboardImage() ?: throw IllegalStateException("剪贴板中没有图片")
                        val bytes = withContext(Dispatchers.Default) { Attach.pngBytes(image) }
                        workspace.stageImage(conn, selected, Attach.fromPastedImage(bytes))
                    } finally { Attach.pasteBusy.set(false) }
                }
            }
            fun enqueueDraft() {
                try {
                    check(allUploaded) { "请等待图片上传完成，或移除失败图片" }
                    if (draft.value.text.isBlank() && attachments.isNullOrEmpty()) return
                    val images = attachments.orEmpty().map { attachment ->
                        InstructionAttachment(attachment.name, (attachment.state as DraftState.Done).staged.remotePath)
                    }
                    state.instructions.enqueue(selected.key, draft.value.text, images)
                    draft.value = TextFieldValue()
                    attachments?.clear()
                    controller?.queueChanged()
                    error = ""
                } catch (e: Exception) { error = e.message.orEmpty() }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton({ voiceOpen = true }, enabled = conn?.ssh?.isConnected == true) { Text("语音输入") }
                TextButton({ historyOpen = true }) { Text("历史提示词") }
                TextButton({ if (conn != null) {
                    Attach.pickFiles().forEach { file ->
                        runCatching { workspace.stageImage(conn, selected, Attach.fromFile(file)) }.onFailure { error = it.message.orEmpty() }
                    }
                } }, enabled = conn?.ssh?.isConnected == true) { Text("添加图片") }
                TextButton(::pasteImage, enabled = conn?.ssh?.isConnected == true && !Attach.pasteBusy.get()) { Text("粘贴图片") }
            }
            if (!attachments.isNullOrEmpty()) Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                attachments.toList().forEach { attachment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val status = when (val upload = attachment.state) {
                            DraftState.Waiting -> "等待上传"
                            is DraftState.Uploading -> "上传 ${upload.percent}%"
                            is DraftState.Done -> "已上传"
                            is DraftState.Failed -> upload.msg
                        }
                        Text(attachment.name + " · " + status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (attachment.state is DraftState.Failed && conn != null) TextButton({
                            runCatching { workspace.retryImage(conn, selected, attachment) }.onFailure { error = it.message.orEmpty() }
                        }) { Text("重试") }
                        TextButton({ attachment.cancelled.set(true); attachments.remove(attachment) }) { Text("移除") }
                    }
                }
            }
            OutlinedTextField(draft.value, { draft.value = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.isCtrlPressed && event.key == Key.V && Attach.hasClipboardImage() -> { pasteImage(); true }
                    event.isCtrlPressed && event.key == Key.Enter && draft.value.composition == null -> { enqueueDraft(); true }
                    else -> false
                }
            },
                placeholder = { Text("描述任务，或补充下一步要求…") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Ctrl+Enter 加入队列", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                Button(::enqueueDraft, enabled = allUploaded && (draft.value.text.isNotBlank() || !attachments.isNullOrEmpty())) { Text(if (controller?.autoDispatch == true) "发送到队列" else "加入队列") }
            }
        }
    }
}
