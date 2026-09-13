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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun PluginInventoryPane(state: AppState, conn: Conn) {
    val t = Tokens.current
    var revision by remember(conn) { mutableStateOf(0) }
    var snapshot by remember(conn) { mutableStateOf<PluginInventory?>(null) }
    var error by remember(conn) { mutableStateOf("") }
    var busy by remember(conn) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val operations = state.pluginOperations
    val hostKey = projectKey(conn.host, "/")
    var prepared by remember(conn) { mutableStateOf<JSONObject?>(null) }
    var preparing by remember(conn) { mutableStateOf(false) }
    var actionError by remember(conn) { mutableStateOf("") }
    LaunchedEffect(conn, revision) {
        busy = true; snapshot = null; error = ""
        try { snapshot = PluginInventory.parse(conn.ssh.exec(PluginInventory.command())) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(180) ?: "读取失败" }
        finally { busy = false }
    }
    val last = operations.latest(hostKey)
    LaunchedEffect(last?.status) { if (last?.status in setOf("configured", "restored", "installed", "uninstalled", "updated", "package-restored")) revision++ }
    Column(Modifier.fillMaxSize()) {
        if (last != null) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (last.status) { "package-restored" -> "插件包已恢复：${last.afterVersion.ifBlank { "版本未知" }} · 请在新会话验证"; "updated" -> "更新结果：${last.beforeVersion.ifBlank { "未知" }} → ${last.afterVersion.ifBlank { "未知" }} · 请在新会话验证"; "uninstalled" -> "插件已卸载 · 持久数据和操作副本已保留"; "installed" -> "插件已安装并被运行器识别 · 请在新会话测试"; "configured" -> "插件设置已更新 · 请在新会话验证加载"; "restored" -> "已恢复操作前设置 · 请在新会话验证加载"; "rejected" -> "变更未执行，配置可能已变化或副本不可用"; "sending" -> "插件操作中…"; else -> "插件操作待确认，请查询原操作" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (last.status == "configured") TextButton({
                prepared = JSONObject(last.request).put("action", "restore").put("restores", last.id).put("operation", UUID.randomUUID().toString().replace("-", ""))
            }, enabled = last.id !in operations.running && !operations.unresolved(hostKey) && operations.error.isBlank() && conn.status == Conn.Status.Connected) { Text("恢复设置") }
            if (last.status in setOf("updated", "uninstalled")) TextButton({
                prepared = JSONObject(last.request).put("action", "rollback").put("restores", last.id).put("operation", UUID.randomUUID().toString().replace("-", "")).put("restoreVersion", last.beforeVersion.ifBlank { JSONObject(last.request).optString("listedVersion") })
            }, enabled = last.id !in operations.running && !operations.unresolved(hostKey) && operations.error.isBlank() && conn.status == Conn.Status.Connected) { Text("恢复插件包") }
            TextButton({ operations.query(conn, last) }, enabled = last.id !in operations.running) { Text("查询结果") }
        }
        if (operations.error.isNotBlank() || actionError.isNotBlank()) Text(operations.error.ifBlank { actionError }, Modifier.padding(horizontal = 24.dp), color = t.danger)
        Box(Modifier.weight(1f)) {
            PluginInventoryContent(conn.host.label, snapshot, error, busy, { revision++ },
                toggle = { plugin, action -> scope.launch {
                    preparing = true; actionError = ""
                    try {
                        val request = JSONObject().put("action", "prepare").put("operation", UUID.randomUUID().toString().replace("-", "")).put("plugin", plugin.id).put("scope", plugin.scope).put("directory", plugin.project).put("enabled", action == "enable")
                        val result = PluginOperationPlan.result(conn.ssh.exec(PluginOperationPlan.command(request)))
                        check(result.getString("state") == "prepared") { "无法准备此范围的变更，请刷新安装记录后重试" }
                        prepared = request.put("action", if (action in setOf("uninstall", "update")) action else "set").put("fingerprint", result.getString("fingerprint")).put("path", result.getString("path")).put("listedVersion", plugin.version)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { actionError = e.message.orEmpty() }
                    finally { preparing = false }
                } }, toggleEnabled = !preparing && !operations.unresolved(hostKey) && conn.status == Conn.Status.Connected && operations.error.isBlank())
        }
    }
    prepared?.let { request ->
        val restoring = request.getString("action") == "restore"
        val rollback = request.getString("action") == "rollback"
        val removing = request.getString("action") == "uninstall"
        val updating = request.getString("action") == "update"
        WorkbenchDialog(onDismissRequest = { prepared = null }, title = { Text(if (rollback) "恢复插件包" else if (updating) "更新插件" else if (removing) "卸载插件" else if (restoring) "恢复操作前设置" else if (request.getBoolean("enabled")) "启用插件" else "停用插件") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("主机：${conn.host.label}")
                Text(request.getString("plugin"))
                Text("范围：${when (request.getString("scope")) { "user" -> "用户级"; "project" -> "项目级"; "local" -> "本地项目级"; else -> "未知" }}")
                Text(request.getString("path"), style = MaterialTheme.typography.bodySmall)
                if (rollback) Text("恢复版本：${request.optString("restoreVersion").ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                if (updating) Text("列表记录版本：${request.optString("listedVersion").ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                Text(if (rollback) "将从副本恢复插件文件及该次操作前的配置，文件放入独立恢复目录。如果之后配置有变化，将停止恢复。当前会话不会重启。" else if (updating) "将请求市场提供的版本，实际版本以更新回执为准。更新前保留插件文件副本，当前会话不会重启。" else if (removing) "仅卸载上述范围，保留插件持久数据，不自动清理依赖。卸载前保存配置和插件文件副本；当前会话不会被重启。" else if (restoring) "恢复此配置文件在该次操作前的内容；如果之后配置或安装记录有变化，将拒绝覆盖。当前会话不会被重启。" else "变更前保存服务器恢复副本。当前运行中的会话不会被重启。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton({
                runCatching { check(state.conn === conn); operations.submit(conn, request); prepared = null }.onFailure { actionError = it.message.orEmpty(); prepared = null }
            }) { Text(if (rollback) "确认恢复" else if (updating) "确认更新" else if (removing) "确认卸载" else "确认变更") } }, dismissButton = { TextButton({ prepared = null }) { Text("取消") } })
    }
}

@Composable
internal fun PluginInventoryContent(host: String, snapshot: PluginInventory?, error: String, busy: Boolean, refresh: () -> Unit, toggle: ((InstalledPlugin, String) -> Unit)? = null, toggleEnabled: Boolean = false) {
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
                        if (toggle != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ toggle(plugin, "enable") }, enabled = toggleEnabled && plugin.scope in setOf("user", "project", "local")) { Text("启用") }
                            TextButton({ toggle(plugin, "disable") }, enabled = toggleEnabled && plugin.scope in setOf("user", "project", "local")) { Text("停用") }
                            TextButton({ toggle(plugin, "update") }, enabled = toggleEnabled && plugin.present && plugin.scope in setOf("user", "project", "local")) { Text("更新") }
                            TextButton({ toggle(plugin, "uninstall") }, enabled = toggleEnabled && plugin.present && plugin.scope in setOf("user", "project", "local")) { Text("卸载", color = if (toggleEnabled && plugin.present) t.danger else t.textMuted) }
                        }
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
