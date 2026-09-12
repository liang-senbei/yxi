package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mikepenz.markdown.m3.Markdown
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun DocumentPane(state: AppState, conn: Conn, task: String) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    val docs = state.documents.filter { it.hostId == conn.host.id && it.task == task }
    val selected = docs.firstOrNull { it.path == state.documentSelection[conn.host.id + "\u0000" + task] } ?: docs.lastOrNull()
    var closing by remember { mutableStateOf<FileDocument?>(null) }
    fun close(doc: FileDocument) { state.documents.remove(doc) }
    Column(Modifier.fillMaxSize().background(t.surface0)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("文件预览", Modifier.weight(1f).padding(10.dp), style = MaterialTheme.typography.titleSmall)
            TextButton({ state.filePanelOpen = false }) { Text("收起") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            docs.forEach { doc ->
                Row(Modifier.background(if (doc === selected) t.selected else t.surface1), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ state.documentSelection[conn.host.id + "\u0000" + task] = doc.path }) {
                        Text(doc.path.substringAfterLast('/') + if (doc.dirty) " •" else "", maxLines = 1)
                    }
                    TextButton({ if (doc.dirty) closing = doc else close(doc) }) { Text("×") }
                }
            }
        }
        HorizontalDivider()
        if (selected == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("从文件列表或对话链接打开文件") }
        else key(selected) {
            val fallback = LocalUriHandler.current
            val handler = object : UriHandler {
                override fun openUri(uri: String) {
                    val parsed = runCatching { java.net.URI(uri) }.getOrNull()
                    if (parsed != null && parsed.scheme == null && !parsed.path.isNullOrBlank()) {
                        val path = if (parsed.path.startsWith('/')) parsed.path else selected.path.substringBeforeLast('/') + "/" + parsed.path
                        scope.launch {
                            try {
                                val session = conn.sessions.firstOrNull { it.name == task } ?: error("任务已不在服务器上")
                                state.openDocument(conn, session, path)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { selected.error = "链接无法打开：${e.message}" }
                        }
                    } else fallback.openUri(uri)
                }
            }
            CompositionLocalProvider(LocalUriHandler provides handler) {
                DocumentBody(conn, selected) { quote -> state.appendDocumentQuote(conn.host.id, task, quote) }
            }
        }
    }
    closing?.let { doc ->
        AlertDialog(onDismissRequest = { closing = null }, title = { Text("有尚未保存的编辑") },
            text = { Text(doc.path + "\n关闭会丢弃这份编辑。也可以收起面板保留草稿。") },
            confirmButton = { TextButton({ close(doc); closing = null }) { Text("丢弃并关闭") } },
            dismissButton = { TextButton({ closing = null }) { Text("继续编辑") } })
    }
}

