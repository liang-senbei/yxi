package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One plugin destination with explicit local/remote ownership. */
@Composable
internal fun PluginsPane(state: AppState) {
    var catalog by remember { mutableStateOf(false) }
    val conn = state.conn
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("插件", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("本地", "服务器").forEach { location ->
                FilterChip(state.pluginLocation == location, { state.pluginLocation = location }, label = { Text("${location}插件") })
            }
        }
        if (state.pluginLocation == "本地") {
            Text("此电脑上的工具与扩展", style = MaterialTheme.typography.bodyMedium, color = Tokens.current.textMuted)
            OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Android 模拟器", style = MaterialTheme.typography.titleMedium)
                        Text("内置 · 本机 Windows", style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
                        Text("查看本机虚拟设备，启动、停止并查看启动日志。", style = MaterialTheme.typography.bodyMedium)
                    }
                    FilledTonalButton({ state.showAndroidEmulator = true }) { Text("打开") }
                }
            }
        } else {
            Text(conn?.host?.label?.let { "当前服务器 · $it" } ?: "先在侧边栏选择服务器", style = MaterialTheme.typography.bodyMedium, color = Tokens.current.textMuted)
            if (conn != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!catalog, { catalog = false }, label = { Text("已安装") })
                    FilterChip(catalog, { catalog = true }, label = { Text("插件目录") })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (catalog) PluginCatalogPane(state, conn) { catalog = false }
                    else PluginInventoryPane(state, conn)
                }
            }
        }
    }
}
