package app.yxi.desktop

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 建会话失败：留着目标服务器和原参数，「重试」照原样再建（服务器切走了也建在原来那台）。 */
private class FailedCreate(val conn: Conn, val path: String, val prompt: String, val group: String, val reason: String)

/**
 * 服务器上 Codex 运行器的会话页，Code 外观（本机对齐 P2，规划图 handover/本机对齐-规划.md）：会话头 + 居中的对话列 + 停靠区，同本机 Codex 的 Code 页（[CodeLocalCodexPane]）。
 * 界面参照 Claude Desktop 的 Code 模式，Codex 只是可选运行器之一；工作区和控制器和经典 [CodexConversationPane] 共用。
 * 不自动连接：点侧栏行才连，没连上时手点「连接任务」。排队、自动逐轮、引导、附件（SFTP）、@ 引用、语音、输入历史、文件和改动视图都在。
 * 和经典的差别：工具行收进会话头（改动、网页预览、更多）；新建不进表单，用服务器默认配置直接建，提示词放进草稿、不发送；
 * 发送同本机：自动逐轮关着且空闲时，回车直接发队首。选中的对话不属于当前服务器时是空态（创建中 / 失败重试 / 恢复已有任务）。
 */
@Composable
internal fun CodeServerCodexPane(state: AppState) {
    val t = Tokens.current
    val workspace = state.codexWorkspace
    val conn = state.conn
    val record = conn?.let { workspace.tasks(it.host).firstOrNull { r -> r.key == state.codexSelectedTaskKey } }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf<FailedCreate?>(null) }
    var recovering by remember { mutableStateOf(false) }
    // 新建不进表单：服务器默认配置直接建（§7-21），提示词进草稿不发送；面板 scope 里跑，离开页面会取消（同经典，§7-26）。
    // LAZY：先记下 Job 再开跑，测试的 Unconfined 调度下 finally 不会抢在赋值前面
    fun create(target: Conn, path: String, prompt: String, group: String) {
        if (creating != null) return
        failed = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val r = workspace.create(target, path.trim(), firstLineTitle(prompt))
                if (group.isNotBlank()) state.navigation.setGroup(r.key, group)   // 不抛，失败进 navigation.error
                if (prompt.isNotBlank()) state.chatDrafts.getOrPut(r.key) { mutableStateOf(TextFieldValue()) }.value = TextFieldValue(prompt, TextRange(prompt.length))
                showServerCodex(state, target, r.key)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = FailedCreate(target, path, prompt, group, e.message.orEmpty().ifBlank { "创建没成功，请重试" }) }
            finally { creating = null }
        }
        creating = job
        job.start()
    }
    // 首页以外的新建请求（线路面板，§7-23）；create 在面板 scope 里起，effect 重启不会取消它。分组读出来就清，提前返回也不残留
    LaunchedEffect(state.codexCreateRequest) {
        val (path, prompt) = state.codexCreateRequest ?: return@LaunchedEffect
        state.codexCreateRequest = null
        val group = state.codexCreateGroup
        state.codexCreateGroup = ""
        val target = state.conn ?: return@LaunchedEffect
        create(target, path, prompt, group)
    }
    if (conn != null && record != null) CodeServerCodexConversation(state, conn, record)
    else Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
        Box(Modifier.fillMaxSize()) {
            if (creating != null) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
            Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val f = failed?.takeIf { it.conn === conn }   // 失败属于别的服务器时不在这台的空态里显示
                when {
                    conn == null -> CenterNote("请先从左侧选择服务器")
                    creating != null -> { CenterNote("正在连接服务器上的 Codex 并创建对话…"); CodeFooterChip("取消创建", { creating?.cancel() }) }
                    f != null -> {
                        CenterNote(f.reason, t.danger)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodeFooterChip("重试", { create(f.conn, f.path, f.prompt, f.group) }, enabled = !workspace.busy)
                            CodeFooterChip("取消", { failed = null })
                        }
                    }
                    else -> {
                        CenterNote(if (workspace.tasks(conn.host).isEmpty()) "在新会话页选「Codex · 对话工作台」新建对话" else "从左侧选择对话继续，或在新会话页新建")
                        // 连上没有：同对话页的 online（status 断线会重组；connected 和 recover 用的是同一个判断）
                        CodeFooterChip("恢复已有任务", { recovering = true },
                            enabled = conn.status == Conn.Status.Connected && workspace.connected(conn) && !workspace.busy)
                    }
                }
                CodeServerCodexNotices(state, textAlign = TextAlign.Center)
            }
        }
    }
    if (recovering && conn != null) CodeCodexRecoverDialog(state, conn, scope) { recovering = false }
}

