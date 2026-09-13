package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun PluginInventoryPane(conn: Conn) {
    val t = Tokens.current
    var revision by remember(conn) { mutableStateOf(0) }
    var snapshot by remember(conn) { mutableStateOf<PluginInventory?>(null) }
    var error by remember(conn) { mutableStateOf("") }
    var busy by remember(conn) { mutableStateOf(true) }
    LaunchedEffect(conn, revision) {
        busy = true; snapshot = null; error = ""
        try { snapshot = PluginInventory.parse(conn.ssh.exec(PluginInventory.command())) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(180) ?: "读取失败" }
        finally { busy = false }
    }
    PluginInventoryContent(conn.host.label, snapshot, error, busy) { revision++ }
}

@Composable
internal fun PluginInventoryContent(host: String, snapshot: PluginInventory?, error: String, busy: Boolean, refresh: () -> Unit) {
    val t = Tokens.current
    var query by remember(host) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("主机插件", style = MaterialTheme.typography.headlineSmall)
                Text("$host · Claude Code", color = t.textMuted)
            }
            OutlinedButton(refresh, enabled = !busy) { Text("刷新状态") }
        }
        Text("安装记录与 Claude 插件列表分别核对；查询在主机家目录执行，不代表当前任务已经加载。", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = t.danger)
        snapshot?.let { data ->
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索插件、来源或项目") }, shape = RoundedCornerShape(14.dp))
            Text("${data.plugins.size} 条安装记录 · ${data.plugins.count { it.runnerState == "reported-error" || !it.present }} 条需检查", style = MaterialTheme.typography.labelMedium, color = t.textMuted)
            val shown = data.plugins.filter { query.isBlank() || "${it.id} ${it.project} ${it.path}".contains(query.trim(), ignoreCase = true) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              items(data.warnings) { Text(it, color = t.warning) }
              if (data.plugins.isEmpty()) item { Text(if (data.warnings.isEmpty()) "此主机暂无 Claude 插件安装记录" else "安装记录读取不完整，请修复后刷新", color = t.textMuted) }
              else if (shown.isEmpty()) item { Text("没有匹配的插件", color = t.textMuted) }
              items(shown) { plugin ->
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), color = t.surface2) { Text(plugin.id.take(1).uppercase(), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium, color = t.accent) }
                            Column(Modifier.weight(1f)) {
                                Text(plugin.id.substringBefore('@'), style = MaterialTheme.typography.titleMedium)
                                Text(plugin.id.substringAfter('@', "来源未记录"), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                            }
                        }
                        Text("${plugin.version.ifBlank { "版本未知" }} · ${when (plugin.scope) { "user" -> "用户级"; "project" -> "项目级"; "local" -> "本地项目级"; else -> "范围未知" }}", color = t.textMuted)
                        Text(if (plugin.present) "安装目录存在" else "安装目录缺失或不可访问", color = if (plugin.present) t.textSecondary else t.warning)
                        Text(when (plugin.runnerState) {
                            "listed" -> "运行器已列出 · ${when (plugin.runnerEnabled) { true -> "启用"; false -> "停用"; null -> "启停未知" }}"
                            "reported-error" -> "运行器报告插件错误 · 请在服务器检查"
                            "unrecognized" -> "运行器列表未找到此安装记录"
                            else -> "运行器状态未确认"
                        }, color = if (plugin.runnerState == "listed") t.textSecondary else t.warning, style = MaterialTheme.typography.bodySmall)
                        Text("用户默认：${when (plugin.userEnabled) { true -> "启用"; false -> "停用"; null -> "未明确设置" }}", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Column {
                            if (plugin.project.isNotBlank()) Text("项目：${plugin.project}", style = MaterialTheme.typography.bodySmall)
                            Text(plugin.path.ifBlank { "未记录安装位置" }, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        } }
                    }
                }
            }
            }
        }
    }
}
