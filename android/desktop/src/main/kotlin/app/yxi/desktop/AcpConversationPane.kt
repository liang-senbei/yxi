package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable internal fun NewAcpConversationDialog(state: AppState, runtime: LocalRuntimeInstallation, dismiss: () -> Unit, created: (LocalCodexTaskRecord) -> Unit) {
    val tasks = state.localAcpTasks
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(System.getProperty("user.home")) }
    var title by remember { mutableStateOf("") }
    var connectedDirectory by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var reviewed by remember(tasks.recoverySessionId) { mutableStateOf(false) }
    DisposableEffect(tasks) { onDispose { tasks.abandonPreparation() } }
    WorkbenchDialog(onDismissRequest = { if (!tasks.busy) dismiss() }, title = { Text("本地 · ${LocalRuntimeDiscovery.title(runtime.engine)}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ACP 接入预览 · 使用运行器自己的账号与配置；创建后不会自动发送消息。", style = MaterialTheme.typography.bodySmall)
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
                    TextButton({ scope.launch { error = ""; runCatching { tasks.authenticate(method.getString("id")) }.onFailure { error = it.message.orEmpty() } } }, enabled = !tasks.busy) {
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

@Composable internal fun AcpConversationPane(state: AppState, record: LocalCodexTaskRecord) {
    val controller = state.localAcpTasks.controllers[record.key]
    val scope = rememberCoroutineScope()
    val draft = remember(record.key) { state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) } }
    var error by remember(record.key) { mutableStateOf("") }
    var sending by remember(record.key) { mutableStateOf(false) }
    var modeMenu by remember(record.key) { mutableStateOf(false) }
    var configMenu by remember(record.key) { mutableStateOf<String?>(null) }
    val view = remember(record.key) { state.codexConversationViews.getOrPut(record.key) { CodexConversationView() } }
    var followLatest by view.followLatest
    val messages = controller?.messages?.toList().orEmpty()
    val approvals = controller?.pendingApprovals?.values?.toList().orEmpty()
    val scrollWatch = remember(record.key) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && !view.scroll.canScrollForward) followLatest = true
            return Offset.Zero
        }
    } }
    LaunchedEffect(messages, approvals.size, followLatest) {
        if (followLatest) view.scroll.requestScrollToItem(messages.size + approvals.size)
    }
    fun send() {
        if (controller == null || !controller.ready || sending || controller.busy || controller.changingMode || controller.pendingApprovals.isNotEmpty() || draft.value.text.isBlank()) return
        try { controller.enqueue(draft.value.text) } catch (e: Exception) { error = e.message.orEmpty(); return }
        draft.value = TextFieldValue(); error = ""; sending = true
        val delivery = controller.dispatchNext()
        scope.launch { try { delivery.join() } finally { sending = false } }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            TextButton({ state.localSelectedTaskKey = null }) { Text("返回本地") }
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
                        scope.launch { runCatching { controller.changeConfig(selector.id, option.id) }.onFailure { error = it.message.orEmpty() } }
                    }) }
                }
            } }
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
                        scope.launch { runCatching { controller.changeMode(option.getString("id")) }.onFailure { error = it.message.orEmpty() } }
                    }) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollWatch), state = view.scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { "message:${it.id}" }) { message ->
                Column {
                    Text(message.author + if (message.status.isBlank()) "" else " · ${message.status}", style = MaterialTheme.typography.labelLarge)
                    if (message.author == "Assistant" && message.kind == "message") AssistantBody(message.text)
                    else SelectionContainer { Text(message.text) }
                }
            }
            items(approvals, key = { "approval:" + org.json.JSONArray().put(it.get("id")).toString() }) { request ->
                AcpPermissionCard(request.getJSONObject("params"), controller.ready && !controller.cancelling) { option ->
                    scope.launch { runCatching { controller.answerPermission(request.get("id"), option) }.onFailure { error = it.message.orEmpty() } }
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
            TextButton({ scope.launch { runCatching { controller.cancelTurn() }.onFailure { error = it.message.orEmpty() } } },
                enabled = controller.busy && !controller.cancelling) { Text("停止") }
        }
    }
}
