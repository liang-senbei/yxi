package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Lines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.DeleteOutline

/** Server-side route settings: configuration confirmation is deliberately separate from request verification. */
@Composable
fun RoutesPane(state: AppState) {
    if (state.isLocal) { LocalWorkspacePane(state, configuration = true); return }
    val conn = state.configurationConnection()
    if (conn == null) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("服务器配置", style = MaterialTheme.typography.headlineMedium)
            Text(if (state.configurationHostId.isNotBlank()) "原配置服务器已断开，请重新选择。" else "选择需要配置的服务器。", color = Tokens.current.textMuted)
            state.conns.filter { it.ssh.isConnected }.forEach { available ->
                OutlinedButton({ state.configurationHostId = available.host.id }) { Text(available.host.label) }
            }
            if (state.conns.none { it.ssh.isConnected }) Text("在侧边栏连接服务器后即可配置供应商。", color = Tokens.current.textMuted)
        }
        return
    }
    key(conn) { BoundRoutesPane(state, conn) }
}

@Composable
private fun BoundRoutesPane(state: AppState, conn: Conn) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<Lines.Line>?>(null) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var engine by remember { mutableStateOf(if (state.routesOrigin == Page.Codex || state.session?.isCodex == true) Lines.CODEX else Lines.CLAUDE) }
    var active by remember { mutableStateOf<Lines.Active?>(null) }
    var codex by remember { mutableStateOf<Lines.CodexNow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<Lines.Line?>(null) }
    var applying by remember { mutableStateOf<Lines.Line?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Lines.Line?>(null) }
    var projectScope by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var openCodeAdd by remember { mutableStateOf(false) }
    val cwd = state.session?.cwd?.takeIf { state.conn === conn && it.startsWith('/') }
    val chosenScope = if (engine == Lines.CLAUDE && projectScope) cwd else null
    suspend fun reload(scopePath: String? = chosenScope) {
        lines = Lines.list(conn.ssh)
        active = Lines.active(conn.ssh, scopePath)
        codex = Lines.currentCodex(conn.ssh)
    }
    fun act(block: suspend () -> Unit) { scope.launch {
        busy = true; note = ""
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { note = e.message ?: "操作未完成" }
        finally { busy = false }
    } }
    LaunchedEffect(conn, chosenScope) {
        busy = true; active = null; codex = null
        try { reload(chosenScope) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { note = e.message ?: "读取失败" }
        finally { busy = false }
    }
    fun applyRoute(line: Lines.Line?) {
        if (state.deferredRoute != null) { note = "请先取消或等待已排队的线路切换"; return }
        val targetEngine = engine
        val targetScope = chosenScope
        val catalog = lines.orEmpty().toList()
        act {
        check(conn.status == Conn.Status.Connected) { "主机未连接，未修改配置" }
        if (targetEngine == Lines.CODEX) {
            state.codexWorkspace.tasks(conn.host).forEach { state.codexWorkspace.controllers[it.key]?.setAutoDispatch(false) }
            check(!state.codexRouteBusy(conn)) { "该服务器的 Codex 对话仍有活动轮次、审批或未确认投递，请处理后再应用。" }
        }
        conn.refresh()
        check(conn.sessions.none { it.state == app.yxi.agent.SessionState.Working && it.isCodex == (targetEngine == Lines.CODEX) && (targetScope == null || it.cwd == targetScope) }) { "目标范围内仍有 Agent 正在工作，请等本轮结束后再应用配置。" }
        val result = if (targetEngine == Lines.CODEX) {
            Lines.applyCodex(conn.ssh, line)?.let { error(it) }
            "配置已写入；已有历史的 Codex 对话可回到对话页点击“在此对话应用当前服务器线路”，或新建任务使用。尚未验证模型请求。"
        } else {
            val changed = Lines.apply(conn.ssh, line, targetScope, catalog)
            changed.err?.let { error(it) }
            if (changed.restart.isNotEmpty()) "配置已写入；${changed.restart.joinToString()} 需要重新连接后生效。Codex 对话可回到对话页应用当前服务器线路。尚未验证模型请求。"
            else "配置已写入，后续请求的生效情况仍需验证。"
        }
        reload(targetScope)
        if (line != null) check(if (line.isCodex) codex?.let { Lines.matchesCodex(line, it) } == true else active?.let { Lines.matches(line, it.env) } == true) { "配置回读不匹配，请检查服务器配置" }
        note = result; applying = null; resetting = false
        }
    }
    editor?.let { original -> RouteForm(original, conn, onClose = { editor = null }) { edited ->
        val before = lines ?: error("清单未读取，不能覆盖")
        val latest = Lines.list(conn.ssh) ?: error("无法确认服务器最新清单")
        check(routeCatalogEqual(before, latest)) { "线路清单已被其他人修改，请关闭编辑后刷新" }
        val updated = if (before.any { it.id == edited.id }) before.map { if (it.id == edited.id) edited else it } else before + edited
        Lines.saveList(conn.ssh, updated, expected = before)?.let { error(it) }
        val checked = Lines.list(conn.ssh) ?: error("保存结果未确认，请刷新核对")
        check(routeCatalogEqual(updated, checked)) { "保存后的线路清单不匹配" }
        lines = checked; editor = null; note = "线路已保存。点击应用配置后才会修改运行器设置。"
    }
        return
    }
    Column(Modifier.fillMaxSize().background(t.surface0).verticalScroll(rememberScrollState()).padding(28.dp)) {
        ConfigurationHeader(state, conn, engine, busy, lines != null || engine == "opencode", { engine = it; projectScope = false; openCodeAdd = false }) {
            if (engine == "opencode") openCodeAdd = true else editor = Lines.Line(Lines.newId(), "", agent = engine)
        }
        Spacer(Modifier.height(22.dp))
        OfficialProviderCards(engine)
        if (engine == "gemini") {
            GeminiConfigurationCard(conn)
            return@Column
        }
        if (engine == "opencode") {
            OpenCodeConfigurationCard(conn, openCodeAdd) { openCodeAdd = false }
            return@Column
        }
        if (engine !in listOf(Lines.CLAUDE, Lines.CODEX)) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${configurationEngines.firstOrNull { it.first == engine }?.second ?: engine} 配置", style = MaterialTheme.typography.titleLarge)
                    Text("该运行器的配置适配尚未完成，暂不能添加或应用供应商。", color = t.textMuted)
                }
            }
            return@Column
        }
        state.deferredRoute?.let { request ->
            OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("等待切换 · ${request.conn.host.label} · ${request.line.name}", style = MaterialTheme.typography.titleSmall)
                    Text("${request.directory ?: "服务器用户级"} · ${request.status}", style = MaterialTheme.typography.bodySmall)
                    TextButton({ state.deferredRoute = null; state.deferredRouteNotice = "已取消等待切换，未写入配置" }, enabled = !request.applying) { Text("取消等待") }
                }
            }
        }
        if (state.deferredRouteNotice.isNotBlank()) Text(state.deferredRouteNotice, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
        if (engine == Lines.CODEX) {
            val existingTask = state.codexWorkspace.tasks(conn.host).firstOrNull { it.key == state.codexSelectedTaskKey }
            if (existingTask != null) OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("继续原对话 · ${existingTask.title}", style = MaterialTheme.typography.titleSmall)
                    Text(existingTask.directory, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    Text("保存并应用线路后，回到对话点击“在此对话应用当前服务器线路”。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    TextButton({ state.page = Page.Codex }, enabled = !busy && state.deferredRoute == null) { Text("返回原对话") }
                }
            }
            TextButton({
                val task = state.codexWorkspace.tasks(conn.host).firstOrNull { it.key == state.codexSelectedTaskKey }
                state.prepareCodexTask(conn, task?.directory ?: cwd.orEmpty())
            }, enabled = !busy && conn.ssh.isConnected && state.deferredRoute == null) { Text("用当前服务器配置新建 Codex 任务") }
            Text("先应用并核对配置，再创建新任务。原任务和草稿保留；新任务不会自动继承原对话内容。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        }
        if (engine == Lines.CLAUDE && cwd != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(projectScope, { projectScope = it }, enabled = !busy)
            Text("仅配置当前项目 · $cwd", style = MaterialTheme.typography.bodySmall)
        }
        val current = lines?.firstOrNull { line -> line.agent == engine && if (line.isCodex) codex?.let { Lines.matchesCodex(line, it) } == true else active?.let { Lines.matches(line, it.env) } == true }
        Surface(Modifier.fillMaxWidth().padding(top = 18.dp), shape = RoundedCornerShape(16.dp), color = t.surface1) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("当前保存的配置", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                    Text(current?.name ?: "未匹配到已保存的线路", style = MaterialTheme.typography.titleMedium)
                    Text(if (chosenScope != null) "项目配置可能覆盖服务器默认值" else "服务器用户级配置；可能影响该用户的其他任务", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
                TextButton({ act { reload() } }, enabled = !busy) { Text("刷新") }
            }
        }
        if (note.isNotBlank()) Text(note, Modifier.padding(vertical = 12.dp), color = t.textSecondary)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("搜索线路名称、地址、模型或备注") })
        Spacer(Modifier.height(10.dp))
        val visibleLines = lines.orEmpty().filter { line -> line.agent == engine &&
            listOf(line.name, line.baseUrl, routeModel(line), line.note, line.website).any { it.contains(search.trim(), ignoreCase = true) } }
        when {
            lines == null -> Text("线路清单未能读取。请确认连接、文件权限与内容格式。", color = t.textMuted)
            lines!!.none { it.agent == engine } -> Text("还没有保存的线路。添加后可配置模型、测试连通性，再应用到服务器。", color = t.textMuted)
            visibleLines.isEmpty() -> Text("没有匹配的线路", color = t.textMuted)
            else -> visibleLines.forEach { line ->
                var actionsOpen by remember(line.id) { mutableStateOf(false) }
                NativeOverlay(actionsOpen)
                Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(14.dp), color = t.surface2,
                    border = BorderStroke(0.5.dp, t.border)) {
                    Column(Modifier.clickable(enabled = !busy) { editor = line }.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = t.surface1, border = BorderStroke(0.5.dp, t.border)) {
                                Box(contentAlignment = Alignment.Center) { Text(line.name.take(2).uppercase(), style = MaterialTheme.typography.labelLarge, color = t.textSecondary) }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(line.name, style = MaterialTheme.typography.titleMedium)
                                Text(line.baseUrl, color = t.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (line.id == current?.id) Text("配置匹配", style = MaterialTheme.typography.labelSmall, color = t.success)
                            Spacer(Modifier.width(12.dp))
                            ProviderQuotaBadge(line)
                        }
                        Text("模型 · " + routeModel(line).ifBlank { "使用运行器默认值" }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        if (line.isCodex && line.extra.optString("model_reasoning_effort").isNotBlank()) Text("推理强度 · ${line.extra.optString("model_reasoning_effort")}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        if (!line.isCodex && line.extra.optString("effortLevel").isNotBlank()) Text("推理强度 · ${line.extra.optString("effortLevel")}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        if (line.note.isNotBlank()) Text(line.note, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ editor = line }, enabled = !busy) { Text("编辑") }
                            if (runCatching { val uri = java.net.URI(line.website); uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false)) TextButton({ runCatching { uriHandler.openUri(line.website) }.onFailure { note = "无法打开官网链接" } }) { Text("官网 ↗") }
                            FilledTonalButton({ note = ""; applying = line }, enabled = !busy) { Text("应用配置") }
                            Box {
                                IconButton({ actionsOpen = true }, enabled = !busy) { Icon(Icons.Outlined.MoreHoriz, "更多供应商操作") }
                                DropdownMenu(actionsOpen, { actionsOpen = false }) {
                                    DropdownMenuItem(text = { Text("复制供应商") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)) },
                                        onClick = { actionsOpen = false; editor = line.copy(id = Lines.newId(), name = line.name + " · 副本", extra = org.json.JSONObject(line.extra.toString())) })
                                    DropdownMenuItem(text = { Text("检查连通性") }, leadingIcon = { Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(18.dp)) },
                                        onClick = { actionsOpen = false; act { note = Lines.probe(conn.ssh, line.baseUrl) + "；此检查不验证密钥或模型可用性。" } })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("移除记录", color = t.danger) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = t.danger) },
                                        onClick = { actionsOpen = false; note = ""; deleting = line })
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton({ note = ""; resetting = true }, enabled = !busy && lines != null) { Text("恢复运行器默认线路…") }
    }
    if (applying != null || resetting) WorkbenchDialog(onDismissRequest = { if (!busy) { applying = null; resetting = false } },
        title = { Text(if (resetting) "恢复默认线路？" else "应用 ${applying!!.name}？") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("目标：${conn.host.label}\n范围：${chosenScope ?: "服务器用户级"}\n" + if (chosenScope == null) "可能影响同一用户下的其他 Agent。正在执行的请求不会被当作已完成切换；需要重开的会话会明确提示。" else "该项目下的其他 Agent 也可能受影响。")
            Text("运行器：${if (engine == Lines.CODEX) "Codex" else "Claude Code"}\n模型：${applying?.let(::routeModel)?.ifBlank { "运行器默认值" } ?: "运行器默认值"}", style = MaterialTheme.typography.bodySmall)
            val affected = conn.sessions.filter { it.isCodex == (engine == Lines.CODEX) && (chosenScope == null || it.cwd == chosenScope) }
            val managed = if (engine == Lines.CODEX) state.codexWorkspace.tasks(conn.host) else emptyList()
            Text("当前已知范围内任务 · ${affected.size + managed.size}", style = MaterialTheme.typography.labelMedium)
            if (managed.isNotEmpty()) Text("应用配置会暂停这些 Codex 对话的自动队列。已打开的运行器不代表已切换线路，仍需重新打开任务并核对。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            managed.forEach { task -> Text((state.navigation.title(task.key) ?: task.title) + "\n" + task.directory, style = MaterialTheme.typography.bodySmall, color = t.textMuted) }
            affected.forEach { task ->
                Text((state.navigation.title(taskNavigationKey(conn.host, task)) ?: task.short) +
                    (if (task.state == app.yxi.agent.SessionState.Working) " · 运行中，需等待本轮结束" else "") + "\n${task.cwd}",
                    style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            }
            if (note.isNotBlank()) Text(note, Modifier.padding(top = 12.dp), color = t.danger)
        } },
        confirmButton = { Column {
            TextButton({ applyRoute(applying) }, enabled = !busy && state.deferredRoute == null) { Text("确认应用") }
            applying?.let { line -> TextButton({
                if (line.isCodex) state.codexWorkspace.tasks(conn.host).forEach { state.codexWorkspace.controllers[it.key]?.setAutoDispatch(false) }
                state.deferredRoute = DeferredRoute(conn, line, chosenScope, lines.orEmpty())
                state.deferredRouteNotice = ""; applying = null
            }, enabled = !busy && lines != null && state.deferredRoute == null) { Text("等待任务空闲后应用") } }
            Text("等待仅在应用运行期间有效；不会中断任务。", style = MaterialTheme.typography.labelSmall)
        } },
        dismissButton = { TextButton({ applying = null; resetting = false }, enabled = !busy) { Text("取消") } })
    deleting?.let { line -> WorkbenchDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("移除 ${line.name}？") },
        text = { Column {
            Text("只从 ${conn.host.label} 的线路清单移除这条记录。已应用到运行器的配置仍保留，此操作不会撤销提供方密钥。")
            if (note.isNotBlank()) Text(note, Modifier.padding(top = 12.dp), color = t.danger)
        } },
        confirmButton = { TextButton({ act {
            val before = lines ?: error("清单未读取")
            val updated = before.filterNot { it.id == line.id }
            Lines.saveList(conn.ssh, updated, expected = before)?.let { error(it) }
            val checked = Lines.list(conn.ssh) ?: error("删除结果尚未确认，请刷新核对")
            check(routeCatalogEqual(updated, checked)) { "线路清单回读不匹配" }
            lines = checked; deleting = null; note = "记录已移除，运行器配置未改动。"
        } }, enabled = !busy) { Text("移除记录", color = t.danger) } },
        dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text("取消") } }) }
}

