package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CodexAgentConfigurationDialog(state: AppState, conn: Conn, record: CodexTaskRecord, dismiss: () -> Unit) {
    var profiles by remember { mutableStateOf<List<Lines.Line>>(emptyList()) }
    var engine by remember { mutableStateOf(Lines.CODEX) }
    var selected by remember { mutableStateOf(record.profileId) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        loading = true; error = ""
        try { profiles = Lines.list(conn.ssh) ?: error("无法读取此服务器已保存的配置") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { loading = false }
    }
    LaunchedEffect(conn) { load() }
    WorkbenchDialog(onDismissRequest = { if (!busy) dismiss() }, modifier = Modifier.widthIn(max = 680.dp),
        title = { Column {
            Text("Agent 配置")
            Text("${conn.host.label} · ${record.title}", style = MaterialTheme.typography.bodySmall)
        } }, text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AgentProfileChoices(engine, selected, profiles, loading, busy,
                { engine = it; selected = ""; message = "" }, { selected = it; message = "" })
            Text(if (engine == Lines.CODEX) "重新连接此空闲对话，保留历史和草稿。配置仅作用于这个 Agent。"
                else "切换运行器的新对话与摘要交接尚未接通。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (error.isNotBlank()) {
                Text(error, color = Tokens.current.danger)
                TextButton({ scope.launch { load() } }, enabled = !busy && !loading) { Text("重新读取") }
            }
            if (message.isNotBlank()) Text(message)
        } }, confirmButton = {
            TextButton({ scope.launch {
                busy = true; error = ""; message = ""
                try {
                    val current = state.codexWorkspace.registry.records.single { it.key == record.key }
                    state.codexWorkspace.open(conn, current, autoRun = false)
                    state.codexWorkspace.applyCurrentConfiguration(conn, current, selected)
                    message = "独立配置已应用，原对话保留。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "配置未应用" }
                finally { busy = false }
            } }, enabled = !loading && !busy && engine == Lines.CODEX && profiles.any { it.id == selected && it.agent == Lines.CODEX }) {
                Text(if (busy) "正在应用…" else "应用到当前对话")
            }
        }, dismissButton = { TextButton(dismiss, enabled = !busy) { Text("关闭") } })
}
