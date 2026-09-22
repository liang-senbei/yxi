package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One plugin destination with explicit local/remote ownership. */
@Composable
internal fun PluginsPane(state: AppState) {
    val conn = state.conn
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("插件", style = MaterialTheme.typography.headlineMedium)
        PluginTabs(listOf("本地插件" to Icons.Outlined.Computer, "服务器插件" to Icons.Outlined.Dns),
            if (state.pluginLocation == "本地") 0 else 1) { state.pluginLocation = if (it == 0) "本地" else "服务器" }
        Text(if (state.pluginLocation == "本地") "此电脑共用 · 切换服务器不影响本地插件"
            else conn?.host?.label?.let { "当前服务器 · $it" } ?: "先在侧边栏选择服务器",
            style = MaterialTheme.typography.bodyMedium, color = Tokens.current.textMuted)
        PluginTabs(listOf("插件市场" to Icons.Outlined.Storefront, "已安装" to Icons.Outlined.Extension),
            if (state.pluginMarketplace) 0 else 1) { state.pluginMarketplace = it == 0 }
        if (state.pluginLocation != "本地") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("codex" to "Codex", "claude" to "Claude Code").forEach { (engine, label) ->
                FilterChip(state.pluginCatalogRuntime == engine, { state.pluginCatalogRuntime = engine },
                    label = { Text(label) }, leadingIcon = { RunnerBrandIcon(engine, Modifier.size(18.dp)) })
            }
        }
        OutlinedTextField(state.pluginMarketQuery, { state.pluginMarketQuery = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("搜索插件、用途或市场") }, singleLine = true)
        if (state.pluginMarketplace) {
            Box(Modifier.weight(1f).fillMaxWidth()) { PluginMarketplacePane(state, conn) }
            return@Column
        }
        if (state.pluginLocation == "本地") {
            if ("Android 模拟器 安卓".contains(state.pluginMarketQuery.trim(), true)) LocalEmulatorPluginCard { state.showAndroidEmulator = true }
            Box(Modifier.weight(1f)) { NativePluginPane(state, null, installedOnly = true) }
        } else {
            if (conn?.status == Conn.Status.Connected) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.pluginCatalogRuntime == "codex") NativePluginPane(state, conn, installedOnly = true)
                    else PluginInventoryPane(state, conn)
                }
            } else Text("连接当前服务器后查看其插件", color = Tokens.current.textMuted)
        }
    }
}

/** Quiet segmented navigation: no raised chip or heavy focus-like selected outline. */
@Composable
internal fun PluginTabs(options: List<Pair<String, ImageVector>>, selected: Int, select: (Int) -> Unit) {
    val t = Tokens.current
    Row(Modifier.selectableGroup().background(t.surface1, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, (label, icon) ->
            val active = selected == index
            Surface(shape = RoundedCornerShape(9.dp),
                color = if (active) t.accent.copy(alpha = if (t.dark) 0.12f else 0.07f) else Color.Transparent,
                contentColor = if (active) t.accent else t.textMuted,
                border = BorderStroke(1.dp, if (active) t.accent.copy(alpha = 0.20f) else Color.Transparent),
                shadowElevation = 0.dp) {
                Row(Modifier.selectable(active, role = Role.Tab, onClick = { select(index) })
                    .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(icon, null, Modifier.size(17.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}
