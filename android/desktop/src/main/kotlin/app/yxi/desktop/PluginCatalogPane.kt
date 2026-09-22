package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class CatalogPlugin(val id: String, val name: String, val description: String, val marketplace: String, val version: String, val source: String, val location: String, val fingerprint: String)
internal object PluginCatalog {
    fun command() = "python3 -c " + Shell.q(PluginCatalog::class.java.getResource("/app/yxi/desktop/plugin-catalog.py")!!.readText())
    fun parse(raw: String): List<CatalogPlugin> {
        val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_CATALOG__:") } ?: error("目录未读到，请检查连接后刷新")
        val value = JSONObject(line.removePrefix("__YXI_CATALOG__:"))
        check(value.getString("state") == "ready") { "插件目录暂不可用：${value.optString("state")}" }
        val entries = value.getJSONArray("entries")
        return (0 until entries.length()).map { val p = entries.getJSONObject(it)
            CatalogPlugin(p.getString("id"), p.getString("name"), p.getString("description"), p.getString("marketplace"), p.getString("version"), p.getString("sourceKind"), p.getString("location"), p.getString("fingerprint"))
        }
    }
}

@Composable
internal fun PluginCatalogPane(state: AppState, conn: Conn, embedded: Boolean = false, searchText: String? = null,
    showInstalled: (() -> Unit)? = null) {
    val t = Tokens.current
    var revision by remember(conn) { mutableStateOf(0) }
    var entries by remember(conn) { mutableStateOf<List<CatalogPlugin>>(emptyList()) }
    var busy by remember(conn) { mutableStateOf(true) }
    var error by remember(conn) { mutableStateOf("") }
    var query by remember(conn) { mutableStateOf("") }
    var selected by remember(conn) { mutableStateOf<CatalogPlugin?>(null) }
    val operations = state.pluginOperations
    val host = projectKey(conn.host, "/")
    val last = operations.latest(host)
    LaunchedEffect(last?.id, last?.status) { if (last?.status in setOf("installed", "uninstalled")) revision++ }
    LaunchedEffect(conn, revision) {
        busy = true; entries = emptyList(); error = ""
        try { entries = PluginCatalog.parse(conn.ssh.exec(PluginCatalog.command())) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().padding(if (embedded) 0.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                if (!embedded) Text("插件市场", style = MaterialTheme.typography.headlineSmall)
                Text("${conn.host.label} · Claude Code 市场", color = t.textMuted, style = MaterialTheme.typography.titleSmall)
            }
            OutlinedButton({ revision++ }, enabled = !busy) { Text("刷新") }
        }
        Text("来自这台主机已配置的市场。目录条目不代表已安装或已验证兼容。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (last != null) Row(Modifier.fillMaxWidth()) {
            Text(when(last.status) { "installed" -> "安装完成，运行器已识别；请在新会话测试"; "sending" -> "插件操作中…"; "unknown" -> "结果待确认，请查询原操作"; "rejected" -> "操作未执行，请刷新目录后核对"; else -> "上次操作已确认" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ operations.query(conn, last) }, enabled = last.id !in operations.running && conn.status == Conn.Status.Connected) { Text("查询结果") }
        }
        if (operations.error.isNotBlank()) Text(operations.error, color = t.danger)
        if (searchText == null) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索插件或市场") }, shape = RoundedCornerShape(14.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = t.danger)
        val shown = entries.filter { "${it.id} ${it.name} ${it.marketplace} ${it.description}".contains((searchText ?: query).trim(), ignoreCase = true) }
        Text("${shown.size} / ${entries.size} 个插件", style = MaterialTheme.typography.labelMedium, color = t.textMuted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!busy && error.isBlank() && shown.isEmpty()) item {
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (entries.isEmpty()) "暂无可安装条目" else "没有匹配的插件", style = MaterialTheme.typography.titleMedium)
                        Text(if (entries.isEmpty()) "已安装的插件可在主机插件中查看和管理。" else "尝试其他名称或市场关键词。", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
                        if (showInstalled != null) TextButton(showInstalled) { Text("查看主机插件") }
                    }
                }
            }
            items(shown) { p -> OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Extension, null, Modifier.size(26.dp), tint = t.accent)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(p.name.ifBlank { p.id }, style = MaterialTheme.typography.titleMedium)
                            Text(p.marketplace, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        }
                    }
                    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                    Text("版本：${p.version.ifBlank { "目录未提供" }} · 来源：${when(p.source) { "marketplace-path" -> "市场内目录"; "url", "git-subdir", "github" -> "代码仓库"; "npm", "pip" -> "软件包"; "command" -> "安装命令"; else -> "未识别" }}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    if (p.location.isNotBlank()) Text(p.location, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    OutlinedButton({ selected = p }, enabled = conn.status == Conn.Status.Connected && !operations.unresolved(host) && operations.error.isBlank() && p.source !in setOf("command", "unknown")) { Text("安装到 ${conn.host.label}") }
                    if (p.source == "command") Text("此来源需要单独授权安装命令。", style = MaterialTheme.typography.bodySmall, color = t.warning)
                    if (p.source == "unknown") Text("暂不支持此插件来源。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
            } }
        }
    }
    selected?.let { plugin -> PluginInstallDialog(state, conn, plugin) { selected = null } }
}