@Composable
private fun DocumentBody(conn: Conn, doc: FileDocument, onQuote: (String) -> Unit) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var pathCopied by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var quoteAdded by remember { mutableStateOf(false) }
    var quoteRange by remember { mutableStateOf(androidx.compose.ui.text.TextRange.Zero) }
    var quoteText by remember { mutableStateOf("") }
    LaunchedEffect(doc.editor) {
        if (!doc.editor.selection.collapsed) { quoteRange = doc.editor.selection; quoteText = doc.editor.text; quoteAdded = false }
        else if (doc.editor.text != quoteText) { quoteRange = androidx.compose.ui.text.TextRange.Zero; quoteText = ""; quoteAdded = false }
    }
    fun save() { scope.launch { RemoteDocuments.save(conn, doc) } }
    LaunchedEffect(conn, doc) {
        while (true) { RemoteDocuments.refresh(conn, doc); delay(2500) }
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent {
        when {
            it.type != KeyEventType.KeyDown || !it.isCtrlPressed -> false
            it.key == Key.S && doc.dirty && !doc.conflict && !doc.busy -> { save(); true }
            it.key == Key.A && doc.mode != "预览" -> {
                doc.editor = doc.editor.copy(selection = androidx.compose.ui.text.TextRange(0, doc.editor.text.length)); true
            }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer { Text(conn.host.label + " · " + doc.path, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (!doc.image) WorkbenchTabs(listOf("预览", "源码", "分栏"), doc.mode, { doc.mode = it })
            TextButton({ save() }, enabled = doc.dirty && !doc.busy && !doc.conflict) { Text(if (doc.busy) "保存中" else "保存") }
            TextButton({ Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(doc.path), null); pathCopied = true }) { Text(if (pathCopied) "已复制" else "复制路径") }
            TextButton({
                val dialog = FileDialog(null as java.awt.Frame?, "另存为本地副本", FileDialog.SAVE)
                dialog.file = doc.path.substringAfterLast('/'); dialog.isVisible = true
                dialog.file?.let { name ->
                    runCatching { File(dialog.directory, name).writeBytes(if (doc.image) doc.base?.bytes ?: byteArrayOf() else doc.editor.text.toByteArray()) }
                        .onFailure { doc.error = "另存为失败：${it.message}" }
                }
            }, enabled = doc.base != null) { Text("另存为") }
        }
        val timestamp = if (doc.loadedAt == 0L) "" else " · " + SimpleDateFormat("HH:mm:ss").format(Date(doc.loadedAt))
        Text((if (doc.dirty && !doc.conflict) "未保存" else doc.status) + timestamp, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        if (doc.error.isNotBlank()) Text(doc.error, Modifier.padding(10.dp), color = t.danger)
        if (doc.conflict) {
            Text("服务器版本已变化。你的编辑已保留；可在下方比较，手工合并后再次保存。", Modifier.padding(10.dp), color = t.warning)
            Row {
                TextButton({ discard = true }) { Text("放弃编辑，载入远端") }
                TextButton({
                    // Explicitly adopt the observed remote revision as the merge base, preserving the user's editor buffer.
                    doc.base = doc.incoming; doc.incoming = null; doc.status = "已采用远端基线，请完成合并后保存"
                }) { Text("保留编辑，以此版本继续合并") }
            }
            SelectionContainer { Text(doc.incoming?.let { decodeDocument(it.bytes) }.orEmpty(), Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(10.dp), style = CodeStyle) }
        }
        HorizontalDivider()
        if (doc.base == null) { Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { if (doc.error.isBlank()) CircularProgressIndicator() } }
        else if (doc.image) {
            val bitmap = remember(doc.base?.hash) { runCatching { androidx.compose.ui.res.loadImageBitmap(ByteArrayInputStream(doc.base!!.bytes)) }.getOrNull() }
            if (bitmap == null) Text("图片无法解码，请下载查看", Modifier.padding(12.dp))
            else Image(bitmap, doc.path, Modifier.weight(1f).fillMaxWidth().padding(10.dp))
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (doc.mode != "预览") {
                    val scroll = rememberScrollState()
                    Row(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                        Text((1..doc.editor.text.count { it == '\n' } + 1).joinToString("\n"), style = CodeStyle, color = t.textMuted, modifier = Modifier.padding(end = 10.dp))
                        BasicTextField(doc.editor, { doc.editor = it }, Modifier.widthIn(min = 280.dp), textStyle = CodeStyle.copy(color = t.textPrimary), cursorBrush = SolidColor(t.accent))
                    }
                }
                if (doc.mode == "分栏") VerticalDivider()
                if (doc.mode != "源码") {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SelectionContainer {
                            if (doc.path.substringAfterLast('.').lowercase() in setOf("md", "markdown")) Markdown(doc.editor.text, typography = workbenchMarkdownTypography())
                            else Text(doc.editor.text, style = CodeStyle)
                        }
                    }
                }
            }
            TextButton({
                val first = quoteRange.min; val last = quoteRange.max
                val line = quoteText.take(first).count { it == '\n' } + 1
                val excerpt = quoteText.substring(first, last)
                onQuote("引用 ${conn.host.label}:${doc.path}:$line（版本 ${doc.base!!.hash.take(12)}${if (doc.dirty) "，未保存编辑" else ""}）\n\n$excerpt")
                quoteAdded = true
            }, enabled = !quoteRange.collapsed && quoteRange.max - quoteRange.min <= 16000) { Text(if (quoteAdded) "已加入对话草稿" else "引用选中内容到对话") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("丢弃本地编辑？") },
        text = { Text("将用当前服务器版本替换编辑内容。") },
        confirmButton = { TextButton({ doc.useIncoming(); discard = false }) { Text("载入远端版本") } },
        dismissButton = { TextButton({ discard = false }) { Text("取消") } })
}
