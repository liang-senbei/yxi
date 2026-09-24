package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File
import javax.swing.JFileChooser

@Composable internal fun LocalWorkspacePane(state: AppState, configuration: Boolean = false) {
    val workspace = state.localWorkspace
    val t = Tokens.current
    var creating by remember { mutableStateOf(false) }
    var openCodeRuntime by remember { mutableStateOf<LocalRuntimeInstallation?>(null) }
    var acpRuntime by remember { mutableStateOf<LocalRuntimeInstallation?>(null) }
    acpRuntime?.let { runtime -> NewAcpConversationDialog(state, runtime, { acpRuntime = null }) {
        acpRuntime = null; state.localSelectedTaskKey = it.key
    } }
    val acpOwned = state.localSelectedTaskKey?.let { key -> state.localAcpTasks.registry.records.singleOrNull { it.key == key } }
    if (!configuration && acpOwned != null) {
        AcpConversationPane(state, acpOwned)
        return
    }
    openCodeRuntime?.let { runtime -> NewOpenCodeConversationDialog(state, runtime, { openCodeRuntime = null }) {
        openCodeRuntime = null; state.localSelectedTaskKey = it.key
    } }
    val openCodeOwned = state.localSelectedTaskKey?.let { key -> state.localOpenCodeTasks.registry.records.singleOrNull { it.key == key } }
    if (!configuration && openCodeOwned != null) {
        Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) { OpenCodeConversationPane(state, openCodeOwned) }
        return
    }
    if (creating) NewLocalConversationDialog(state, { creating = false }) { record -> creating = false; state.localSelectedTaskKey = record.key }
    val owned = state.localSelectedTaskKey?.let { key -> state.localCodexTasks.registry.records.singleOrNull { it.key == key } }
    if (!configuration && owned != null) {
        Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) { LocalConversationPane(state, owned) }
        return
    }
    LaunchedEffect(workspace) { if (!workspace.scanned) workspace.refresh() }
    var search by remember { mutableStateOf(workspace.query) }
    var projectError by remember { mutableStateOf("") }
    val selected = workspace.selectedThread.takeUnless { configuration }
    CompositionLocalProvider(LocalContentColor provides t.textPrimary) {
        LazyColumn(Modifier.fillMaxSize().background(t.surface0).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (configuration) "本地配置" else selected?.title ?: "本地", style = MaterialTheme.typography.headlineMedium)
                        Text("此电脑 · ${System.getProperty("os.name")}", color = t.textMuted)
                    }
                    if (selected != null) TextButton(workspace::backToList) { Text("返回历史") }
                    else {
                        if (!configuration) TextButton({ creating = true }, enabled = workspace.selectedRuntime?.ready == true) { Text("新建对话") }
                        TextButton(workspace::refresh, enabled = !workspace.detecting) { Text(if (workspace.detecting) "检测中…" else "重新检测") }
                    }
                }
            }
            if (selected == null) {
                item { Text("本机运行器", style = MaterialTheme.typography.titleMedium) }
                items(LocalRuntimeDiscovery.engines) { engine ->
                    val found = workspace.installations.filter { it.engine == engine }
                    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = t.surface2)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RunnerBrandIcon(engine, Modifier.size(22.dp)); Text(LocalRuntimeDiscovery.title(engine), style = MaterialTheme.typography.titleMedium)
                                if (found.isEmpty()) Text(if (workspace.detecting) "检测中" else "未发现安装", color = t.textMuted)
                                Spacer(Modifier.weight(1f))
                                TextButton({
                                    val chooser = JFileChooser().apply { dialogTitle = "选择 ${LocalRuntimeDiscovery.title(engine)} 可执行文件" }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) workspace.chooseExecutable(engine, chooser.selectedFile)
                                }, enabled = !workspace.detecting) { Text("选择路径") }
                            }
                            found.forEach { runtime ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${runtime.source} · ${runtime.version.ifBlank { "版本未确认" }}", color = t.textPrimary)
                                        SelectionContainer { Text(runtime.command.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = t.textMuted) }
                                        Text(runtime.problem.ifBlank { if (engine == "codex") "数据目录：${runtime.home}" else "可启动 · 原生历史适配尚未接入" },
                                            style = MaterialTheme.typography.bodySmall, color = if (runtime.problem.isEmpty()) t.textMuted else t.danger)
                                    }
                                    if (engine == "codex" && runtime.ready) TextButton({ workspace.selectRuntime(runtime) }, enabled = !workspace.loading) {
                                        Text(if (workspace.selectedRuntime?.id == runtime.id) "已选择" else "读取历史")
                                    }
                                    if (engine == "opencode" && runtime.ready && !configuration) TextButton({ openCodeRuntime = runtime }) { Text("新建对话") }
                                    if (engine == "claude") ClaudeSubscriptionCheck(runtime)
                                    if (engine in setOf("gemini", "grok", "hermes") && runtime.ready && !configuration)
                                        TextButton({ acpRuntime = runtime }) { Text("连接并继续（预览）") }
                                }
                            }
                        }
                    }
                }
                workspace.account?.let { account -> item {
                    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = t.surface2)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(account.label, style = MaterialTheme.typography.titleMedium)
                            Text("当前运行器配置：${account.provider}", color = t.textMuted)
                            Text("登录状态由本机 Codex 返回；本页仅浏览历史。订阅状态与实际模型路由分别显示。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        }
                    }
                } }
                if (configuration) item {
                    Text("官方订阅模型", style = MaterialTheme.typography.titleMedium)
                    TextButton(workspace::refreshOfficialModels, enabled = workspace.selectedRuntime != null && !workspace.officialModelsLoading) {
                        Text(if (workspace.officialModelsLoading) "正在读取…" else "获取官方模型列表")
                    }
                    if (workspace.officialModels.isNotEmpty()) CompactModelInput(workspace.officialModelId,
                        workspace.officialModels.map { ProviderModels.Model(it.id) }, workspace::chooseOfficialModel, "官方订阅模型", Modifier.fillMaxWidth())
                    if (workspace.officialModelsError.isNotBlank()) Text(workspace.officialModelsError, color = t.danger)
                    if (workspace.officialModels.isNotEmpty() && workspace.officialModels.none { it.id == workspace.officialModelId }) Text("请从原生列表选择有效的官方模型", color = t.danger)
                    else if (workspace.officialModelId.isNotBlank()) Text("此选择用于后续新建本地官方会话，不改原生全局模型。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    Text("此页读取本机状态。单 Agent 官方订阅与第三方配置切换仍在接入中。", color = t.textMuted)
                    TextButton({ state.page = Page.LocalWorkspace }) { Text("浏览本地历史") }
                }
                else {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("项目与历史", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton({
                                runCatching {
                                    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "添加本地项目" }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) workspace.addProject(chooser.selectedFile)
                                }.onFailure { projectError = it.message.orEmpty() }
                            }) { Text("添加项目") }
                            TextButton({ state.page = Page.LocalAgents }) { Text("已有本地任务") }
                        }
                        if (projectError.isNotBlank()) Text(projectError, color = t.danger)
                    }
                    items(state.localCodexTasks.registry.records.filter { it.user == System.getProperty("user.name") && it.platform == System.getProperty("os.name") &&
                        it.runtimeHome == workspace.selectedRuntime?.home?.let { home -> File(home).canonicalPath } }, key = { "owned:${it.key}" }) { record ->
                        TextButton({ state.localSelectedTaskKey = record.key }) { Text("${record.title} · ${record.model}") }
                    }
                    items(state.localOpenCodeTasks.registry.records.filter { it.user == System.getProperty("user.name") && it.platform == System.getProperty("os.name") }, key = { "owned:${it.key}" }) { record ->
                        TextButton({ state.localSelectedTaskKey = record.key }) { Text("${record.title} · OpenCode · ${record.model}") }
                    }
                    items(state.localAcpTasks.registry.records.filter { it.user == System.getProperty("user.name") && it.platform == System.getProperty("os.name") }, key = { "owned:${it.key}" }) { record ->
                        TextButton({ state.localSelectedTaskKey = record.key }) { Text("${record.title} · ${LocalRuntimeDiscovery.title(record.engine)}") }
                    }
                    if (workspace.projects.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        workspace.projects.forEach { path -> Text(path + if (File(path).isDirectory) "" else " · 目录已不可用", color = t.textMuted) }
                    } }
                    item {
                        Text("原生历史 · Codex", style = MaterialTheme.typography.titleMedium)
                        Text("打开对话只读取历史，不恢复任务。原应用的占用状态尚未确认。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactTextInput(search, { search = it }, "搜索原生历史", Modifier.weight(1f), "搜索所有历史标题")
                            TextButton({ workspace.loadThreads(search, workspace.archived) }, enabled = workspace.selectedRuntime != null && !workspace.loading) { Text("搜索") }
                        }
                        WorkbenchTabs(listOf("最近", "归档"), if (workspace.archived) "归档" else "最近", {
                            if (!workspace.loading) workspace.loadThreads(search, it == "归档")
                        })
                    }
                    items(workspace.threads, key = { it.id }) { thread -> NativeThreadRow(thread) { workspace.openThread(thread) } }
                    if (workspace.next != null) item { TextButton({ workspace.loadThreads(more = true) }, enabled = !workspace.loading) { Text("加载更多历史") } }
                    if (workspace.scanned && !workspace.loading && workspace.threads.isEmpty()) item {
                        Text(if (workspace.selectedRuntime == null) "安装并验证 Codex 后，可读取已有历史。" else "没有找到符合条件的历史。", color = t.textMuted)
                    }
                }
            } else {
                item {
                    Text("${selected.directory}\n${selected.provider} · ${selected.source}", color = t.textMuted)
                    Text("只读历史 · 未恢复会话，未发送模型请求", color = t.accent)
                    if (!File(selected.directory).isDirectory) Text("原工作目录在此电脑不可用。", color = t.danger)
                    if (!workspace.paginated) Text("此版本使用完整历史读取，单次响应上限 8 MB。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
                if (workspace.nextTurns != null) item { TextButton({ workspace.openThread(selected, more = true) }, enabled = !workspace.readingHistory) { Text("加载更早的对话") } }
                items(workspace.turns, key = { it.optString("id") }) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val entries = turn.optJSONArray("items")
                        for (i in 0 until (entries?.length() ?: 0)) NativeHistoryItem(entries!!.getJSONObject(i))
                        HorizontalDivider(color = t.border)
                    }
                }
                if (workspace.readError.isNotBlank()) item { Text(workspace.readError, color = t.danger) }
                if (workspace.readingHistory) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (workspace.loading || workspace.detecting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (workspace.error.isNotBlank()) item { Text(workspace.error, color = t.danger) }
        }
    }
}

