package app.yxi.desktop

import androidx.compose.foundation.layout.*
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
@Composable internal fun ClaudeConversationPane(state: AppState, record: LocalCodexTaskRecord) {
    val controller = state.localClaudeTasks.controllers[record.key]
    val scope = rememberCoroutineScope()
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    var error by remember(record.key) { mutableStateOf("") }
    var sending by remember(record.key) { mutableStateOf(false) }
    val responding = remember(record.key) { mutableStateListOf<String>() }
    val view = remember(record.key) { state.codexConversationViews.getOrPut(record.key) { CodexConversationView() } }
    var followLatest by view.followLatest
    val dragging by view.scroll.interactionSource.collectIsDraggedAsState()
    var previousPosition by remember(record.key) { mutableStateOf(0 to 0) }
    val messages = controller?.messages?.toList().orEmpty()
    val approvals = controller?.pendingApprovals?.values?.toList().orEmpty()
    LaunchedEffect(view, record.key) {
        snapshotFlow { Triple(view.scroll.firstVisibleItemIndex to view.scroll.firstVisibleItemScrollOffset,
            view.scroll.isScrollInProgress, view.scroll.canScrollForward) }.collect { (position, scrolling, forward) ->
            if (scrolling && (position.first < previousPosition.first || position.first == previousPosition.first && position.second < previousPosition.second)) followLatest = false
            if (!forward && !dragging && view.scroll.layoutInfo.totalItemsCount > 0) followLatest = true
            previousPosition = position
        }
    }
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    LaunchedEffect(messages.size, approvals.size, followLatest) {
        if (followLatest && !dragging) view.scroll.scrollToItem(messages.size + approvals.size, view.scroll.layoutInfo.viewportSize.height.coerceAtLeast(1))
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
                    Text(if (controller.changingModel) "正在切换模型…" else "选择模型")
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    controller.availableModels.forEach { model ->
                        DropdownMenuItem(text = { Text(model) }, onClick = {
                            modelMenu = false
                            scope.launch { try { controller.selectModel(model) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "模型切换未确认" } }
                        }, trailingIcon = { if (model == controller.model) Text("✓") })
                    }
                }
            }
        }
        Text(controller?.note ?: "连接未恢复；此记录不会自动重发指令。")
        InstructionStrip(state.instructions, record.key, controller?.ready == true && !controller.busy && !controller.cancelling && !controller.changingModel && controller.pendingApprovals.isEmpty(), { controller?.dispatchNext() })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
            if (event.changes.any { it.scrollDelta.y < 0f }) followLatest = false
        }, state = view.scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
