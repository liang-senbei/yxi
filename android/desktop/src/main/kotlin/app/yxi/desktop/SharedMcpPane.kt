package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable internal fun SharedMcpPane(state: AppState, conn: Conn?) {
    val hostKey = if (state.pluginLocation == "本地") "@local" else conn?.let { projectKey(it.host, "/") }
    if (hostKey == null) { Text("请先选择服务器"); return }
    val registry = state.sharedMcp
    val t = Tokens.current
    var adding by remember(hostKey) { mutableStateOf(false) }
    var error by remember(hostKey) { mutableStateOf("") }
    var showHistory by remember(hostKey) { mutableStateOf(false) }
    fun change(action: () -> Unit) { runCatching { action(); error = "" }.onFailure { error = it.message.orEmpty() } }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var remote by remember { mutableStateOf(true) }
        var endpoint by remember { mutableStateOf("") }
        var program by remember { mutableStateOf("") }
        var arguments by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        val id = remember { UUID.randomUUID().toString() }
        WorkbenchDialog(onDismissRequest = { adding = false }, title = { Text("添加通用 MCP 配置") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                Row { QuietChoice(remote, { remote = true }, label = { Text("服务地址") }); QuietChoice(!remote, { remote = false }, label = { Text("启动程序") }) }
                if (remote) OutlinedTextField(endpoint, { endpoint = it }, label = { Text("MCP 服务 URL") }, singleLine = true)
                else {
                    OutlinedTextField(program, { program = it }, label = { Text("目标机器上的程序路径或命令名") }, singleLine = true)
                    OutlinedTextField(arguments, { arguments = it }, label = { Text("参数，每行一个，空格按原样保留") }, minLines = 2, maxLines = 5)
                }
                Text("保存后可为 OpenCode 新会话启用。Claude、Codex 的应用入口仍在接入。", style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message, color = t.danger)
            }
        }, confirmButton = { TextButton({
            runCatching {
                val definition = SharedMcpDefinition(hostKey, id, "manual", "1", name.trim(),
                    if (remote) emptyList() else listOf(program.trim()) + arguments.lineSequence().filter { it.isNotEmpty() }.toList(), endpoint.trim().takeIf { remote })
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
            Text("同一机器登记一次，分别选择运行器。当前 OpenCode 会在新建会话时加载并核对连接；配置保存不代表已安装或已授权。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            if (registry.problem.isNotBlank()) Text(registry.problem, color = t.danger)
            if (error.isNotBlank()) Text(error, color = t.danger)
        }
        items(registry.forHost(hostKey, showHistory).filter { it.definition.name.contains(state.pluginMarketQuery, true) }, key = { it.definition.key }) { record ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${record.definition.name} · ${record.definition.version}" + if (record.retired) " · 历史记录" else "", style = MaterialTheme.typography.titleSmall)
                Text(if (record.definition.url != null) "远程 MCP 服务" else "本机程序 / stdio MCP", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                if (!record.retired) {
                    Row {
                        QuietChoice("opencode" in record.desiredRunners, { change {
                            val runners = if ("opencode" in record.desiredRunners) record.desiredRunners - "opencode" else record.desiredRunners + "opencode"
                            registry.save(record.definition, runners, record.revision)
                        } }, enabled = registry.problem.isBlank(), label = { Text("OpenCode 新会话") })
                        QuietChoice("claude" in record.desiredRunners, {}, enabled = false, label = { Text("Claude · 接入中") })
                        QuietChoice("codex" in record.desiredRunners, {}, enabled = false, label = { Text("Codex · 接入中") })
                    }
                    Text("已打开的会话保留其已加载版本。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    TextButton({ change { registry.retire(record.definition.key, record.revision) } }, enabled = registry.problem.isBlank()) { Text("从新会话配置中移除") }
                } else TextButton({ change { registry.restore(record.definition.key, record.revision) } }, enabled = registry.problem.isBlank()) { Text("恢复配置") }
            } }
        }
    }
}
