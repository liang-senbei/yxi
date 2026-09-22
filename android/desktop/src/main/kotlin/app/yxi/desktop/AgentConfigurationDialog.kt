package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import app.yxi.agent.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Desired configuration is a draft until the runtime adapter supplies a verified receipt. */
@Composable
internal fun AgentConfigurationDialog(conn: Conn, session: Session, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bindings by remember { mutableStateOf<Map<String, AgentBindingStore.Binding>?>(null) }
    var profiles by remember { mutableStateOf<List<Lines.Line>>(emptyList()) }
    var engine by remember { mutableStateOf(session.agent) }
    var profileId by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    suspend fun load() {
        loading = true; error = ""
        try {
            val catalog = Lines.list(conn.ssh) ?: error("无法读取已保存配置，请检查连接或配置文件")
            val saved = AgentBindingStore.list(conn.ssh) ?: error("无法读取此服务器的 Agent 配置")
            profiles = catalog; bindings = saved
            saved[session.name]?.desired?.let { engine = it.engine; profileId = it.profileId }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取失败" }
        finally { loading = false }
    }
    LaunchedEffect(conn, session.name) { load() }
    WorkbenchDialog(onDismissRequest = { if (!saving) dismiss() },
        modifier = Modifier.widthIn(max = 680.dp),
        title = { Column {
            Text("Agent 配置")
            Text("${conn.host.label} · ${session.short}", style = MaterialTheme.typography.bodySmall,
                color = Tokens.current.textMuted)
        } },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("1  选择运行器", style = MaterialTheme.typography.titleSmall)
            configurationEngines.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        FilterChip(engine == id, {
                            engine = id; profileId = ""; notice = ""
                        }, enabled = !loading && !saving, modifier = Modifier.weight(1f),
                            label = { Text(label) }, leadingIcon = { RunnerBrandIcon(id, Modifier.size(20.dp)) })
                    }
                }
            }
            HorizontalDivider()
            Text("2  选择已保存配置", style = MaterialTheme.typography.titleSmall)
            val matching = profiles.filter { it.agent == engine }
            if (engine !in listOf(Lines.CLAUDE, Lines.CODEX)) {
                Text("该运行器的独立配置适配尚未完成，暂不能保存。", color = Tokens.current.textMuted)
            } else {
                matching.forEach { profile ->
                    OutlinedCard(onClick = { profileId = profile.id; notice = "" }, enabled = !saving && !loading) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(profileId == profile.id, null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(profile.name)
                                Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                            }
                        }
                    }
                }
                if (matching.isEmpty() && !loading) Text("此运行器还没有已保存配置，请先在配置页添加。", color = Tokens.current.textMuted)
                if (profileId.isNotBlank() && matching.none { it.id == profileId })
                    Text("原配置已删除，请重新选择。", color = Tokens.current.danger)
            }
            Text(if (engine == Lines.CLAUDE && session.agent == Lines.CLAUDE)
                "应用会重启此空闲对话，保留原历史和权限模式。其他 Agent 的配置不变。"
                else "切换运行器的新对话与摘要交接尚未接通，目前只能保存配置草稿。",
                style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (engine == Lines.CLAUDE && session.agent == Lines.CLAUDE) {
                FilledTonalButton({ scope.launch {
                    saving = true; error = ""; notice = ""
                    try {
                        val selected = Lines.list(conn.ssh)?.singleOrNull { it.id == profileId && it.agent == engine }
                            ?: error("所选配置已变化，请重新读取")
                        val desired = AgentBindingStore.Desired(engine, profileId)
                        AgentBindingStore.setDesired(conn.ssh, session.name, desired, bindings)?.let { error(it) }
                        bindings = AgentBindingStore.list(conn.ssh) ?: error("配置保存后无法回读，未重启")
                        check(bindings?.get(session.name)?.desired == desired) { "选择已变化，未重启" }
                        val receipt = ConversationRouteApply.apply(conn, session, selected)
                        val checked = Lines.list(conn.ssh)?.singleOrNull { it.id == selected.id && it.agent == selected.agent }
                        check(checked != null && selected.settingsJson().similar(checked.settingsJson())) {
                            "应用期间供应商配置已变化，请重新核对；未标记为已应用"
                        }
                        AgentBindingStore.markApplied(conn.ssh, session.name, desired, receipt.processIdentity,
                            System.currentTimeMillis() / 1000.0)?.let { error(it) }
                        bindings = AgentBindingStore.list(conn.ssh)
                        notice = "已应用并核对新进程，原对话历史保留。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "应用未完成，请查看终端" }
                    finally { saving = false }
                } }, enabled = !saving && !loading && bindings != null && profiles.any { it.id == profileId && it.agent == engine }) {
                    Text(if (saving) "正在应用…" else "应用到当前对话")
                }
            }
            if (notice.isNotBlank()) Text(notice)
            if (error.isNotBlank()) {
                Text(error, color = Tokens.current.danger)
                TextButton({ scope.launch { load() } }, enabled = !saving && !loading) { Text("重新读取") }
            }
        } },
        confirmButton = {
            TextButton({ scope.launch {
                saving = true; error = ""; notice = ""
                try {
                    val latest = Lines.list(conn.ssh) ?: error("无法核对配置清单，请重试")
                    check(latest.any { it.id == profileId && it.agent == engine }) { "所选配置已删除或运行器已变化，请重新读取" }
                    val desired = AgentBindingStore.Desired(engine, profileId)
                    AgentBindingStore.setDesired(conn.ssh, session.name, desired, bindings)?.let { error(it) }
                    bindings = AgentBindingStore.list(conn.ssh) ?: error("已提交保存，但回读失败，请重新读取核对")
                    check(bindings?.get(session.name)?.desired == desired) { "配置已变化，请重新读取" }
                    notice = "配置草稿已保存，尚未应用到运行器。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "保存失败" }
                finally { saving = false }
            } }, enabled = !saving && !loading && bindings != null &&
                profiles.any { it.id == profileId && it.agent == engine } && engine in listOf(Lines.CLAUDE, Lines.CODEX)) {
                Text(if (saving) "保存中…" else "保存配置草稿")
            }
        }, dismissButton = { TextButton(dismiss, enabled = !saving) { Text("关闭") } })
}
