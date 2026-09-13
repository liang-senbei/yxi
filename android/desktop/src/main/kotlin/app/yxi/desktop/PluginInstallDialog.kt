package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun PluginInstallDialog(state: AppState, conn: Conn, plugin: CatalogPlugin, dismiss: () -> Unit) {
    val coroutine = rememberCoroutineScope()
    var scope by remember { mutableStateOf("user") }
    var directory by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val t = Tokens.current
    WorkbenchDialog(onDismissRequest = dismiss, title = { Text(if (prepared == null) "安装插件" else "确认安装目标") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(plugin.name.ifBlank { plugin.id }, style = MaterialTheme.typography.titleMedium)
            Text("主机：${conn.host.label}")
            Text("市场：${plugin.marketplace}", style = MaterialTheme.typography.bodySmall)
            if (plugin.location.isNotBlank()) Text(plugin.location, style = MaterialTheme.typography.bodySmall)
            if (prepared == null) {
                listOf("user" to "用户级 · 此主机", "project" to "项目级 · 共享配置", "local" to "本地项目级 · 当前用户配置").forEach { (value, label) ->
                    Row { RadioButton(scope == value, { scope = value }, enabled = !busy); TextButton({ scope = value }, enabled = !busy) { Text(label) } }
                }
                if (scope != "user") OutlinedTextField(directory, { directory = it }, label = { Text("服务器上的项目绝对路径") }, singleLine = true, enabled = !busy)
            } else {
                Text("范围：${when(scope) { "user" -> "用户级"; "project" -> "项目级"; else -> "本地项目级" }}")
                Text(prepared!!.getString("path"), style = MaterialTheme.typography.bodySmall)
                Text("安装前保存配置与安装记录副本。现有会话不会被重启。", style = MaterialTheme.typography.bodySmall)
            }
            Text("目录未提供完整权限、依赖和兼容性声明；安装后仍需验证插件功能及登录。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            if (error.isNotBlank()) Text(error, color = t.danger, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton({
            val ready = prepared
            if (ready != null) {
                runCatching { check(state.conn === conn); state.pluginOperations.submit(conn, ready); dismiss() }.onFailure { error = it.message.orEmpty() }
            } else coroutine.launch {
                busy = true; error = ""
                try {
                    check(state.conn === conn) { "目标主机已切换" }
                    require(scope == "user" || directory.startsWith('/') && directory.none { it < ' ' }) { "请输入服务器上的绝对项目路径" }
                    val request = JSONObject().put("action", "prepare-install").put("operation", UUID.randomUUID().toString().replace("-", "")).put("plugin", plugin.id).put("scope", scope).put("directory", directory).put("catalogFingerprint", plugin.fingerprint)
                    val result = PluginOperationPlan.result(conn.ssh.exec(PluginOperationPlan.command(request)))
                    check(result.getString("state") == "prepared") { when(result.getString("state")) { "already-installed" -> "此范围已经安装，请返回主机插件查看"; "catalog-changed" -> "目录条目已变化，请关闭并刷新目录"; else -> "准备安装失败，请检查目标路径与连接" } }
                    prepared = request.put("action", "install").put("fingerprint", result.getString("fingerprint")).put("path", result.getString("path"))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message.orEmpty() }
                finally { busy = false }
            }
        }, enabled = !busy && conn.status == Conn.Status.Connected) { Text(if (busy) "核对中…" else if (prepared == null) "核对安装目标" else "确认安装") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
