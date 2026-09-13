package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
internal fun PluginCatalogPane(conn: Conn) {
    val t = Tokens.current
    var revision by remember(conn) { mutableStateOf(0) }
    var entries by remember(conn) { mutableStateOf<List<CatalogPlugin>>(emptyList()) }
    var busy by remember(conn) { mutableStateOf(true) }
    var error by remember(conn) { mutableStateOf("") }
    var query by remember(conn) { mutableStateOf("") }
    LaunchedEffect(conn, revision) {
        busy = true; entries = emptyList(); error = ""
        try { entries = PluginCatalog.parse(conn.ssh.exec(PluginCatalog.command())) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("插件目录", style = MaterialTheme.typography.headlineSmall)
                Text(conn.host.label, color = t.textMuted)
            }
            OutlinedButton({ revision++ }, enabled = !busy) { Text("刷新目录") }
        }
        Text("来自这台主机已配置的市场。目录条目不代表已安装或已验证兼容。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索插件或市场") }, shape = RoundedCornerShape(14.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = t.danger)
        val shown = entries.filter { "${it.id} ${it.description}".contains(query.trim(), ignoreCase = true) }
        Text("${shown.size} / ${entries.size} 个插件", style = MaterialTheme.typography.labelMedium, color = t.textMuted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!busy && error.isBlank() && shown.isEmpty()) item { Text("没有匹配的目录条目", color = t.textMuted) }
            items(shown) { p -> OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name.ifBlank { p.id }, style = MaterialTheme.typography.titleMedium)
                    Text(p.marketplace, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                    Text("版本：${p.version.ifBlank { "目录未提供" }} · 来源：${when(p.source) { "marketplace-path" -> "市场内目录"; "url", "git-subdir", "github" -> "代码仓库"; "npm", "pip" -> "软件包"; "command" -> "安装命令"; else -> "未识别" }}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    if (p.location.isNotBlank()) Text(p.location, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
            } }
        }
    }
}
