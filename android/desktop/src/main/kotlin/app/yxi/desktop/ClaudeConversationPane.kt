package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.yxi.agent.ChatItem
import java.io.File

@Composable internal fun NewClaudeConversationDialog(state: AppState, runtime: LocalRuntimeInstallation, dismiss: () -> Unit,
    modifier: Modifier = Modifier,
    created: (LocalCodexTaskRecord) -> Unit) {
    val scope = rememberCoroutineScope()
    var directory by remember(runtime.id) { mutableStateOf(System.getProperty("user.home")) }
    var title by remember(runtime.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    WorkbenchDialog(onDismissRequest = dismiss, modifier = modifier, title = { Text("新建本地 Claude 对话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("官方订阅 · 使用此运行器的原生登录和项目权限")
            Text(runtime.command.joinToString(" "), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("本机工作目录") })
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("对话名称") })
            Text("先核对身份和有效官方端点，再创建对话。创建不会自动发送消息。", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { TextButton({ if (!busy) {
        busy = true; error = ""; val path = directory; val name = title
        scope.launch {
            try { created(state.localClaudeTasks.create(runtime, path, name)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "创建未完成" }
            finally { busy = false }
        }
    } }, enabled = !busy && !state.localClaudeTasks.busy && directory.isNotBlank()) { Text("创建对话") } },
        dismissButton = { TextButton(dismiss) { Text(if (busy) "取消创建" else "取消") } })
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun ClaudeConversationPane(state: AppState, record: LocalCodexTaskRecord,
    installations: List<LocalRuntimeInstallation> = state.localWorkspace.installations, historyPagingModifier: Modifier = Modifier) {
    val controller = state.localClaudeTasks.controllers[record.key]
    val scope = rememberCoroutineScope()
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    var error by remember(record.key) { mutableStateOf("") }
    var sending by remember(record.key) { mutableStateOf(false) }
    var resuming by remember(record.key) { mutableStateOf(false) }
    val responding = remember(record.key) { mutableStateListOf<String>() }
    val view = remember(record.key) { state.codexConversationViews.getOrPut(record.key) { CodexConversationView() } }
    var followLatest by view.followLatest
    val dragging by view.scroll.interactionSource.collectIsDraggedAsState()
    var previousPosition by remember(record.key) { mutableStateOf(0 to 0) }
    val messages = controller?.messages?.toList().orEmpty()
    val approvals = controller?.pendingApprovals?.values?.toList().orEmpty()
    var history by remember(record.key, controller) { mutableStateOf<List<ChatItem>>(emptyList()) }
    var historyCursor by remember(record.key, controller) { mutableStateOf<LocalClaudeHistory.Cursor?>(null) }
    var historyLoading by remember(record.key, controller) { mutableStateOf(false) }
    var historyPartial by remember(record.key, controller) { mutableStateOf(false) }
    var historyError by remember(record.key, controller) { mutableStateOf("") }
    var historyLoadJob by remember(record.key, controller) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var historyRefresh by remember(record.key) { mutableStateOf(0) }
    DisposableEffect(record.key, controller) { onDispose { historyLoadJob?.cancel() } }
    LaunchedEffect(record.key, controller, historyRefresh) {
        if (controller == null) {
            historyLoading = true; historyError = ""
            try {
                val page = withContext(Dispatchers.IO) { LocalClaudeHistory.page(record) }
                history = page.items; historyCursor = page.earlier; historyPartial = page.partialTail
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { historyError = e.message ?: "历史读取失败" }
            finally { historyLoading = false }
        } else {
            history = controller.historyPage?.items ?: controller.history
            historyCursor = controller.historyPage?.earlier; historyPartial = controller.historyPage?.partialTail == true
            historyError = ""
        }
    }
    fun loadEarlier() {
        val cursor = historyCursor ?: return
        if (historyLoading) return
        historyLoading = true; historyError = ""; followLatest = false
        val capturedIndex = view.scroll.firstVisibleItemIndex
        val anchorKey = view.scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        val anchorOffset = view.scroll.firstVisibleItemScrollOffset
        historyLoadJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { LocalClaudeHistory.page(record, cursor) }
                check(page.items.none { earlier -> history.any { it.key == earlier.key } }) { "历史分页出现重复，请重新加载" }
                val merged = page.items + history
                history = merged; historyCursor = page.earlier; historyPartial = page.partialTail
                // Item zero is the stable paging control; all message keys remain unchanged when rows prepend.
                val anchor = merged.indexOfFirst { "history:" + it.key == anchorKey }
                if (anchor >= 0) view.scroll.scrollToItem(anchor + 1, anchorOffset)
                else if (capturedIndex == 0) view.scroll.scrollToItem(0)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { historyError = e.message ?: "较早历史读取失败" }
            finally { historyLoading = false }
        }
    }
    LaunchedEffect(view, record.key) {
        snapshotFlow { Triple(view.scroll.firstVisibleItemIndex to view.scroll.firstVisibleItemScrollOffset,
            view.scroll.isScrollInProgress, view.scroll.canScrollForward) }.collect { (position, scrolling, forward) ->
            if (scrolling && (position.first < previousPosition.first || position.first == previousPosition.first && position.second < previousPosition.second)) followLatest = false
            if (!forward && !dragging && view.scroll.layoutInfo.totalItemsCount > 0) followLatest = true
            previousPosition = position
        }
    }
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    LaunchedEffect(history.size, messages.size, approvals.size, followLatest) {
        if (followLatest && !dragging) view.scroll.scrollToItem(1 + history.size + messages.size + approvals.size, view.scroll.layoutInfo.viewportSize.height.coerceAtLeast(1))
    }
    fun send() {
        if (controller == null || !controller.ready || controller.busy || controller.cancelling || controller.changingModel || sending || controller.pendingApprovals.isNotEmpty() || draft.value.text.isBlank()) return
        try { controller.enqueue(draft.value.text) } catch (e: Exception) { error = e.message.orEmpty(); return }
        draft.value = TextFieldValue(); error = ""; sending = true
        val job = controller.dispatchNext()
        scope.launch { try { job.join() } finally { sending = false } }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton({ state.localSelectedTaskKey = null }) { Text("返回本地") }
        Text(state.navigation.title(record.key) ?: record.title, style = MaterialTheme.typography.headlineSmall)
        Text("Claude · ${controller?.model?.ifBlank { record.model } ?: record.model} · ${record.directory}", style = MaterialTheme.typography.bodySmall)
        if (controller != null && controller.availableModels.isNotEmpty()) {
            var modelMenu by remember(record.key) { mutableStateOf(false) }
            Box {
                TextButton({ modelMenu = true }, enabled = controller.ready && !controller.busy && !controller.cancelling && !controller.changingModel && controller.pendingApprovals.isEmpty()) {
                    Text(if (controller.changingModel) "正在应用设置…" else "选择模型")
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    controller.availableModels.forEach { model ->
                        DropdownMenuItem(text = { Text(model) },
                            modifier = Modifier.padding(horizontal = 6.dp).then(if (model == controller.model)
                                Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)) else Modifier),
                            onClick = {
                            modelMenu = false
                            scope.launch { try { controller.selectModel(model) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "模型切换未确认" } }
                        }, trailingIcon = { if (model == controller.model) Text("✓") })
                    }
                }
            }
        }
        if (controller != null && controller.effortLevels.isNotEmpty()) {
            ClaudeEffortSettings(controller.model, controller.effortLevels, controller.effort,
                enabled = controller.ready && !controller.busy && !controller.cancelling && controller.pendingApprovals.isEmpty(),
                applying = controller.changingModel) { choice ->
                scope.launch {
                    try { controller.selectEffort(choice) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "思考强度未确认" }
                }
            }
        }
        Text(controller?.note ?: "连接未恢复；此记录不会自动重发指令。")
        if (controller?.ready != true && controller?.busy != true && controller?.cancelling != true && controller?.changingModel != true) {
            ClaudeResumeActions(record, installations,
                busy = resuming || state.localClaudeTasks.busy,
                detecting = state.localWorkspace.detecting,
                refresh = { state.localWorkspace.refresh() }) { runtime ->
                if (!resuming) {
                    resuming = true; error = ""
                    scope.launch {
                        try { state.localClaudeTasks.resume(runtime, record.key) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "恢复未完成" }
                        finally { resuming = false }
                    }
                }
            }
        }
        if (historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (historyPartial) Text("读取时历史末尾尚未写完，当前仅显示完整记录。", style = MaterialTheme.typography.bodySmall)
        if (historyError.isNotBlank()) {
            Text(historyError, color = Tokens.current.danger)
            if (controller == null) TextButton({ historyRefresh++ }, enabled = !historyLoading) { Text("重新加载历史") }
        }
        InstructionStrip(state.instructions, record.key, controller?.ready == true && !controller.busy && !controller.cancelling && !controller.changingModel && controller.pendingApprovals.isEmpty(), { controller?.dispatchNext() })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
            if (event.changes.any { it.scrollDelta.y < 0f }) followLatest = false
        }, state = view.scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "history-paging") {
                if (historyCursor != null) TextButton(::loadEarlier, modifier = historyPagingModifier, enabled = !historyLoading) { Text(if (historyLoading) "正在加载…" else "加载更早消息") }
            }
            items(history, key = { "history:" + it.key }) { LocalClaudeHistoryItem(it) }
            items(messages, key = { it.id }) { message ->
                Column {
                    Text(when (message.role) { "User" -> "你"; "Assistant" -> "Claude"; else -> "工具" }, style = MaterialTheme.typography.labelLarge)
                    if (message.role == "Assistant" && message.kind == "text") AssistantBody(message.text)
                    else SelectionContainer { Text(message.text) }
                }
            }
            items(approvals, key = { "permission:" + it.getString("request_id") }) { approval ->
                val id = approval.getString("request_id"); val request = approval.getJSONObject("request")
                fun answer(allow: Boolean) {
                    if (id in responding || controller == null || !controller.ready || controller.cancelling) return
                    responding.add(id)
                    scope.launch { try { controller.answerPermission(id, allow) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message.orEmpty() }
                        finally { responding.remove(id) } }
                }
                ClaudePermissionCard(request,
                    enabled = controller?.ready == true && !controller.cancelling,
                    responding = id in responding,
                    onAllowOnce = { answer(true) }, onDeny = { answer(false) })
            }
            item { Spacer(Modifier.height(1.dp)) }
        }
        if (!followLatest) TextButton({ followLatest = true }) { Text("回到最新消息 ↓") }
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        OutlinedTextField(draft.value, { draft.value = it }, Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && draft.value.composition == null && (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed) { send(); true } else false
        }, label = { Text("消息 · Enter 发送，Shift+Enter 换行") }, minLines = 2, maxLines = 6)
        Row {
            TextButton(::send, enabled = controller?.ready == true && !controller.busy && !controller.cancelling && !controller.changingModel && !sending && controller.pendingApprovals.isEmpty() && draft.value.text.isNotBlank()) { Text("发送") }
            TextButton({ scope.launch { try { controller?.cancelTurn() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "停止结果未确认" } } },
                enabled = controller?.busy == true && !controller.cancelling) { Text(if (controller?.cancelling == true) "停止中…" else "停止") }
        }
    }
}

