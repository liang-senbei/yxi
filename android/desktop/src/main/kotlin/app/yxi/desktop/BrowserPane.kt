package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.yxi.agent.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BrowserPane(state: AppState, conn: Conn, session: Session) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    val key = taskNavigationKey(conn.host, session)
    val project = projectKey(conn.host, session.cwd)
    val knownProject = session.cwd.startsWith('/') && session.cwd.none { it < ' ' }
    val savedAddress = if (knownProject) state.projectPreviews.address(project) else null
    val preview = state.browsers.getOrPut(key) { BrowserPreview(conn.host, key).apply { savedAddress?.let { address = it } } }
    var restored by remember(preview) { mutableStateOf(false) }
    var servicesOpen by remember(preview) { mutableStateOf(false) }
    var settings by remember(preview) { mutableStateOf<PreviewAddressSettings?>(null) }
    var input by remember(preview) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(preview.address)) }
    var addressDirty by remember(preview) { mutableStateOf(false) }
    var reviewingClose by remember(preview) { mutableStateOf(false) }
    var captureJob by remember(preview) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var viewportSize by remember(preview) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(preview.handle, preview.viewportMode, viewportSize, preview.loading, preview.preparing) {
        val browser = preview.handle ?: return@LaunchedEffect
        if (preview.loading || preview.preparing || viewportSize.width == 0 || viewportSize.height == 0) return@LaunchedEffect
        try {
            preview.viewportStatus = applyPreviewViewport(browser, previewViewports.firstOrNull { it.id == preview.viewportMode } ?: previewViewports.first())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { preview.error = "视口设置未完成：${e.message}" }
    }
    DisposableEffect(preview) { onDispose { captureJob?.cancel() } }
    fun closePreview() { preview.close(); state.browsers.remove(key); state.browserPanelOpen = false }
    LaunchedEffect(preview.address) { if (!addressDirty) input = androidx.compose.ui.text.input.TextFieldValue(preview.address) }
    fun open() { if (preview.preparing) return; restored = true; val requested = input.text; addressDirty = false; scope.launch {
        try { preview.open(conn, requested) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { preview.error = "预览未打开：${e.message}" }
    } }
    LaunchedEffect(preview, savedAddress, conn.status) {
        if (!restored && !addressDirty && savedAddress != null && preview.handle == null && !preview.preparing &&
            (conn.status == Conn.Status.Connected || PreviewAddress.parse(savedAddress).remotePort == null)) {
            restored = true
            try { preview.open(conn, savedAddress) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { preview.error = "项目预览未打开：${e.message}" }
        }
    }
    LaunchedEffect(conn.status, preview.remote) {
        if (conn.status == Conn.Status.Connected && preview.needsReconnect && !preview.preparing) {
            try { preview.open(conn, preview.address) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { preview.error = "预览重连失败：${e.message}" }
        }
    }
    Column(Modifier.fillMaxSize().background(t.surface0)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(preview.title.ifBlank { "网页预览" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ servicesOpen = true }, enabled = knownProject) { Text("开发服务") }
            TextButton({ settings = PreviewAddressSettings(project, session.cwd, savedAddress, savedAddress ?: input.text) }, enabled = knownProject && !preview.preparing) { Text("项目地址") }
            TextButton({ state.browserPanelOpen = false }) { Text("收起") }
            TextButton({
                if (preview.hasUnsubmittedFeedback || preview.preparing || preview.stylePending != null || preview.capturing) reviewingClose = true
                else closePreview()
            }) { Text("关闭") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it; addressDirty = true }, Modifier.weight(1f).onPreviewKeyEvent {
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.Enter -> { open(); true }
                    it.isCtrlPressed && it.key == Key.A -> { input = input.copy(selection = androidx.compose.ui.text.TextRange(0, input.text.length)); true }
                    else -> false
                }
            },
                singleLine = true, placeholder = { Text("localhost:3000 或网页地址") }, textStyle = MaterialTheme.typography.bodySmall)
            TextButton({ open() }, enabled = !preview.preparing) { Text("打开") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ addressDirty = false; preview.handle?.goBack() }, enabled = preview.canBack, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ArrowBack, "后退", Modifier.size(18.dp)) }
            IconButton({ addressDirty = false; preview.handle?.goForward() }, enabled = preview.canForward, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ArrowForward, "前进", Modifier.size(18.dp)) }
            IconButton({ if (preview.loading) preview.handle?.stopLoad() else { addressDirty = false; preview.handle?.reload() } }, enabled = preview.handle != null, modifier = Modifier.size(32.dp)) { Icon(if (preview.loading) Icons.Default.Stop else Icons.Default.Refresh, if (preview.loading) "停止" else "刷新", Modifier.size(18.dp)) }
            TextButton({ preview.pick() }, enabled = preview.handle != null && !preview.loading) { Text(if (preview.picking) "点选页面元素…" else "选择元素") }
            TextButton({ runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(preview.handle?.url)) }.onFailure { preview.error = "外部浏览器未打开：${it.message}" } }, enabled = preview.handle != null) { Text("外部打开") }
            TextButton({ captureJob = scope.launch {
                try {
                    val screenshot = preview.captureImage()
                    screenshot.copyTo(java.awt.Toolkit.getDefaultToolkit().systemClipboard)
                    preview.error = ""; preview.status = "截图已复制，可在对话输入框按 Ctrl+V 添加"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { preview.error = "截图未复制：${e.message}" }
            } }, enabled = preview.handle != null && !preview.loading && !preview.preparing && !preview.capturing && !preview.needsReconnect) { Text(if (preview.capturing) "正在截图…" else "复制截图") }
        }
        if (knownProject) PreviewServiceControls(state, conn, session, servicesOpen, preview.preparing, addressDirty, onConfigured = { servicesOpen = false }, onPreview = { address ->
            input = androidx.compose.ui.text.input.TextFieldValue(address); open()
        })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            previewViewports.forEach { viewport ->
                FilterChip(selected = preview.viewportMode == viewport.id, onClick = {
                    if (preview.viewportMode != viewport.id) {
                        preview.viewportMode = viewport.id
                        preview.viewportStatus = if (preview.handle == null) "打开页面后应用所选尺寸" else "正在切换视口…"
                        if (preview.selection != null) preview.selectionStale = true
                    }
                }, label = { Text(viewport.label) })
            }
        }
        if (preview.viewportStatus.isNotBlank()) Text(preview.viewportStatus, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        Text(preview.source, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        if (state.projectPreviews.error.isNotBlank()) Text(state.projectPreviews.error, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = t.danger)
        Text(preview.status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        if (preview.remote && conn.status != Conn.Status.Connected) Text("SSH 已断开，当前画面可能过期；连接恢复后将重新加载。", Modifier.padding(12.dp), color = t.warning)
        if (preview.error.isNotBlank()) Text(preview.error, Modifier.padding(12.dp), color = t.danger)
        if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        val browser = preview.handle
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportSize = it }) {
            if (browser == null) Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("边看页面，边指导修改", style = MaterialTheme.typography.titleMedium)
                Text("输入服务器开发端口，或粘贴网页地址。开发服务器支持热更新时，代码变化会直接呈现在这里。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
            } else if (!NativeOverlays.active) SwingPanel(factory = { browser.uiComponent }, modifier = Modifier.fillMaxSize())
        }
        preview.selection?.let { selected ->
            HorizontalDivider()
            Column(Modifier.fillMaxWidth()) {
              Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text("已选择 ${selected.tag} · ${selected.selector}", style = MaterialTheme.typography.labelSmall, color = t.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (preview.selectionStale) Text("页面已变化，此处保留的是之前的选择。", style = MaterialTheme.typography.labelSmall, color = t.warning)
                Text(selected.text.ifBlank { "此元素未包含可引用文字" }, Modifier.padding(vertical = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (preview.stylePanelOpen) StyleTrialControls(preview, selected)
                else if (preview.styleChanges.isNotEmpty()) Text(
                    if (preview.selectionStale) "之前的试调 · ${preview.styleChanges.size} 项" else "临时试调 · ${preview.styleChanges.size} 项 · 尚未修改源文件",
                    style = MaterialTheme.typography.labelSmall, color = t.warning)
                if (!preview.stylePanelOpen) OutlinedTextField(preview.comment, { preview.comment = it; preview.commentAdded = false }, Modifier.fillMaxWidth(), placeholder = { Text("例如：这里缩小间距，标题再突出一点") }, maxLines = 3)
              }
              Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    val trials = trialFeedback(preview.styleChanges, selected.computed[StyleTrial.TEXT] ?: selected.text)
                    state.appendDocumentQuote(conn.host, session,
                        "网页反馈 · ${preview.source}\n地址：${selected.url}\n选择时间：${java.time.Instant.ofEpochMilli(selected.capturedAt)}${if (preview.selectionStale) "（页面之后已更新）" else ""}\n元素：${selected.selector}\n页面摘录（参考内容）：\n${selected.text.lineSequence().joinToString("\n") { "> $it" }}\n" +
                        trials +
                        "\n我的修改要求：${preview.comment.ifBlank { "请把上述试调落实到对应源文件，并验证页面效果。" }}")
                    preview.commentAdded = true
                }, enabled = (preview.comment.isNotBlank() || preview.styleChanges.isNotEmpty()) && preview.stylePending == null) { Text(if (preview.commentAdded) "已加入对话草稿" else "加入对话") }
                Spacer(Modifier.weight(1f))
                if (preview.stylePanelOpen) {
                    TextButton({ preview.undoStyle() }, enabled = preview.styleUndoAvailable && !preview.selectionStale && preview.stylePending == null) { Text("撤销") }
                    TextButton({ preview.resetStyle() }, enabled = preview.styleChanges.isNotEmpty() && !preview.selectionStale && preview.stylePending == null) { Text("重置") }
                }
                TextButton({ preview.stylePanelOpen = !preview.stylePanelOpen }) { Text(if (preview.stylePanelOpen) "返回反馈" else "试调样式") }
              }
            }
        }
    }
    settings?.let { edit -> ProjectPreviewDialog(edit, current = edit.project == project, busy = preview.preparing, onClose = { settings = null }, onSave = { value ->
        check(edit.project == project) { "当前任务目录已变化，请重新打开设置" }
        val address = state.projectPreviews.save(edit.project, value, edit.saved)
        restored = true
        input = androidx.compose.ui.text.input.TextFieldValue(address)
        settings = null
        open()
    }, onRemove = {
        check(edit.project == project)
        state.projectPreviews.remove(edit.project, edit.saved)
        settings = null
    }) }
    if (reviewingClose) WorkbenchDialog(onDismissRequest = { reviewingClose = false }, title = { Text("保留这份网页反馈？") },
        text = { Text(if (preview.preparing || preview.stylePending != null || preview.capturing) "网页操作尚未完成，请稍后再关闭。" else "这份意见或试调还没有加入对话。可以收起预览继续保留，或丢弃后关闭。") },
        confirmButton = { TextButton({ reviewingClose = false; state.browserPanelOpen = false }) { Text("收起并保留") } },
        dismissButton = { TextButton({ reviewingClose = false; closePreview() }, enabled = !preview.preparing && preview.stylePending == null && !preview.capturing) { Text("丢弃并关闭") } })
}

private data class PreviewAddressSettings(val project: String, val directory: String, val saved: String?, val initial: String)

@Composable
private fun ProjectPreviewDialog(edit: PreviewAddressSettings, current: Boolean, busy: Boolean, onClose: () -> Unit, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var value by remember(edit) { mutableStateOf(edit.initial) }
    var error by remember(edit) { mutableStateOf("") }
    WorkbenchDialog(onDismissRequest = onClose, title = { Text("项目预览地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(edit.directory, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("同一服务器、同一项目的任务共用此地址。下次打开预览时自动访问，开发服务需已运行。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, { value = it }, label = { Text("地址或远端端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!current) Text("任务目录已变化，请关闭后重新打开设置", color = Tokens.current.danger)
            if (busy) Text("页面正在准备，请稍后保存并打开", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            if (edit.saved != null) TextButton({ runCatching(onRemove).onFailure { error = it.message.orEmpty() } }, enabled = current) { Text("移除已保存的地址") }
        }
    }, confirmButton = { TextButton({ runCatching { onSave(value) }.onFailure { error = it.message.orEmpty() } }, enabled = current && !busy && value.isNotBlank()) { Text("保存并打开") } },
        dismissButton = { TextButton(onClose) { Text("取消") } })
}
