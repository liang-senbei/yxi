package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

internal fun parseMcpHeaderVariables(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val parts = line.split('=', limit = 2).map { it.trim() }
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "每行填写 请求头名称=环境变量名" }
        require(result.keys.none { it.equals(parts[0], ignoreCase = true) }) { "请求头名称不能重复" }
        result[parts[0]] = parts[1]
    }
    return result
}

@Composable internal fun SharedMcpPane(state: AppState, conn: Conn?) {
    val hostKey = if (state.pluginLocation == "本地") "@local" else conn?.let { projectKey(it.host, "/") }
    if (hostKey == null) { Text("请先选择服务器"); return }
    val registry = state.sharedMcp
    val t = Tokens.current
    var adding by remember(hostKey) { mutableStateOf(false) }
    var error by remember(hostKey) { mutableStateOf("") }
    var showHistory by remember(hostKey) { mutableStateOf(false) }
    var reviewing by remember(hostKey) { mutableStateOf(false) }
    var reviewed by remember(hostKey) { mutableStateOf(false) }
    fun change(action: () -> Unit) { runCatching { action(); error = "" }.onFailure { error = it.message.orEmpty() } }
    if (reviewing) WorkbenchDialog(onDismissRequest = { reviewing = false }, title = { Text("核对恢复的共享配置") }, text = {
        Column {
            Text("请核对本地及服务器的共享配置列表。确认后允许编辑，连接状态会在新建会话时重新核对。")
            Row { Checkbox(reviewed, { reviewed = it }); Text("我已核对恢复的共享配置") }
        }
    }, confirmButton = { TextButton({ change { registry.confirmRecoveryReviewed(); reviewing = false } }, enabled = reviewed) { Text("确认核对") } },
        dismissButton = { TextButton({ reviewing = false }) { Text("取消") } })
    if (adding) {
        var name by remember { mutableStateOf("") }
        var remote by remember { mutableStateOf(true) }
        var endpoint by remember { mutableStateOf("") }
        var program by remember { mutableStateOf("") }
        var arguments by remember { mutableStateOf("") }
        var variables by remember { mutableStateOf("") }
        var headers by remember { mutableStateOf("") }
        var advanced by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        val id = remember { UUID.randomUUID().toString() }
        WorkbenchDialog(onDismissRequest = { adding = false }, title = { Text("添加通用 MCP 配置") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称（字母、数字、短横线或下划线）") }, singleLine = true)
                Row { QuietChoice(remote, { remote = true }, label = { Text("服务地址") }); QuietChoice(!remote, { remote = false }, label = { Text("启动程序") }) }
                if (remote) OutlinedTextField(endpoint, { endpoint = it }, label = { Text("MCP 服务 URL") }, singleLine = true)
                else {
                    OutlinedTextField(program, { program = it }, label = { Text("目标机器上的程序路径或命令名") }, singleLine = true)
                    OutlinedTextField(arguments, { arguments = it }, label = { Text("参数，每行一个，空格按原样保留") }, minLines = 2, maxLines = 5)
                }
                TextButton({ advanced = !advanced }) { Text(if (advanced) "收起变量引用" else "配置变量引用（可选）") }
                if (advanced) {
                    if (remote) {
                        OutlinedTextField(headers, { headers = it }, label = { Text("请求头=变量名，每行一项") },
                            placeholder = { Text("Authorization=MY_PLUGIN_AUTH") }, minLines = 2, maxLines = 4)
                        Text("变量的值应包含完整请求头，例如 Bearer 加令牌。此处只填写变量名。变量需要在目标机器的运行器启动环境中设置。", style = MaterialTheme.typography.bodySmall)
                    } else OutlinedTextField(variables, { variables = it }, label = { Text("环境变量名，每行一个") },
                        placeholder = { Text("MY_PLUGIN_TOKEN") }, minLines = 2, maxLines = 4)
                    Text(if (hostKey == "@local") "请先在本机为运行器进程配置这些变量；Yxi 不保存变量值。" else "请在当前服务器的运行器启动环境中配置这些变量；本机变量不会传到服务器。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("可用于 OpenCode、Codex 新会话，以及本地和服务器 Claude 任务。", style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message, color = t.danger)
            }
        }, confirmButton = { TextButton({
            runCatching {
                val definition = SharedMcpDefinition(hostKey, id, "manual", "1", name.trim(),
                    if (remote) emptyList() else listOf(program.trim()) + arguments.lineSequence().filter { it.isNotEmpty() }.toList(), endpoint.trim().takeIf { remote },
                    if (remote) emptySet() else variables.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    if (remote) parseMcpHeaderVariables(headers) else emptyMap())
                registry.save(definition, emptySet(), null)
                adding = false
            }.onFailure { message = it.message.orEmpty() }
        }) { Text("保存配置") } }, dismissButton = { TextButton({ adding = false }) { Text("取消") } })
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row {
                Text("通用 MCP", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ adding = true }, enabled = registry.problem.isBlank()) { Text("添加") }
                TextButton({ showHistory = !showHistory }) { Text(if (showHistory) "隐藏历史" else "版本历史") }
            }
            Text("同一机器登记一次，分别选择运行器。OpenCode 和 Codex 会在新建时核对并加载；配置保存不代表已授权。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            if (registry.problem.isNotBlank()) Text(registry.problem, color = t.danger)
            if (registry.recoveryReviewRequired) TextButton({ reviewed = false; reviewing = true }) { Text("核对恢复的配置") }
            if (error.isNotBlank()) Text(error, color = t.danger)
        }
        items(registry.forHost(hostKey, showHistory).filter { it.definition.name.contains(state.pluginMarketQuery, true) }, key = { it.definition.key }) { record ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${record.definition.name} · ${record.definition.version}" + if (record.retired) " · 历史记录" else "", style = MaterialTheme.typography.titleSmall)
                Text(if (record.definition.url != null) "远程 MCP 服务" else "目标机器上的程序 / stdio MCP", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                if (!record.retired) {
                    Row {
                        QuietChoice("opencode" in record.desiredRunners, { change {
                            val runners = if ("opencode" in record.desiredRunners) record.desiredRunners - "opencode" else record.desiredRunners + "opencode"
                            registry.save(record.definition, runners, record.revision)
                        } }, enabled = registry.problem.isBlank(), label = { Text("OpenCode 新会话") })
                        QuietChoice("claude" in record.desiredRunners, { change {
                            val runners = if ("claude" in record.desiredRunners) record.desiredRunners - "claude" else record.desiredRunners + "claude"
                            registry.save(record.definition, runners, record.revision)
                        } }, enabled = registry.problem.isBlank(), label = { Text(if (hostKey == "@local") "Claude 本地任务" else "Claude 新会话") })
                        QuietChoice("codex" in record.desiredRunners, { change {
                            val runners = if ("codex" in record.desiredRunners) record.desiredRunners - "codex" else record.desiredRunners + "codex"
                            registry.save(record.definition, runners, record.revision)
                        } }, enabled = registry.problem.isBlank(), label = { Text("Codex 新会话") })
                    }
                    Text("已打开的会话保留其已加载版本。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    TextButton({ change { registry.retire(record.definition.key, record.revision) } }, enabled = registry.problem.isBlank()) { Text("从新会话配置中移除") }
                } else TextButton({ change { registry.restore(record.definition.key, record.revision) } }, enabled = registry.problem.isBlank()) { Text("恢复配置") }
            } }
        }
    }
}