@Composable internal fun ClaudeResumeActions(record: LocalCodexTaskRecord, installations: List<LocalRuntimeInstallation>,
    busy: Boolean, detecting: Boolean, refresh: () -> Unit, resume: (LocalRuntimeInstallation) -> Unit) {
    val candidates = remember(record.runtimeHome, installations) {
        installations.filter { runtime -> runtime.engine == "claude" && runtime.ready &&
            runCatching { File(runtime.home).canonicalPath == File(record.runtimeHome).canonicalPath }.getOrDefault(false) }
    }
    var selectedId by remember(record.key) { mutableStateOf<String?>(null) }
    val selected = candidates.singleOrNull() ?: candidates.singleOrNull { it.id == selectedId }
    var open by remember(record.key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("恢复会保留原会话；请先停止其他窗口中的同一会话。", style = MaterialTheme.typography.bodySmall)
        if (candidates.isEmpty()) Text("尚未找到匹配原配置目录的 Claude 运行器。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (candidates.size > 1) Box {
                TextButton({ open = true }, enabled = !busy && !detecting) { Text(selected?.let { "${it.source} · ${it.version}" } ?: "选择原运行器") }
                DropdownMenu(open, { open = false }) {
                    candidates.forEach { runtime -> DropdownMenuItem(text = { Text("${runtime.source} · ${runtime.version} · ${runtime.command.firstOrNull().orEmpty()}") },
                        onClick = { selectedId = runtime.id; open = false }) }
                }
            }
            TextButton({ selected?.let(resume) }, enabled = !busy && !detecting && selected != null) { Text(if (busy) "正在恢复…" else "恢复此会话") }
            TextButton(refresh, enabled = !busy && !detecting) { Text(if (detecting) "正在检测…" else "重新检测运行器") }
        }
    }
}
