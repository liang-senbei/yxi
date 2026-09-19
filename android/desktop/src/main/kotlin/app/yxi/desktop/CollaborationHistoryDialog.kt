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
internal fun CollaborationHistoryDialog(state: AppState, conn: Conn, group: String, members: List<String>, onTaskOpened: () -> Unit, close: () -> Unit) {
    var raw by remember(conn) { mutableStateOf("") }
    var truncated by remember(conn) { mutableStateOf(false) }
    var busy by remember(conn) { mutableStateOf(true) }
    var error by remember(conn) { mutableStateOf("") }
    var query by remember(conn) { mutableStateOf("") }
    var revision by remember(conn) { mutableStateOf(0) }
    var structured by remember(conn) { mutableStateOf(true) }
    var replyTarget by remember(conn) { mutableStateOf<Pair<app.yxi.agent.Session, String>?>(null) }
    replyTarget?.let { (target, messageId) ->
        MemberAssignmentDialog(state, conn, target, group, { replyTarget = null }, { replyTarget = null; onTaskOpened() },
            initialText = "请查看协作消息 $messageId，结合当前任务进展回复原发送者。使用 yxi-hub reply 保留原消息关联；如果原任务实例已变化，请说明情况，不要回复同名新任务。\n\n补充要求：")
        return
    }
    LaunchedEffect(conn, revision, structured) {
        busy = true; error = ""; raw = ""; truncated = false
        try {
            val script = """
import pathlib, json
p = pathlib.Path.home() / '.yxi' / '${if (structured) "hub-events.jsonl" else "hub.log"}'
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
            Row {
                FilterChip(!structured, { structured = false }, label = { Text("传统消息日志") })
                Spacer(Modifier.width(8.dp))
                FilterChip(structured, { structured = true }, label = { Text("带 ID 的投递事件") })
            }
            Text(if (structured) "需服务器安装新版 yxi-hub。attempting 为开始投递，terminal-written 为终端写入，unknown 为结果待确认；均不是 Agent 处理回执。" else "服务器 hub.log 原始记录；终端投递不代表 Agent 已处理。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索成员、组名或消息文字") })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            if (truncated) Text("显示最近 256 KiB，开头可能是上一条消息的片段。", style = MaterialTheme.typography.labelSmall)
            if (structured && !busy && error.isBlank() && raw.isBlank()) Text("还没有带编号的消息记录。旧版协作服务的记录可切换到传统消息日志查看。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (structured) HubEventCards(raw, query, requestReply = { id, recipient, instance ->
                val target = conn.sessions.firstOrNull { it.name == recipient && it.runtimeId == instance && it.name in members }
                if (target == null) error = "原接收任务不在当前组或实例已变化，请刷新后核对。"
                else if (runCatching { java.util.UUID.fromString(id) }.isFailure) error = "原消息编号无效"
                else replyTarget = target to id
            }) { query = it }
            else SelectionContainer {
                Text(shown.ifBlank { if (raw.isBlank()) "暂无协作记录" else "没有匹配内容" },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } }, dismissButton = { TextButton({ revision++ }, enabled = !busy) { Text("刷新记录") } })
}
