package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("主机插件", style = MaterialTheme.typography.headlineSmall)
                Text("${conn.host.label} · Claude Code", color = t.textMuted)
            }
            OutlinedButton({ revision++ }, enabled = !busy) { Text("刷新状态") }
        }
        Text("查看这台服务器的安装记录与用户默认设置。项目覆盖和运行器加载状态尚未验证。", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = t.danger)
        snapshot?.let { data ->
            data.warnings.forEach { Text(it, color = t.warning) }
            if (data.plugins.isEmpty()) Text(if (data.warnings.isEmpty()) "此主机暂无 Claude 插件安装记录" else "安装记录读取不完整，请修复后刷新", color = t.textMuted)
            data.plugins.forEach { plugin ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(plugin.id, style = MaterialTheme.typography.titleMedium)
                        Text("${plugin.version.ifBlank { "版本未知" }} · ${when (plugin.scope) { "user" -> "用户级"; "project" -> "项目级"; "local" -> "本地项目级"; else -> "范围未知" }}", color = t.textMuted)
                        Text(if (plugin.present) "安装目录存在 · 待运行器验证" else "安装目录缺失或不可访问", color = if (plugin.present) t.textSecondary else t.warning)
                        Text("用户默认：${when (plugin.userEnabled) { true -> "启用"; false -> "停用"; null -> "未明确设置" }}", style = MaterialTheme.typography.bodySmall)
                        if (plugin.project.isNotBlank()) Text("项目：${plugin.project}", style = MaterialTheme.typography.bodySmall)
                        Text(plugin.path.ifBlank { "未记录安装位置" }, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                }
            }
        }
    }
}
