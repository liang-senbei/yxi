package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PluginMarketplacePane(state: AppState, conn: Conn?) {
    val t = Tokens.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.pluginLocation == "本地") {
            Box(Modifier.weight(1f).fillMaxWidth()) { NativePluginPane(state, null, installedOnly = false) }
        } else {
            if (conn?.status == Conn.Status.Connected) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.pluginCatalogRuntime == "codex") NativePluginPane(state, conn, installedOnly = false)
                    else PluginCatalogPane(state, conn, embedded = true, searchText = state.pluginMarketQuery) {
                        state.pluginLocation = "服务器"; state.pluginMarketplace = false
                    }
                }
            } else {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Dns, null, Modifier.size(24.dp), tint = t.textMuted)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("服务器插件市场", style = MaterialTheme.typography.titleMedium)
                            Text("在左侧选择并连接服务器后，浏览其可用插件。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalEmulatorPluginCard(open: () -> Unit) {
    val t = Tokens.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Android, null, Modifier.size(30.dp), tint = t.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Android 模拟器", style = MaterialTheme.typography.titleMedium)
                Text("本地 Windows · 开发工具 · 内置入口", style = MaterialTheme.typography.labelMedium, color = t.textMuted)
                Text("管理虚拟设备，启动、停止模拟器并查看运行日志。", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
            FilledTonalButton(open) { Text("打开") }
        }
    }
}
