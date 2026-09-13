package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun CollaborationHistoryDialog(conn: Conn, close: () -> Unit) {
    var raw by remember(conn) { mutableStateOf("") }
    var truncated by remember(conn) { mutableStateOf(false) }
    var busy by remember(conn) { mutableStateOf(true) }
    var error by remember(conn) { mutableStateOf("") }
    var query by remember(conn) { mutableStateOf("") }
    var revision by remember(conn) { mutableStateOf(0) }
    LaunchedEffect(conn, revision) {
        busy = true; error = ""
        try {
            val script = """
import pathlib, json
p = pathlib.Path.home() / '.yxi' / 'hub.log'
text, clipped = '', False
if p.exists():
    with p.open('rb') as f:
        f.seek(0, 2)
        size = f.tell()
        clipped = size > 262144
        f.seek(max(0, size - 262144))
        text = f.read(262144).decode('utf-8', errors='replace')
print('__YXI_HUB_LOG__:' + json.dumps({'text': text, 'clipped': clipped}, ensure_ascii=False))
""".trimIndent()
            val result = conn.ssh.exec("python3 -c " + Shell.q(script))
            val record = result.lineSequence().lastOrNull { it.startsWith("__YXI_HUB_LOG__:") } ?: error("服务器未返回协作日志")
            val json = JSONObject(record.removePrefix("__YXI_HUB_LOG__:"))
            raw = json.getString("text"); truncated = json.getBoolean("clipped")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { busy = false }
    }
    // Legacy log is multiline plain text; retain surrounding lines rather than invent message receipts.
    val shown = remember(raw, query) {
        if (query.isBlank()) raw else {
            val lines = raw.lines()
            val indices = lines.indices.filter { lines[it].contains(query.trim(), ignoreCase = true) }
                .flatMap { ((it - 2).coerceAtLeast(0)..(it + 3).coerceAtMost(lines.lastIndex)).toList() }.distinct().sorted()
            buildString {
                var previous = -2
                indices.forEach { index ->
                    if (index > previous + 1) append("\n…\n")
                    append(lines[index]).append('\n'); previous = index
                }
            }
        }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text("协作记录 · ${conn.host.label}") }, text = {
        Column(Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务器 hub.log 原始记录；终端投递不代表 Agent 已处理。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索成员、组名或消息文字") })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            if (truncated) Text("显示最近 256 KiB，开头可能是上一条消息的片段。", style = MaterialTheme.typography.labelSmall)
            SelectionContainer {
                Text(shown.ifBlank { if (raw.isBlank()) "暂无协作记录" else "没有匹配内容" },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } }, dismissButton = { TextButton({ revision++ }, enabled = !busy) { Text("刷新记录") } })
}