/** 模型映射的角色档位；alias 对应 env 键 ANTHROPIC_DEFAULT_{alias}_MODEL(_NAME)，null = 子 Agent（CLAUDE_CODE_SUBAGENT_MODEL）。 */
private data class ModelRoleRow(val label: String, val alias: String?, val supportsOneM: Boolean)

private val RoleAliases = listOf("SONNET", "OPUS", "FABLE", "HAIKU")

/** 映射区行序（参照 CC Switch）：Sonnet、Opus、Fable、Haiku、Subagent；Haiku 不支持 1M。 */
private val RoleRows = listOf(
    ModelRoleRow("Sonnet", "SONNET", true),
    ModelRoleRow("Opus", "OPUS", true),
    ModelRoleRow("Fable", "FABLE", true),
    ModelRoleRow("Haiku", "HAIKU", false),
    ModelRoleRow("Subagent", null, true),
)

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun RouteForm(original: Lines.Line, conn: Conn, onClose: () -> Unit, onSave: suspend (Lines.Line) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(original.name) }
    var website by remember { mutableStateOf(original.website) }
    var url by remember { mutableStateOf(original.baseUrl) }
    var secret by remember { mutableStateOf(original.apiKey) }
    var authToken by remember { mutableStateOf(original.token) }
    var showSecret by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(routeModel(original)) }
    var mappingsOpen by remember { mutableStateOf(true) }
    var requestOptionsOpen by remember { mutableStateOf(false) }
    var quickSetOpen by remember { mutableStateOf(false) }
    var quickModel by remember { mutableStateOf("") }
    var quickUndo by remember { mutableStateOf<Triple<Map<String, String>, Map<String, String>, String>?>(null) }
    val mappings = remember { mutableStateMapOf<String, String>().apply {
        RoleAliases.forEach { alias -> put(alias, original.extraEnv().optString("ANTHROPIC_DEFAULT_${alias}_MODEL")) }
    } }
    val displayNames = remember { mutableStateMapOf<String, String>().apply {
        RoleAliases.forEach { alias -> put(alias, original.extraEnv().optString("ANTHROPIC_DEFAULT_${alias}_MODEL_NAME")) }
    } }
    var subagentModel by remember { mutableStateOf(original.extraEnv().optString("CLAUDE_CODE_SUBAGENT_MODEL")) }
    var memo by remember { mutableStateOf(original.note) }
    val effortKey = if (original.isCodex) "model_reasoning_effort" else "effortLevel"
    var effort by remember { mutableStateOf(original.extra.optString(effortKey)) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var presetSearch by remember { mutableStateOf("") }
    var presetsExpanded by remember { mutableStateOf(false) }
    var templatesOpen by remember { mutableStateOf(original.name.isBlank()) }
    var presetExtra by remember { mutableStateOf(org.json.JSONObject(original.extra.toString())) }
    var advancedOpen by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(original.extra.toString(2)) }
    // 可用模型列表（网络层在 ProviderModels，本表单只发起与展示状态；请求在选中服务器执行）
    var models by remember { mutableStateOf<List<ProviderModels.Model>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsNote by remember { mutableStateOf("") }
    var modelsNoteIsError by remember { mutableStateOf(false) }
    var modelsGen by remember { mutableStateOf(0) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    var modelsUrl by remember { mutableStateOf(original.modelsUrl) }
    // 端点、密钥或列表 URL 一旦变化：取消在途请求并作废旧结果，不许残留旧线路的模型供误选
    LaunchedEffect(url, secret, authToken, modelsUrl) {
        modelsGen += 1
        fetchJob?.cancel(); fetchJob = null
        models = emptyList(); modelsLoading = false
        modelsNote = ""; modelsNoteIsError = false
    }
    fun fetchModels() {
        // 密钥口径：Auth token 优先，其次 API Key（与该线路实际认证字段一致）
        val key = authToken.trim().ifBlank { secret.trim() }
        val requestUrl = url.trim()
        val requestModelsUrl = modelsUrl.trim()
        if (url.isBlank() || key.isBlank()) {
            modelsNote = "获取模型列表需要先填写请求地址和密钥（Auth token 优先，其次 API Key）。"; modelsNoteIsError = true
            return
        }
        fetchJob?.cancel()
        // 每次发起都换新代号：被取消的旧任务回到 finally 时对不上号，不会关掉新任务的状态
        modelsGen += 1
        val gen = modelsGen
        modelsNote = ""; modelsNoteIsError = false; modelsLoading = true
        fetchJob = scope.launch {
            try {
                val fetched = ProviderModels.fetch(conn, requestUrl, key, requestModelsUrl)
                if (gen == modelsGen && url.trim() == requestUrl && modelsUrl.trim() == requestModelsUrl &&
                    authToken.trim().ifBlank { secret.trim() } == key) {
                    models = fetched
                    modelsNote = if (fetched.isEmpty()) "接口未返回任何模型。" else "已获取 ${fetched.size} 个模型"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen == modelsGen) {
                    modelsNote = e.message?.takeIf { it.isNotBlank() } ?: "获取模型列表失败。"
                    modelsNoteIsError = true
                }
            } finally { if (gen == modelsGen) modelsLoading = false }
        }
    }
    fun roleModelChange(alias: String?, supportsOneM: Boolean, raw: String) {
        quickUndo = null
        val normalized = if (supportsOneM) raw else oneMBase(raw)
        if (alias == null) { subagentModel = normalized; return }
        // 实际模型变更：显示名称为空或还跟着旧模型 ID 时跟随更新，用户自定义过的显示名称不动
        val oldBase = oneMBase(mappings[alias].orEmpty())
        mappings[alias] = normalized
        val shown = displayNames[alias].orEmpty().trim()
        if (shown.isEmpty() || shown == oldBase) displayNames[alias] = oneMBase(normalized)
    }
    LaunchedEffect(modelsNote) {
        if (modelsNote.isNotBlank() && !modelsNoteIsError) { kotlinx.coroutines.delay(5000); modelsNote = "" }
    }
    fun quickSetRoles(source: String) {
        val before = Triple(mappings.toMap(), displayNames.toMap(), subagentModel)
        RoleRows.forEach { row ->
            val old = if (row.alias == null) subagentModel else mappings[row.alias].orEmpty()
            roleModelChange(row.alias, row.supportsOneM, setOneM(oneMBase(source), row.supportsOneM && hasOneM(old)))
        }
        quickUndo = before
    }
    ProviderEditorPage(onDismissRequest = { if (!busy) onClose() }, title = { Text("${if (original.name.isBlank()) "添加" else "编辑"}供应商 · ${if (original.isCodex) "Codex" else "Claude Code"}", style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("配置服务器 · ${conn.host.label}", style = MaterialTheme.typography.bodyMedium, color = Tokens.current.textMuted)
            TextButton({ templatesOpen = !templatesOpen }) { Text(if (templatesOpen) "收起供应商模板" else "选择供应商模板") }
            if (templatesOpen) {
            OutlinedTextField(presetSearch, { presetSearch = it }, singleLine = true, label = { Text("搜索模板") }, modifier = Modifier.fillMaxWidth())
            val matchingPresets = app.yxi.agent.LinePresets.forAgent(original.agent).filter { it.name.contains(presetSearch, true) }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SuggestionChip(onClick = {
                    name = ""; url = ""; website = ""; model = ""; secret = ""; authToken = ""; modelsUrl = ""
                    presetExtra = org.json.JSONObject(); advanced = "{}"
                    mappings.keys.toList().forEach { mappings[it] = "" }
                    displayNames.keys.toList().forEach { displayNames[it] = "" }
                    subagentModel = ""; quickUndo = null
                }, label = { Text("自定义供应商") }, enabled = !busy)
                (if (presetsExpanded || presetSearch.isNotBlank()) matchingPresets else matchingPresets.take(12)).forEach { preset ->
                    SuggestionChip(onClick = {
                        name = preset.name; url = preset.baseUrl
                        website = ""; secret = ""; authToken = ""; modelsUrl = ""
                        presetExtra = org.json.JSONObject(original.extra.toString())
                        if (!original.isCodex) {
                            presetExtra.put("env", preset.envJson())
                            model = preset.env["ANTHROPIC_MODEL"].orEmpty()
                            mappings.keys.toList().forEach { alias -> mappings[alias] = preset.env["ANTHROPIC_DEFAULT_${alias}_MODEL"].orEmpty() }
                            displayNames.keys.toList().forEach { alias -> displayNames[alias] = preset.env["ANTHROPIC_DEFAULT_${alias}_MODEL_NAME"].orEmpty() }
                            subagentModel = preset.env["CLAUDE_CODE_SUBAGENT_MODEL"].orEmpty()
                        } else model = preset.model
                        advanced = presetExtra.toString(2); quickUndo = null
                    }, label = { Text(preset.name) }, enabled = !busy)
                }
            }
            if (presetSearch.isBlank() && matchingPresets.size > 12) TextButton({ presetsExpanded = !presetsExpanded }) {
                Text(if (presetsExpanded) "收起模板" else "查看全部 ${matchingPresets.size} 个模板")
            }
            if (matchingPresets.isEmpty()) Text("没有匹配的模板，可以使用自定义供应商。", color = Tokens.current.textMuted)
            }
            Text("模板仅预填，可自行修改端点和模型；切换模板会清空密钥，避免将上一供应商凭据带到新地址。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            HorizontalDivider()
            OutlinedTextField(name, { name = it }, label = { Text("供应商名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(website, { website = it }, label = { Text("官网链接（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("请求地址 Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton({ showSecret = !showSecret }) { Text(if (showSecret) "隐藏" else "显示") } })
            if (!original.isCodex) OutlinedTextField(authToken, { authToken = it }, label = { Text("Auth token（按提供方要求填写）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
            if (original.isCodex) OutlinedTextField(model, { model = it }, label = { Text("模型 ID（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (original.isCodex) {
                TextButton({ requestOptionsOpen = !requestOptionsOpen }) { Text(if (requestOptionsOpen) "收起请求选项" else "更多请求选项") }
                if (requestOptionsOpen) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("模型列表 URL（可选）", fontSize = 12.sp, color = Tokens.current.textMuted)
                    CompactTextInput(modelsUrl, { modelsUrl = it }, "模型列表 URL", Modifier.fillMaxWidth(), placeholder = "留空按 Base URL 尝试")
                }
            }
            if (!original.isCodex) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = Tokens.current.surface2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.current.border)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactMappingToolbar(mappingsOpen, { mappingsOpen = !mappingsOpen },
                    { quickModel = oneMBase(model).ifBlank { oneMBase(mappings["SONNET"].orEmpty()) }; quickSetOpen = true }, { fetchModels() }, modelsLoading)
                    if (quickUndo != null) TextButton({ quickUndo?.let { (oldModels, oldNames, oldSub) ->
                        mappings.clear(); mappings.putAll(oldModels); displayNames.clear(); displayNames.putAll(oldNames); subagentModel = oldSub
                    }; quickUndo = null }) { Text("撤销一键设置") }
                if (modelsNote.isNotBlank()) Text(modelsNote, style = MaterialTheme.typography.bodySmall,
                    color = if (modelsNoteIsError) Tokens.current.danger else Tokens.current.textMuted)
                if (mappingsOpen) {
                    Text("显示名称只影响菜单；1M 是上下文声明，需要运行器和供应商支持。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    CompactModelMappings(RoleRows.map { row -> ModelMappingRow(row.alias ?: "SUBAGENT", row.label,
                        if (row.alias == null) subagentModel else mappings[row.alias].orEmpty(),
                        row.alias?.let { displayNames[it].orEmpty() }, row.supportsOneM) }, models,
                        changeModel = { id, value -> val row = RoleRows.single { (it.alias ?: "SUBAGENT") == id }; roleModelChange(row.alias, row.supportsOneM, value) },
                        changeName = { id, value -> quickUndo = null; displayNames[id] = value }, fallback = model, changeFallback = { model = it })
                }
                TextButton({ requestOptionsOpen = !requestOptionsOpen }) { Text(if (requestOptionsOpen) "收起请求选项" else "更多请求选项") }
                if (requestOptionsOpen) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("模型列表 URL（可选）", fontSize = 12.sp, color = Tokens.current.textMuted)
                    CompactTextInput(modelsUrl, { modelsUrl = it }, "模型列表 URL", Modifier.fillMaxWidth(), placeholder = "留空按 Base URL 尝试")
                }
                } }
            }
            run {
                OutlinedTextField(effort, { effort = it }, label = { Text("推理强度（可留空）") }, singleLine = true)
                Text("按运行器与模型支持的值填写；更改后可能需要重开会话。留空移除此线路的强度设置。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            }
            OutlinedTextField(memo, { memo = it }, label = { Text("备注（例如用途、套餐或模型区别）") }, maxLines = 3)
            if (!original.isCodex) {
                TextButton({ advancedOpen = !advancedOpen }) { Text(if (advancedOpen) "收起高级配置 JSON" else "高级配置 JSON") }
                if (advancedOpen) {
                    Text("填写额外的 settings 配置。上方端点、密钥、模型映射和强度字段优先；不支持的键会明确提示。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    OutlinedTextField(advanced, { advanced = it }, label = { Text("额外配置 JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 14)
                }
            }
            Text("模型 ID 由该线路提供方定义。保存不代表接口、密钥或模型已验证。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        } },
        confirmButton = { TextButton({ scope.launch {
            busy = true; error = ""
            try {
                require(effort.none { it < ' ' }) { "推理强度不能包含控制字符" }
                val extra = if (!original.isCodex) org.json.JSONObject(advanced) else presetExtra
                if (!original.isCodex) {
                    val rejected = Lines.rejectedKeys(extra)
                    require(rejected.isEmpty()) { "不支持的配置项：${rejected.joinToString()}" }
                    require(extra.optJSONObject("env")?.let { env -> listOf("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY").none { env.has(it) } } != false) { "端点和密钥请填写上方独立字段" }
                }
                if (website.isNotBlank()) {
                    val uri = java.net.URI(website.trim())
                    require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "官网链接需要完整的HTTP或HTTPS地址，不能含账号密码" }
                }
                if (modelsUrl.isNotBlank()) {
                    val uri = java.net.URI(modelsUrl.trim())
                    require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) { "模型列表 URL 需要 HTTP/HTTPS 地址，不能含凭据、查询参数或片段" }
                }
                val edited = editedRoute(original.copy(extra = extra), name, url, secret, model, authToken).copy(note = memo.trim(), website = website.trim(), modelsUrl = modelsUrl.trim())
                if (!original.isCodex) {
                    val env = edited.extra.optJSONObject("env") ?: org.json.JSONObject()
                    mappings.forEach { (alias, value) ->
                        require(value.none { it < ' ' }) { "模型映射不能包含控制字符" }
                        val key = "ANTHROPIC_DEFAULT_${alias}_MODEL"
                        if (value.isBlank()) env.remove(key) else env.put(key, value.trim())
                    }
                    displayNames.forEach { (alias, value) ->
                        require(value.none { it < ' ' }) { "显示名称不能包含控制字符" }
                        val nameKey = "ANTHROPIC_DEFAULT_${alias}_MODEL_NAME"
                        if (value.isBlank()) env.remove(nameKey) else env.put(nameKey, value.trim())
                    }
                    require(subagentModel.none { it < ' ' }) { "子 Agent 模型不能包含控制字符" }
                    if (subagentModel.isBlank()) env.remove("CLAUDE_CODE_SUBAGENT_MODEL") else env.put("CLAUDE_CODE_SUBAGENT_MODEL", subagentModel.trim())
                    if (env.length() == 0) edited.extra.remove("env") else edited.extra.put("env", env)
                }
                if (effort.isBlank()) edited.extra.remove(effortKey) else edited.extra.put(effortKey, effort.trim())
                onSave(edited)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "保存失败，输入已保留" }
            finally { busy = false }
        } }, enabled = !busy) { Text(if (busy) "保存中…" else "保存线路") } },
        dismissButton = { TextButton(onClose, enabled = !busy) { Text("取消") } })
    if (quickSetOpen) AlertDialog(onDismissRequest = { quickSetOpen = false }, title = { Text("一键设置模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用到所有角色，保留自定义显示名称和各角色当前的 1M 声明。")
            CompactModelInput(quickModel, models, { quickModel = it }, "一键设置模型", Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton({ quickSetRoles(quickModel); quickSetOpen = false }, enabled = oneMBase(quickModel).isNotBlank()) { Text("应用") } },
        dismissButton = { TextButton({ quickSetOpen = false }) { Text("取消") } })
}


