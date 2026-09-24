package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun PluginMcpImportPreview(plugin: NativePlugin, registry: SharedMcpRegistry, dialogModifier: Modifier = Modifier, entryModifier: Modifier = Modifier) {
    var previews by remember(plugin) { mutableStateOf<List<PluginMcpImport.Preview>>(emptyList()) }
    var error by remember(plugin) { mutableStateOf("") }
    var loading by remember(plugin) { mutableStateOf(true) }
    var selected by remember(plugin) { mutableStateOf<PluginMcpImport.Preview?>(null) }
    var runners by remember(plugin) { mutableStateOf(setOf("claude", "codex", "opencode")) }
    var saved by remember(plugin) { mutableStateOf("") }
    LaunchedEffect(plugin) {
        try { previews = withContext(Dispatchers.IO) { PluginMcpImport.read(plugin) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "无法读取通用 MCP 定义" else "本地 MCP 定义格式不支持，请在共享配置手动核对" }
        finally { loading = false }
    }
    if (loading) Text("正在检查本地通用 MCP 定义…")
    if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
    if (saved.isNotBlank()) Text(saved, style = MaterialTheme.typography.bodySmall)
    previews.forEach { preview ->
        val registered = registry.records.any { it.definition.key == preview.definition.key && !it.retired }
        TextButton({ selected = preview }, modifier = entryModifier, enabled = !registered) { Text(if (registered) "${preview.definition.name} · 已登记共享配置" else "登记共享配置 · ${preview.definition.name}") }
    }
    selected?.let { preview ->
        AlertDialog(modifier = dialogModifier, onDismissRequest = { selected = null }, title = { Text("登记本地共享 MCP") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val d = preview.definition
                Text("来源：${plugin.marketplace}\n版本：${d.version}\n定义：${preview.manifest}")
                SelectionContainer { Text(d.url ?: d.command.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                Text("环境变量引用：${d.environmentNames.joinToString().ifBlank { "无" }}\n请求头引用：${d.headerVariables.entries.joinToString { "${it.key} ← ${it.value}" }.ifBlank { "无" }}")
                Text("供以下运行器使用：")
                listOf("claude" to "Claude Code", "codex" to "Codex", "opencode" to "OpenCode").forEach { (id, label) ->
                    Row { Checkbox(id in runners, { checked -> runners = if (checked) runners + id else runners - id }); Text(label, Modifier.padding(top = 12.dp)) }
                }
                Text("只登记这台电脑的共享定义，不启动命令或复制账号凭据。加载与授权需在目标会话分别核对；插件资源仍来自上面的本地目录。", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            }
        }, confirmButton = { Button({
            runCatching { PluginMcpImport.confirm(plugin, preview, registry, runners) }
                .onSuccess { saved = "已登记 ${preview.definition.name}，可在共享配置查看；尚未确认运行器加载或授权。"; selected = null; error = "" }
                .onFailure { error = it.message ?: "登记失败" }
        }, enabled = runners.isNotEmpty()) { Text("确认登记") } }, dismissButton = { TextButton({ selected = null }) { Text("取消") } })
    }
}
