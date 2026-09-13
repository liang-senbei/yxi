package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Groups
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
fun CollaborationDialog(state: AppState, conn: Conn, close: () -> Unit) {
    var table by remember(conn) { mutableStateOf<Groups.Table?>(null) }
    var error by remember(conn) { mutableStateOf("") }
    var busy by remember(conn) { mutableStateOf(true) }
    var revision by remember(conn) { mutableStateOf(0) }
    var selected by remember(conn) { mutableStateOf("") }
    LaunchedEffect(conn, revision) {
        busy = true; error = ""
        try {
            val raw = conn.ssh.exec("if [ -f \"\$HOME/.yxi/groups.json\" ]; then cat \"\$HOME/.yxi/groups.json\"; else printf '%s' '{\"version\":1,\"groups\":{}}'; fi")
            require(JSONObject(raw).optJSONObject("groups") != null) { "服务器分组文件格式无法读取" }
            table = Groups.parse(raw)
            if (selected !in table!!.groups) selected = table!!.groups.keys.sorted().firstOrNull().orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { busy = false }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text("协作组 · ${conn.host.label}") }, text = {
        Column(Modifier.widthIn(max = 640.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务器上的分组与组规", style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            table?.let { data ->
                if (data.groups.isEmpty()) Text("此服务器尚未配置协作组。", color = Tokens.current.textMuted)
                data.groups.keys.sorted().forEach { name ->
                    FilterChip(selected == name, { selected = name }, label = { Text("$name · ${data.groups[name].orEmpty().size} 位成员") })
                }
                if (selected in data.groups) {
                    HorizontalDivider()
                    Text(selected, style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(data.rules[selected].orEmpty().ifBlank { "尚未设置组规" }, style = MaterialTheme.typography.bodySmall) }
                    data.groups[selected].orEmpty().forEach { name ->
                        val session = conn.sessions.firstOrNull { it.name == name }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(session?.let { state.navigation.title(taskNavigationKey(conn.host, it)) } ?: name, style = MaterialTheme.typography.titleSmall)
                                Text(if (session == null) "当前会话列表未找到 · 可能离线或已结束" else "${session.state} · ${if (session.isCodex) "Codex" else "Claude Code"}", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                                if (session != null) {
                                    Text(session.cwd, style = MaterialTheme.typography.bodySmall)
                                    TextButton({ state.select(conn, session); close() }) { Text("打开成员任务") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } }, dismissButton = {
        TextButton({ revision++ }, enabled = !busy) { Text("刷新分组") }
    })
}