@Composable private fun NativeThreadRow(thread: NativeHistoryThread, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(thread.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Tokens.current.textPrimary)
        Text("${thread.directory} · ${thread.provider}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun NativeHistoryItem(item: JSONObject) {
    val type = item.optString("type")
    val text = when (type) {
        "userMessage" -> item.optJSONArray("content")?.let { a -> (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { c -> if (c.optString("type") == "text") c.optString("text") else "[${c.optString("type")} 附件]" }
        }.joinToString("\n") }.orEmpty()
        "agentMessage" -> item.optString("text")
        else -> item.optString("command").ifBlank { item.optString("text") }.ifBlank { "运行器记录：$type" }
    }
    var expanded by remember(item.optString("id")) { mutableStateOf(false) }
    Surface(color = if (type == "userMessage") Tokens.current.surface1 else Tokens.current.surface2, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (type) { "userMessage" -> "你"; "agentMessage" -> "Codex"; else -> type }, color = Tokens.current.textMuted, style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(if (expanded) text else text.take(4000), color = Tokens.current.textPrimary) }
            if (text.length > 4000) TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开完整文本") }
        }
    }
}

@Composable internal fun LocalWorkspaceSidebar(state: AppState, query: String) {
    val workspace = state.localWorkspace
    LaunchedEffect(workspace) { if (!workspace.scanned) workspace.refresh() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("本地项目", style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
        val groups = workspace.threads.filter { it.title.contains(query, true) || it.directory.contains(query, true) }.groupBy { it.directory }
        (workspace.projects + groups.keys).distinct().forEach { directory ->
            Text(File(directory).name.ifBlank { "未分组" }, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = Tokens.current.textPrimary)
            groups[directory].orEmpty().forEach { thread ->
                Text(thread.title, Modifier.fillMaxWidth().clickable { state.page = Page.LocalWorkspace; workspace.openThread(thread) }.padding(vertical = 6.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Tokens.current.textSecondary)
            }
        }
        if (groups.isEmpty()) Text("在本地工作台读取原生历史", color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}