@Composable
private fun CenterNote(text: String, color: Color = Tokens.current.textMuted) =
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)

/** 登记读写失败、全局工作区提示，和「已在服务器建好但没登记上」的恢复编号（同经典头部）。 */
@Composable
private fun CodeServerCodexNotices(state: AppState, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    val t = Tokens.current
    val workspace = state.codexWorkspace
    listOf(workspace.registry.error, state.workspaceError).filter { it.isNotBlank() }.forEach {
        Text(it, modifier, color = t.danger, fontSize = 13.sp, lineHeight = 18.sp, textAlign = textAlign)
    }
    if (workspace.recoveryThreadId.isNotBlank()) SelectionContainer {
        Text("任务已在服务器创建，请保留恢复编号：" + workspace.recoveryThreadId, modifier, color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, textAlign = textAlign)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CodeServerCodexConversation(state: AppState, conn: Conn, record: CodexTaskRecord) {
    val t = Tokens.current
    val workspace = state.codexWorkspace
    val controller = workspace.controllers[record.key]
    val scope = rememberCoroutineScope()
    var error by remember(record.key) { mutableStateOf("") }
    var notice by remember(record.key) { mutableStateOf("") }
    var showFiles by remember(record.key) { mutableStateOf(false) }
    var showChanges by remember(record.key) { mutableStateOf(false) }
    var more by remember(record.key) { mutableStateOf(false) }
    var fileDialog by remember(record.key) { mutableStateOf(false) }
    var voiceOpen by remember(record.key) { mutableStateOf(false) }
    var historyOpen by remember(record.key) { mutableStateOf(false) }
    var connecting by remember(record.key) { mutableStateOf(false) }
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    val view = remember(record.key) { state.codexConversationViews.getOrPut(record.key) { CodexConversationView() } }
    var followLatest by view.followLatest
    val dragging by view.scroll.interactionSource.collectIsDraggedAsState()
    var previousPosition by remember(record.key) { mutableStateOf(0 to 0) }
    val messages = controller?.messages?.toList().orEmpty()
    val requests = controller?.pendingRequests?.values?.toList().orEmpty()
    // 滚轮、滚动条往上翻也停跟随，翻回底部再接上（同本机和经典）
    LaunchedEffect(view, record.key) {
        snapshotFlow { Triple(view.scroll.firstVisibleItemIndex to view.scroll.firstVisibleItemScrollOffset,
            view.scroll.isScrollInProgress, view.scroll.canScrollForward) }.collect { (position, scrolling, forward) ->
            if (scrolling && (position.first < previousPosition.first || position.first == previousPosition.first && position.second < previousPosition.second)) followLatest = false
            if (!forward && !dragging && view.scroll.layoutInfo.totalItemsCount > 0) followLatest = true
            previousPosition = position
        }
    }
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    // 文件 / 改动视图开着时列表不在组合里，不滚（同经典）；滚过末尾 1 高的占位
    LaunchedEffect(messages.lastOrNull(), messages.size, requests.size, followLatest) {
        if (followLatest && !dragging) view.scroll.scrollToItem(messages.size + requests.size, view.scroll.layoutInfo.viewportSize.height.coerceAtLeast(1))
    }
    // 队列变了交给控制器：自动逐轮开着时由它发下一条（发送入口不再另调）
    LaunchedEffect(controller, state.instructions.entries.toList()) { controller?.queueChanged() }
    // 接上后读一次模型列表（模型菜单要用）；失败只留原因，菜单里可以再刷新
    LaunchedEffect(controller, controller?.ready) {
        val c = controller ?: return@LaunchedEffect
        if (c.ready && c.models.isEmpty() && !c.modelsLoading && c.modelError.isBlank()) c.refreshModels()
    }
    LaunchedEffect(notice) { if (notice.isNotBlank()) { delay(3000); notice = "" } }
    val focus = remember(record.key) { FocusRequester() }
    LaunchedEffect(record.key) { runCatching { focus.requestFocus() } }   // 焦点先落输入框，同 SSH 对话页
    fun act(block: suspend () -> Unit) {
        scope.launch {
            error = ""
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作未完成" }
        }
    }
    // 手点「连接任务」才接上（不自动连，同经典）；connecting 只管这一页按钮的字样，workspace.busy 管整个工作区
    fun connect() {
        if (connecting) return
        connecting = true
        act { try { workspace.open(conn, record) } finally { connecting = false } }
    }
    // 活动卡和回复里的文件链接：在右侧文件侧栏打开（同经典）
    fun openTaskFile(path: String) = act {
        state.openCodexDocument(conn, record, path)
        if (state.conn === conn && state.codexSelectedTaskKey == record.key) { state.filePanelOpen = true; state.browserPanelOpen = false }
    }
    val webLinks = previewWebLinks(state, conn, record.key, LocalUriHandler.current)
    val taskUris = object : UriHandler {
        override fun openUri(uri: String) {
            val targetUri = normalizedWebLink(uri)
            val parsed = runCatching { java.net.URI(targetUri) }.getOrNull()
            when {
                parsed == null -> error = "无法识别链接"
                parsed.scheme?.lowercase() in listOf("http", "https", "mailto") -> runCatching { webLinks.openUri(targetUri) }.onFailure { error = it.message.orEmpty() }
                parsed.scheme == null && !parsed.path.isNullOrBlank() -> openTaskFile(parsed.path)
                else -> error = "暂不支持此链接，请从文件列表打开"
            }
        }
    }
    fun copyTaskId() {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(record.threadId), null) }
            .onSuccess { error = ""; notice = "已复制任务编号" }
            .onFailure { error = "任务编号复制失败：" + it.message.orEmpty() }
    }
    val ready = controller?.ready == true
    val idle = controller != null && controller.ready && !controller.sending && controller.activeTurnId == null && controller.pendingRequests.isEmpty()
    val canSteer = controller != null && controller.ready && !controller.sending && controller.activeTurnId != null && controller.pendingRequests.isEmpty()
    // 连上没有：status 是 Compose 状态（断线会重组）；workspace.connected 和 open / create 用的是同一个判断（也是测试缝）
    val online = conn.status == Conn.Status.Connected && workspace.connected(conn)
    val attachments = workspace.attachments[record.key]
    val allUploaded = attachments.orEmpty().all { it.state is DraftState.Done }
    val canSend = allUploaded && (draft.value.text.isNotBlank() || !attachments.isNullOrEmpty())   // 没连上也能先排队（同经典）
    // 先入队再清草稿和附件（同经典）。自动逐轮开着：交给上面的队列监听；关着且空闲：发队首（同本机，§7-22）；否则只排队
    fun send() {
        try {
            check(allUploaded) { "请等待附件上传完成，或移除失败附件" }
            if (draft.value.text.isBlank() && attachments.isNullOrEmpty()) return
            val staged = attachments?.toList().orEmpty()
            val labels = attachmentLabels(staged)
            val images = staged.mapIndexed { i, a -> InstructionAttachment(labels[i] + "." + a.name.substringAfterLast('.', "bin"), (a.state as DraftState.Done).staged.remotePath) }
            state.instructions.enqueue(record.key, draft.value.text, images)
            draft.value = TextFieldValue(); attachments?.clear(); error = ""
        } catch (e: Exception) { error = e.message.orEmpty(); return }
        val c = controller
        if (c != null && c.ready && !c.sending && !c.autoDispatch && c.activeTurnId == null && c.pendingRequests.isEmpty()) act { c.sendNext() }
    }
    fun stop() {   // Code 的停止方块运行中一直能点，所以自己判断
        val c = controller ?: return
        if (c.activeTurnId != null) act { c.interrupt() }
    }
    fun setAutomatic(enabled: Boolean) {   // 同经典：偏好按会话存，控制器按偏好开关
        val c = controller ?: return
        QueuePreferences.setEnabled(record.key, enabled)
        c.setAutoDispatch(QueuePreferences.enabled(record.key))
    }
    // 附件走 SFTP 上传：没连上直接说（经典照样进托盘、上传失败，§7-29）
    fun pasteImage() {
        if (!online) { error = "服务器未连接，连上后再添加附件"; return }
        if (!Attach.pasteBusy.compareAndSet(false, true)) return
        act {
            try {
                val image = Attach.clipboardImage() ?: throw IllegalStateException("剪贴板中没有图片")
                val bytes = withContext(Dispatchers.Default) { Attach.pngBytes(image) }
                workspace.stageAttachment(conn, record, Attach.fromPastedImage(bytes))
            } finally { Attach.pasteBusy.set(false) }
        }
    }
    fun attachFiles() {
        if (!online) { error = "服务器未连接，连上后再添加附件"; return }
        Attach.pickFiles().forEach { f -> runCatching { workspace.stageAttachment(conn, record, Attach.fromFile(f)) }.onFailure { error = it.message.orEmpty() } }
        runCatching { focus.requestFocus() }   // 选择框是模态的，关掉后焦点还给输入框
    }
    val mentions = rememberAttachmentMentions(attachments?.toList().orEmpty(), draft.value) { draft.value = it }
    NativeOverlay(more)
    Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
        // M4b：「改动」「文件」开在右侧的卡片里（规格表 §3.12，同 SSH 会话页），对话和停靠区留在左边
        CodePanelLayout(if (showFiles) "文件" else if (showChanges) "改动" else null, { showFiles = false; showChanges = false }, panel = {
            key(record.key) {
                if (showFiles) FilesPane(conn, record.key, record.directory, ::openTaskFile)
                else GitChangesPane(conn, record.directory, ::openTaskFile) { quote -> state.appendCodexQuote(record, quote); showChanges = false }
            }
        }) {
            Column(Modifier.fillMaxSize()) {
                CodeLocalSessionHeader(state.navigation.title(record.key) ?: record.title, record.directory, "Codex", host = conn.host.label) {
                    CodeIconButton(Icons.Outlined.Difference, "改动", active = showChanges) { showChanges = !showChanges; showFiles = false }
                    CodeIconButton(Icons.Outlined.Preview, "网页预览", active = state.browserPanelOpen) {
                        if (state.browserPanelOpen) state.browserPanelOpen = false else { state.browserPanelOpen = true; state.filePanelOpen = false }
                    }
                    Box {
                        CodeIconButton(Icons.Filled.MoreVert, "更多", held = more) { more = true }
                        if (more) CodeServerCodexMoreMenu(state, conn, record, controller, showFiles,
                            onFiles = { showFiles = !showFiles; showChanges = false }, onOpenFile = { fileDialog = true },
                            onCopyId = ::copyTaskId, act = ::act) { more = false }
                    }
                }
                CodeServerCodexNotices(state, Modifier.padding(horizontal = 17.6.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // M3f：运行中的状态行（对话末尾）；开始时间记在这一层，列表滚动时不会从 0 算起
                    val transcriptMode = state.transcriptView(record.key)   // M3g：对话视图（Ctrl+O 轮换）
                    val runningSince = rememberRunningSince(controller?.activeTurnId != null && requests.isEmpty())
                    val transcript = messages.map(Transcripts::codex)
                    val thinkingSince = rememberRunningSince(runningSince > 0L && transcript.lastOrNull() is TranscriptItem.Thinking)   // M3h-4：思考行
                    LazyColumn(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                        if (event.changes.any { it.scrollDelta.y < 0f }) followLatest = false
                    }, state = view.scroll, contentPadding = PaddingValues(40.dp, 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // M3b / M3c：同本机 Codex 页；改动里的路径点了打开文件（同原来的活动卡）
                        items(Transcripts.rows(Transcripts.visible(transcript, transcriptMode)), key = { "row:" + it.key }) { row ->
                            CodeColumn(true) { CompositionLocalProvider(LocalUriHandler provides taskUris) { CodeTranscriptLine(row, onPath = ::openTaskFile, view = transcriptMode) } }
                        }
                        if (runningSince > 0L) item(key = "status") { CodeColumn(true) { CodeStatusLine("正在运行", runningSince, thinkingSince) } }
                        item(key = "latest") { Spacer(Modifier.height(1.dp)) }
                    }
                    if (workspace.busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                    if (!followLatest) JumpToLatestButton { followLatest = true }
                }
                CodeDock(true) {
                    InstructionStrip(state.instructions, record.key, canDeliver = idle,
                        onDeliver = { item -> if (controller != null) act { controller.sendNext(item.id) } },
                        automatic = controller?.autoDispatch == true,
                        onAutomaticChange = if (controller == null) null else { enabled -> setAutomatic(enabled) },
                        canSteer = canSteer, onSteer = { item -> if (controller != null) act { controller.steerNext(item.id) } })
                    if (controller != null) CodexGoalStrip(controller)
                    Note(controller?.note ?: "连接后恢复对话", t.textMuted)
                    if (!ready) {
                        // 9.6 = Note 的 16 − chip 的 6.4：chip 的字和上面的提示字对齐
                        Row(Modifier.padding(horizontal = 9.6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CodeFooterChip(if (connecting) "正在连接…" else "连接任务", ::connect, enabled = !workspace.busy && online)
                            if (!online) CodeFooterNote("服务器未连接", t.textMuted)
                        }
                    }
                    if (notice.isNotBlank()) Note(notice, t.textMuted)
                    if (error.isNotBlank()) Note(error, t.danger)
                    // M3d-2b：请求卡停靠在输入卡上方（规格表 §3.3），不进对话滚动区
                    if (controller != null) requests.forEach { request -> key(request.get("id").toString()) { CodexRequestCard(request, controller, ::openTaskFile) { block -> act(block) } } }
                    // M4p：分支改动条（服务器上同一段只读脚本），点徽章开右侧「改动」；连上 / 断开时重读
                    CodeBranchBar(record.key, controller?.activeTurnId != null, { BranchChanges.remote(conn, record.directory) }, refresh = online,
                        poll = false, paused = controller?.sending == true || controller?.pendingRequests?.isNotEmpty() == true) { showChanges = true; showFiles = false }
                    CodeLocalComposer(draft.value, { draft.value = it },
                        hint = when {
                            controller == null || !controller.ready -> "连接任务后发送；现在发送会先排队"
                            controller.pendingRequests.isNotEmpty() -> "先处理上面的请求；现在发送会先排队"
                            controller.activeTurnId != null -> "本轮进行中；现在发送会先排队"
                            else -> "跟它说点什么… Enter 发送，Shift+Enter 换行；输入 @ 引用附件，截图直接 Ctrl+V"
                        },
                        focus = focus, canSend = canSend, onSend = ::send,
                        running = controller?.activeTurnId != null, onStop = ::stop,
                        attachments = attachments?.toList().orEmpty(),
                        onRemoveAttachment = { a ->
                            draft.value = draftAfterAttachmentRemoval(draft.value, attachments?.toList().orEmpty(), a)
                            a.cancelled.set(true); attachments?.remove(a)
                        },
                        onKey = { e ->
                            if (isTranscriptViewKey(e) && run { state.cycleTranscriptView(record.key); true }) return@CodeLocalComposer true
                            when {
                                mentions.handle(e) -> true
                                e.type == KeyEventType.KeyDown && draft.value.composition == null && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed) &&
                                    Attach.hasClipboardImage() && !Attach.pasteBusy.get() -> { pasteImage(); true }
                                else -> false
                            }
                        },
                        showTransferStatus = true, mentions = mentions,
                        start = { CodeComposerPlus(onAttach = ::attachFiles, onHistory = { historyOpen = true }, onSearch = null, onVoice = { voiceOpen = true }, onRoutes = { state.openRoutes() }) },
                        end = {
                            if (controller != null && controller.ready) CodeCodexModelMenu(record.key, controller, record.modelOverride.orEmpty(), onRoutes = { state.openRoutes() }) { error = it }
                            else (controller?.currentModelLabel?.ifBlank { null } ?: record.modelOverride)?.takeIf { it.isNotBlank() }?.let { CodeFooterNote(it) }
                        })
                }
            }
        }
    }
    if (voiceOpen) VoiceInputDialog(conn, { voiceOpen = false }) { text -> state.appendCodexQuote(record, text); voiceOpen = false }
    if (historyOpen) PromptHistoryDialog(state.instructions, record.key, draft.value.text.isNotBlank(), { historyOpen = false }) { text, replace ->
        if (replace) draft.value = TextFieldValue(text, TextRange(text.length)) else state.appendCodexQuote(record, text)
        historyOpen = false
    }
    if (fileDialog) CodeServerFileDialog({ fileDialog = false }) { path -> fileDialog = false; openTaskFile(path.trim()) }
}

/**
 * 「更多」：经典工具行里没进会话头的几项（文件、打开文件、复制编号、在此对话应用当前服务器线路），加模型与线路、切换任务、键盘快捷键。
 * 「在此对话应用当前服务器线路」文案不改：线路面板（RoutesPane）的说明指向它。固定宽 280：有没有说明文字，菜单都一样宽。
 */
@Composable
private fun CodeServerCodexMoreMenu(
    state: AppState, conn: Conn, record: CodexTaskRecord, controller: CodexTaskController?, showFiles: Boolean,
    onFiles: () -> Unit, onOpenFile: () -> Unit, onCopyId: () -> Unit, act: (suspend () -> Unit) -> Unit, close: () -> Unit,
) {
    val workspace = state.codexWorkspace
    val docs = !state.filePanelOpen && !state.browserPanelOpen && state.documents.any { it.task == record.key }
    CodePopup(close, alignEnd = true, gap = 6.4.dp, width = 280.dp) {
        CodeMenuItem("文件", { close(); onFiles() }, checked = showFiles)
        if (docs) CodeMenuItem("打开文件侧栏", { close(); state.filePanelOpen = true; state.browserPanelOpen = false })
        CodeMenuItem("打开文件…", { close(); onOpenFile() })
        CodeMenuItem("复制任务编号", { close(); onCopyId() })
        CodeMenuDivider()
        if (controller != null) {
            CodeMenuItem(if (workspace.busy) "正在连接…" else "在此对话应用当前服务器线路", { close(); act { workspace.applyCurrentConfiguration(conn, record) } },
                enabled = !workspace.busy && controller.ready && !controller.sending && controller.activeTurnId == null && controller.pendingRequests.isEmpty())
            CodeMenuNote("应用时重新连接并保留历史、草稿和队列；自动发送暂停，成功后可手动恢复。")
        }
        CodeMenuItem("模型与线路", { close(); state.openRoutes() })
        CodeMenuItem("切换任务", { close(); state.showTaskSwitcher = true }, trailing = "Ctrl+K")
        CodeMenuItem("键盘快捷键", { close(); state.showShortcuts = true }, trailing = "Ctrl+/")
    }
}

/** 「打开文件…」：任务目录下的相对路径或绝对路径，在右侧文件侧栏打开（同经典工具行的「打开文件」）。 */
@Composable
private fun CodeServerFileDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    WorkbenchDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开文件") },
        text = { OutlinedTextField(path, { path = it }, singleLine = true, label = { Text("文件路径") }, placeholder = { Text("文件路径，如 README.md") }) },
        confirmButton = { TextButton({ onOpen(path) }, enabled = path.isNotBlank()) { Text("打开") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 「恢复已有任务」：同经典的恢复区，从当前服务器恢复历史，不新建对话、不发送指令；恢复后接上并选中。[scope] 由调用方给（面板 / App 级）。 */
@Composable
internal fun CodeCodexRecoverDialog(state: AppState, conn: Conn, scope: CoroutineScope, close: () -> Unit) {
    val workspace = state.codexWorkspace
    var id by remember { mutableStateOf(workspace.recoveryThreadId) }
    var running by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    WorkbenchDialog(
        onDismissRequest = close,
        title = { Text("恢复已有任务") },
        text = {
            Column {
                OutlinedTextField(id, { id = it }, singleLine = true, label = { Text("现有 Codex 任务编号") })
                Text("从当前服务器恢复历史，不会新建对话或发送指令。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (failure.isNotBlank()) Text(failure, color = Tokens.current.danger)
            }
        },
        confirmButton = {
            TextButton({
                running = true; failure = ""
                scope.launch {
                    try {
                        val r = workspace.recover(conn, id.trim(), "")
                        workspace.open(conn, r)
                        showServerCodex(state, conn, r.key)
                        close()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = e.message.orEmpty().ifBlank { "恢复没成功，请重试" } }
                    finally { running = false }
                }
            }, enabled = id.isNotBlank() && !running && !workspace.busy) { Text(if (running) "正在恢复…" else "恢复") }
        },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
}

/** 新建对话的标题：提示词第一行非空内容，去掉控制字符，最多 40 字（同本机首页）；为空时 create 用目录名。 */
internal fun firstLineTitle(text: String): String =
    text.lineSequence().map { line -> line.filter { it >= ' ' }.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(40)

/**
 * 建好或恢复了服务器上的 Codex 对话：还停在首页就切到那台服务器的对话页（同原 prepareCodexTask：先 select(c, null) 再切页、选中），
 * 已在那台服务器的对话页就只选中；用户已经去了别处不打扰（侧栏里能看到新对话）。本机首页是 Page.LocalWorkspace，不会误中。
 */
internal fun showServerCodex(state: AppState, c: Conn, key: String) {
    if (state.page == Page.Workspace && state.session == null) { state.select(c, null); state.page = Page.Codex; state.codexSelectedTaskKey = key }
    else if (state.page == Page.Codex && state.conn === c) state.codexSelectedTaskKey = key
}
