package app.yxi.desktop

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

/**
 * 本机 Codex 对话的 Code 外观（本机对齐 P1b，规划图 handover/本机对齐-规划.md）：会话头 + 居中的对话列 + 停靠区，结构同 Claude / ACP 的 Code 分支。
 * 和经典 [LocalConversationPane] 的差别：进页面自动接回一次（同服务器点任务行即连接）；失败留原因和「重新连接」；
 * 排队、自动逐轮和引导同服务器工作台；活动卡和回复里的文件链接改成复制路径（本机还没有文件面板，P6 再接）。
 * 输入区（P1c）同本机 Claude 的 Code 分支：页脚左段「+」选图、Ctrl+V 贴截图，草稿按会话存在 AppState；右段模型 / 思考强度菜单，选了从下一轮起生效。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CodeLocalCodexPane(state: AppState, record: LocalCodexTaskRecord) {
    val t = Tokens.current
    val tasks = state.localCodexTasks
    val workspace = state.localWorkspace
    val controller = tasks.controllers[record.key]
    val scope = rememberCoroutineScope()
    var error by remember(record.key) { mutableStateOf("") }
    var notice by remember(record.key) { mutableStateOf("") }
    var connecting by remember(record.key) { mutableStateOf(false) }
    var attempted by remember(record.key) { mutableStateOf(false) }
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    val view = remember(record.key) { state.codexConversationViews.getOrPut(record.key) { CodexConversationView() } }
    // 图片草稿（同本机 Claude）：引用是 tasks.images 的快照，托盘缩略图在 IO 线程读快照
    val imageDrafts = remember(record.key) { state.localCodexImageDrafts.getOrPut(record.key) { mutableStateListOf() } }
    var capturingImages by remember(record.key) { mutableStateOf(false) }
    var imagePreviews by remember(record.key) { mutableStateOf<List<DraftAttach>>(emptyList()) }
    LaunchedEffect(imageDrafts.toList()) {
        val references = imageDrafts.toList()
        try { imagePreviews = withContext(Dispatchers.IO) { references.map(tasks.images::preview) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "图片预览失败" }
    }
    var followLatest by view.followLatest
    val dragging by view.scroll.interactionSource.collectIsDraggedAsState()
    var previousPosition by remember(record.key) { mutableStateOf(0 to 0) }
    val messages = controller?.messages?.toList().orEmpty()
    val requests = controller?.pendingRequests?.values?.toList().orEmpty()
    // 滚轮、滚动条往上翻也停跟随，翻回底部再接上（同 Claude 和服务器工作台）
    LaunchedEffect(view, record.key) {
        snapshotFlow { Triple(view.scroll.firstVisibleItemIndex to view.scroll.firstVisibleItemScrollOffset,
            view.scroll.isScrollInProgress, view.scroll.canScrollForward) }.collect { (position, scrolling, forward) ->
            if (scrolling && (position.first < previousPosition.first || position.first == previousPosition.first && position.second < previousPosition.second)) followLatest = false
            if (!forward && !dragging && view.scroll.layoutInfo.totalItemsCount > 0) followLatest = true
            previousPosition = position
        }
    }
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    LaunchedEffect(messages.lastOrNull(), messages.size, requests.size, followLatest) {
        // 滚过末尾 1 高的占位，让布局夹到真实末端（Markdown 和展开的活动卡高度不定）
        if (followLatest && !dragging) view.scroll.scrollToItem(messages.size + requests.size, view.scroll.layoutInfo.viewportSize.height.coerceAtLeast(1))
    }
    // 队列变了交给控制器：自动逐轮开着时由它发下一条（发送入口不再另调，免得同一帧排两次）
    LaunchedEffect(controller, state.instructions.entries.toList()) { controller?.queueChanged() }
    // 接回后读一次模型列表（模型菜单要用）；失败只留原因，菜单里可以再刷新，不自动重试
    LaunchedEffect(controller, controller?.ready) {
        val c = controller ?: return@LaunchedEffect
        if (c.ready && c.models.isEmpty() && !c.modelsLoading && c.modelError.isBlank()) c.refreshModels()
    }
    // 原数据目录的 Codex 运行器（核对同 LocalCodexTasks.open）；有多个时优先当前选中的那个
    val matching = remember(record.runtimeHome, workspace.installations) {
        workspace.installations.filter { it.engine == "codex" && runCatching { File(it.home).canonicalPath == record.runtimeHome }.getOrDefault(false) }
    }
    val runtime = matching.filter { it.ready }.let { found -> found.firstOrNull { it.id == workspace.selectedRuntime?.id } ?: found.firstOrNull() }
    val unavailable = matching.firstOrNull { !it.ready }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            error = ""
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作未完成" }
        }
    }
    fun connect(target: LocalRuntimeInstallation) {
        if (connecting || tasks.busy) return
        connecting = true; error = ""; notice = ""
        scope.launch {
            try { tasks.open(target, record.key) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "接回未完成" }
            finally { connecting = false }
        }
    }
    // 进页面自动接回一次（同服务器点任务行即连接）：在面板的 scope 里起，tasks.busy 变化不会取消它；失败只留原因和「重新连接」，不反复重试。
    // 运行器检测由侧栏发起，这里不重复扫描
    LaunchedEffect(record.key, runtime?.id, tasks.busy) {
        if (!attempted && !tasks.busy && tasks.controllers[record.key] == null && runtime != null) { attempted = true; connect(runtime) }
    }
    // 复制路径之类的提示 3 秒后自己消失
    LaunchedEffect(notice) { if (notice.isNotBlank()) { delay(3000); notice = "" } }
    fun copyPath(path: String) {
        // 相对路径按会话的工作目录补全，复制出去能直接用
        val absolute = File(path).let { if (it.isAbsolute) it else File(record.directory, path) }.path
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(absolute), null) }
            .onSuccess { error = ""; notice = "已复制路径：$absolute" }
            .onFailure { error = "路径复制失败：" + it.message.orEmpty() }
    }
    // 回复里的链接：网页交给系统，本机路径复制（同服务器工作台的 taskUris，只是没有文件面板）。
    // 解析不了的（带空格、反斜杠）和单字母协议（盘符 C:/…）都按本机路径处理
    val externalUris = LocalUriHandler.current
    val links = object : UriHandler {
        override fun openUri(uri: String) {
            val target = normalizedWebLink(uri)
            val parsed = runCatching { java.net.URI(target) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase()
            when {
                parsed == null || scheme?.length == 1 -> copyPath(target)
                scheme == "http" || scheme == "https" || scheme == "mailto" -> runCatching { externalUris.openUri(target) }.onFailure { error = it.message.orEmpty() }
                scheme == "file" -> runCatching { File(parsed).path }.onSuccess { copyPath(it) }.onFailure { error = "无法识别链接" }
                scheme == null && !parsed.path.isNullOrBlank() -> copyPath(parsed.path)
                else -> error = "暂不支持此链接"
            }
        }
    }
    // 读图同本机 Claude：快照和核对在 IO 线程，提交只补新引用（读图期间删掉的不复活）；读图中算未完成的操作（退出保护）
    fun pasteImage(): Boolean {
        if (!Attach.hasClipboardImage()) return false
        if (capturingImages) return true
        capturingImages = true; state.localCodexImageCaptures.add(record.key); error = ""
        scope.launch {
            try {
                val reference = withContext(Dispatchers.IO) {
                    val bitmap = checkNotNull(Attach.clipboardImage()) { "无法读取剪贴板图片" }
                    require(bitmap.width.toLong() * bitmap.height <= 48_000_000L) { "图片超过4800万像素，请先缩小" }
                    tasks.images.capture("粘贴图片.png", Attach.pngBytes(bitmap))
                }
                val existing = imageDrafts.toList()
                val merged = withContext(Dispatchers.IO) { tasks.images.mergeDraft(existing, listOf(reference)) }
                tasks.images.commitDraft(imageDrafts, existing, merged)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "图片粘贴失败" }
            finally { capturingImages = false; state.localCodexImageCaptures.remove(record.key) }
        }
        return true
    }
    fun addImages() {
        if (capturingImages) return
        val files = Attach.pickFiles()
        if (files.isEmpty()) return
        capturingImages = true; state.localCodexImageCaptures.add(record.key); error = ""
        scope.launch {
            try {
                require(files.size <= 4) { "每次最多4张图片" }
                val captured = withContext(Dispatchers.IO) { files.map { tasks.images.capture(it) } }
                val existing = imageDrafts.toList()
                val merged = withContext(Dispatchers.IO) { tasks.images.mergeDraft(existing, captured) }
                tasks.images.commitDraft(imageDrafts, existing, merged)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "图片读取失败" }
            finally { capturingImages = false; state.localCodexImageCaptures.remove(record.key) }
        }
    }
    // 先入队再清草稿和图片（同服务器工作台）。自动逐轮开着：交给上面的队列监听；关着且空闲：发队首；否则只排队
    fun send() {
        val c = controller ?: return
        if (!c.ready || c.sending || capturingImages || draft.value.text.isBlank() && imageDrafts.isEmpty()) return
        try { state.instructions.enqueue(record.key, draft.value.text, imageDrafts.toList()) }
        catch (e: Exception) { error = e.message ?: "草稿未能保存，未发送"; return }
        draft.value = TextFieldValue(); imageDrafts.clear(); imagePreviews = emptyList()
        if (!c.autoDispatch && c.activeTurnId == null && c.pendingRequests.isEmpty()) act { c.sendNext() }
    }
    fun stop() {   // Code 的停止方块运行中一直能点，所以自己判断
        val c = controller ?: return
        if (c.activeTurnId != null) act { c.interrupt() }
    }
    fun setAutomatic(enabled: Boolean) {   // 同服务器工作台：偏好按会话存，控制器按偏好开关
        val c = controller ?: return
        QueuePreferences.setEnabled(record.key, enabled)
        c.setAutoDispatch(QueuePreferences.enabled(record.key))
    }
    val focus = remember(record.key) { FocusRequester() }
    LaunchedEffect(record.key) { runCatching { focus.requestFocus() } }   // 焦点先落输入框，同 SSH 对话页
    val ready = controller?.ready == true
    val idle = controller != null && controller.ready && !controller.sending && controller.activeTurnId == null && controller.pendingRequests.isEmpty()
    val canSend = controller != null && controller.ready && !controller.sending && !capturingImages && (draft.value.text.isNotBlank() || imageDrafts.isNotEmpty())
    val canSteer = controller != null && controller.ready && !controller.sending && controller.activeTurnId != null && controller.pendingRequests.isEmpty()
    // 「马上要接回」也算接回中（比如等别的会话接完）：免得进页面先闪一下「还没接回」
    val opening = connecting || controller == null && !attempted && runtime != null
    Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
        Column(Modifier.fillMaxSize()) {
            // 「改动」「文件」开着时对话列换成本机的 Git 改动 / 文件视图（本机对齐 P6a / P6b，同服务器 Codex 页），停靠区不变
            var tool by remember(record.key) { mutableStateOf<String?>(null) }
            CodeLocalSessionHeader(state.navigation.title(record.key) ?: record.title, record.directory, LocalRuntimeDiscovery.title(record.engine)) {
                CodeLocalToolButtons(tool) { tool = it }
            }
            if (tool == "changes") Box(Modifier.weight(1f).fillMaxWidth()) { CodeLocalChanges(state, record) { tool = null } }
            else if (tool == "files") Box(Modifier.weight(1f).fillMaxWidth()) { LocalFilesPane(record.key, record.directory) }
            else Box(Modifier.weight(1f).fillMaxWidth()) {
                // M3f：运行中的状态行（对话末尾）；开始时间记在这一层，列表滚动时不会从 0 算起
                val transcriptMode = state.transcriptView(record.key)   // M3g：对话视图（Ctrl+O 轮换）
                val runningSince = rememberRunningSince(controller?.activeTurnId != null && requests.isEmpty())
                val transcript = messages.map(Transcripts::codex)
                val thinkingSince = rememberRunningSince(runningSince > 0L && transcript.lastOrNull() is TranscriptItem.Thinking)   // M3h-4：思考行
                LazyColumn(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    if (event.changes.any { it.scrollDelta.y < 0f }) followLatest = false
                }, state = view.scroll, contentPadding = PaddingValues(40.dp, 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // 顺序和 key 同服务器工作台：跟随最新滚到下标 messages + requests（末尾的占位）
                    // M3b / M3c：统一对话项（你 = 右对齐气泡，Codex = 没有作者名的正文），命令和文件改动合成摘要行 + 卡片（代替原来的活动卡）；
                    // 回复里的链接、改动里的路径照旧是复制路径
                    items(Transcripts.rows(Transcripts.visible(transcript, transcriptMode)), key = { "row:" + it.key }) { row ->
                        CodeColumn(true) { CompositionLocalProvider(LocalUriHandler provides links) { CodeTranscriptLine(row, onPath = ::copyPath, view = transcriptMode) } }
                    }
                    if (runningSince > 0L) item(key = "status") { CodeColumn(true) { CodeStatusLine("正在运行", runningSince, thinkingSince) } }
                    item(key = "latest") { Spacer(Modifier.height(1.dp)) }
                }
                // 接回时列表顶端一条 2 高的进度条（同 Claude 读历史）
                if (opening) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                if (!followLatest) JumpToLatestButton { followLatest = true }
            }
            CodeDock(true) {
                InstructionStrip(state.instructions, record.key, canDeliver = idle,
                    onDeliver = { item -> if (controller != null) act { controller.sendNext(item.id) } },
                    automatic = controller?.autoDispatch == true,
                    onAutomaticChange = if (controller == null) null else { enabled -> setAutomatic(enabled) },
                    canSteer = canSteer, onSteer = { item -> if (controller != null) act { controller.steerNext(item.id) } })
                if (controller != null) CodexGoalStrip(controller, usedLabel = "累计")
                Note(when {
                    controller != null -> controller.note
                    opening -> "正在接回会话…"
                    runtime != null -> "会话还没接回。"
                    workspace.detecting -> "正在检测本机运行器…"
                    unavailable != null -> "Codex 运行器不可用：" + unavailable.problem.ifBlank { "未检测到版本" }
                    !workspace.scanned -> "还没检测本机运行器。"
                    else -> "没有找到使用原数据目录（${record.runtimeHome}）的 Codex 运行器，请重新检测。"
                }, t.textMuted)
                if (!ready) {
                    Note("接回会保留原会话；请先关掉其他窗口里的同一会话。", t.textMuted)
                    // 9.6 = Note 的 16 − chip 的 6.4：chip 的字和上面的提示字对齐
                    Row(Modifier.padding(horizontal = 9.6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CodeFooterChip(if (opening) "正在连接…" else "重新连接", { runtime?.let(::connect) }, enabled = runtime != null && !opening && !tasks.busy)
                        CodeFooterChip(if (workspace.detecting) "正在检测…" else "重新检测运行器", { workspace.refresh() }, enabled = !workspace.detecting && !connecting)
                        CodeFooterChip("只读查看历史", {
                            openNativeHistory(state, NativeHistoryThread(record.threadId, record.title, record.directory, "openai", "appServer", record.createdAt / 1000, "unknown"))
                        }, enabled = !connecting && !workspace.loading && workspace.selectedRuntime?.home?.let { runCatching { File(it).canonicalPath }.getOrNull() } == record.runtimeHome)
                    }
                }
                if (notice.isNotBlank()) Note(notice, t.textMuted)
                if (error.isNotBlank()) Note(error, t.danger)
                // M3d-2b：请求卡停靠在输入卡上方（规格表 §3.3），不进对话滚动区
                if (controller != null) requests.forEach { request -> key(request.get("id").toString()) { CodexRequestCard(request, controller, ::copyPath) { block -> act(block) } } }
                CodeLocalComposer(draft.value, { draft.value = it },
                    hint = when {
                        controller == null || !controller.ready -> if (opening) "正在接回会话…" else "接回会话后可发送"
                        controller.pendingRequests.isNotEmpty() -> "先处理上面的请求；现在发送会先排队"
                        controller.activeTurnId != null -> "本轮进行中；现在发送会先排队"
                        else -> "跟它说点什么… Enter 发送，Shift+Enter 换行；截图直接 Ctrl+V"
                    },
                    focus = focus, canSend = canSend, onSend = ::send,
                    running = controller?.activeTurnId != null, onStop = ::stop,
                    attachments = imagePreviews, onRemoveAttachment = { preview -> imageDrafts.removeAll { it.remotePath == preview.stamp } },
                    onKey = { e -> (isTranscriptViewKey(e) && run { state.cycleTranscriptView(record.key); true }) || (e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed) && pasteImage()) },
                    start = {
                        // 选图是模态框，关掉后焦点还给输入框；没接回也能先选图（发送要等接回）
                        CodeLocalAttachButton(!capturingImages && imageDrafts.size < 4, capturingImages) { addImages(); runCatching { focus.requestFocus() } }
                        if (capturingImages) CodeFooterNote("正在读取图片…", t.textMuted)
                    },
                    end = {
                        if (controller != null && controller.ready) CodeCodexModelMenu(record.key, controller, record.model) { error = it }
                        else (controller?.currentModelLabel?.ifBlank { record.model } ?: record.model).takeIf { it.isNotBlank() }?.let { CodeFooterNote(it) }
                    })
            }
        }
    }
}

/** 运行器请求卡，写法同服务器工作台（CodexWorkspacePane 的 requests 项）；服务器上的 Codex 对话页（Code 外观）也用它。外观仍是现有卡片（M3 统一）。 */
@Composable
internal fun CodexRequestCard(request: JSONObject, controller: CodexTaskController, onOpen: (String) -> Unit, act: (suspend () -> Unit) -> Unit) {
    // M3d-2b：Code 审批卡（规格表 §3.3），停靠在输入卡上方（由面板放进停靠区）。相关的命令 / 改动已经在上面的工具摘要行里，这里不再重复展开活动卡
    val t = Tokens.current
    val method = request.optString("method")
    val command = method == "item/commandExecution/requestApproval"
    val files = method == "item/fileChange/requestApproval"
    val answer: (JSONObject) -> Unit = { response -> act { controller.answerRequest(request.get("id"), response) } }
    val params = request.optJSONObject("params") ?: JSONObject()
    fun text(key: String) = params.optString(key).takeIf { it.isNotBlank() && it != "null" }
    var details by remember(request) { mutableStateOf(false) }
    val title = when {
        command -> "允许运行这条命令？"
        files -> "允许修改这些文件？"
        method == "item/permissions/requestApproval" -> "额外权限申请"
        else -> "运行器需要进一步输入"
    }
    val supported = command || files
    CodeApprovalCard(title,
        deny = if (supported) CodeApprovalAction("拒绝", enabled = controller.ready) { answer(JSONObject().put("decision", "decline")) } else null,
        actions = if (supported) listOf(CodeApprovalAction("仅允许本次", primary = true, enabled = controller.ready) { answer(JSONObject().put("decision", "accept")) }) else emptyList(),
        modifier = Modifier.padding(bottom = 6.4.dp)) {
        text("reason")?.let { CodeApprovalText(it) }
        if (command) {
            text("command")?.let { CodeCommandBlock(it) }
            text("cwd")?.let { Text("目录：$it", color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        if (files) {
            val related = controller.messages.firstOrNull { it.id == params.optString("itemId") && it.kind != "message" }
            related?.paths?.forEach { path ->
                Text(path, Modifier.clickable { onOpen(path) }, color = t.accent, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!supported && text("reason") == null && text("command") == null) CodeApprovalText("请查看请求详情后再决定。")
        CodeTextAction(if (details) "收起详情" else "查看请求详情", { details = !details })
        if (details) CodeCommandBlock(params.toString(2))
        when (method) {
            "item/permissions/requestApproval" -> CodexPermissionRequest(params, controller.ready, answer)
            "mcpServer/elicitation/request" -> CodexMcpElicitationButtons(params, controller.ready, answer)
            "item/tool/requestUserInput" -> CodexQuestionForm(controller, request)
            else -> if (!supported) Text("此类输入暂未接入，可中断当前轮次后调整任务。", color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/**
 * 本机 Codex 的模型 / 思考强度：外观同 [CodeLocalModelMenu]（两个 chip 开同一个 300 宽浮层），语义同服务器的模型选择器：
 * 列表来自运行器的 model/list，选了马上存进会话记录、从下一轮起生效；强度点了就生效，不另设「应用」。运行中也能改（只影响下一轮）。
 * 服务器上的 Codex 对话也用它，传 [onRoutes]：菜单末尾多「当前线路」和「管理第三方线路」（同经典模型选择器）。
 */
@Composable
internal fun CodeCodexModelMenu(taskKey: String, controller: CodexTaskController, fallback: String, onRoutes: (() -> Unit)? = null, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember(taskKey) { mutableStateOf(false) }
    var fromEffort by remember(taskKey) { mutableStateOf(false) }
    val models = controller.models
    val selected = controller.selectedModel?.takeIf { it.isNotBlank() }
    val option = models.firstOrNull { it.model == selected }
    val label = option?.label?.ifBlank { null } ?: selected ?: controller.currentModelLabel.ifBlank { fallback }
    val levels = option?.efforts.orEmpty()
    val enabled = !controller.sending
    fun show(effort: Boolean) {
        fromEffort = effort; open = true
        if (models.isEmpty() && !controller.modelsLoading) scope.launch { controller.refreshModels() }
    }
    fun pick(failure: String, block: () -> Unit) {
        try { block(); onError("") } catch (e: Exception) { onError(e.message ?: failure) }
    }
    NativeOverlay(open)
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodeFooterChip(label.ifBlank { "选择模型" }, { show(false) }, held = open && !fromEffort, enabled = enabled)
            if (levels.isNotEmpty()) CodeFooterChip(controller.selectedEffort?.let(::effortName) ?: "思考强度", { show(true) }, held = open && fromEffort, enabled = enabled)
        }
        if (open) CodePopup({ open = false }, side = PopupSide.Above, alignEnd = true, gap = 2.4.dp, width = 300.dp) {
            if (models.isNotEmpty()) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                models.forEach { m ->
                    CodeMenuItem(m.label.ifBlank { m.model }, { open = false; pick("模型选择未保存") { controller.chooseModel(m.model) } },
                        enabled = enabled, checked = m.model == selected)
                }
            }
            if (levels.isNotEmpty()) {
                if (models.isNotEmpty()) CodeMenuDivider()
                Box(Modifier.padding(start = 8.8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    EffortControl(label, levels, controller.selectedEffort) { effort -> pick("思考强度未保存") { controller.chooseEffort(effort) } }
                }
            }
            if (models.isNotEmpty()) CodeMenuDivider()
            if (controller.activeTurnId != null) CodeMenuNote("本轮仍用原来的设置；从下一轮起生效")
            when {
                controller.modelsLoading -> CodeMenuNote("正在获取模型…")
                controller.modelError.isNotBlank() -> CodeMenuNote(controller.modelError, Tokens.current.danger)
                controller.modelNotice.isNotBlank() -> CodeMenuNote(controller.modelNotice)
            }
            CodeMenuItem("刷新模型列表", { scope.launch { controller.refreshModels() } }, enabled = !controller.modelsLoading)
            if (onRoutes != null) {
                if (controller.configurationLabel.isNotBlank()) CodeMenuNote("当前线路 · " + controller.configurationLabel)
                CodeMenuItem("管理第三方线路", { open = false; onRoutes() })
            }
        }
    }
}
