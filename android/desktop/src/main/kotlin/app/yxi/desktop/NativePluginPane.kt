package app.yxi.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NativePluginPane(state: AppState, conn: Conn?, installedOnly: Boolean) {
    val store = remember(state, conn) { state.nativePlugins(conn) }
    val target = conn?.host?.label ?: "本地电脑"
    var selected by remember(store) { mutableStateOf<NativePlugin?>(null) }
    var category by remember(store) { mutableStateOf("全部") }
    LaunchedEffect(store) { store.refresh() }
    val t = Tokens.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (installedOnly) "已安装插件" else "发现插件", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton({ store.refresh() }, enabled = !store.busy) { Text("刷新") }
        }
        if (store.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (store.error.isNotBlank()) Text(store.error, color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (store.message.isNotBlank()) Text(store.message, color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (store.pendingId != null) Text("${store.pendingId} · 安装结果待核对", color = t.warning, style = MaterialTheme.typography.bodySmall)
        store.authLinks.forEach { (name, url) ->
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton({ runCatching { uriHandler.openUri(url) } }) { Text("连接 $name") }
        }
        val query = state.pluginMarketQuery.trim()
        val matching = store.entries.filter { (!installedOnly || it.installed) &&
            "${it.title} ${it.name} ${it.description} ${it.category} ${categoryLabel(it.category)} ${it.marketplace}".contains(query, true) }
        val builtinMatches = conn == null && "Android 模拟器 安卓模拟器 开发工具 本地 Windows".contains(query, true)
        val total = matching.size + (if (builtinMatches) 1 else 0)
        val counts = matching.groupingBy { categoryLabel(it.category) }.eachCount().toMutableMap().apply {
            if (builtinMatches) this["开发工具"] = getOrDefault("开发工具", 0) + 1
        }
        LaunchedEffect(counts.keys) { if (category != "全部" && category !in counts) category = "全部" }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("全部") + PluginCategories.order.filter { it in counts }).forEach { label ->
                QuietChoice(category == label, { category = label }, label = { Text("$label ${if (label == "全部") total else counts[label] ?: 0}") })
            }
        }
        val shown = matching.filter { category == "全部" || categoryLabel(it.category) == category }
        val showBuiltin = builtinMatches && category in setOf("全部", "开发工具")
        Text("${shown.size + (if (showBuiltin) 1 else 0)} 个插件", style = MaterialTheme.typography.labelMedium, color = t.textMuted)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 760.dp) 2 else 1
            val grouped = shown.groupBy { categoryLabel(it.category) }
            val groups = PluginCategories.order.filter { it in grouped || it == "开发工具" && showBuiltin }.associateWith { grouped[it].orEmpty() }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!store.busy && shown.isEmpty() && !showBuiltin) item {
                    Text(if (installedOnly) "这台机器暂无匹配的已安装 Codex 插件" else "暂无匹配插件；目录由这台机器的 Codex 登录和市场配置提供",
                        color = t.textMuted, modifier = Modifier.padding(vertical = 20.dp))
                }
                groups.forEach { (category, plugins) ->
                    item("category:$category") { Text(category, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }
                    if (category == "开发工具" && showBuiltin) item("builtin:android") { LocalEmulatorPluginCard { state.showAndroidEmulator = true } }
                    items(plugins.sortedBy { it.title.lowercase() }.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { p ->
                                Surface(onClick = { selected = p }, modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = t.surface1) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CatalogLogo(p)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(p.description.ifBlank { p.marketplace }, style = MaterialTheme.typography.bodySmall,
                                                color = t.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (p.installed) Text(if (p.enabled) "已安装" else "已停用", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                                        else Icon(Icons.Outlined.Add, "查看插件", Modifier.size(18.dp), tint = t.textMuted)
                                    }
                                }
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    selected?.let { p ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(p.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.description.ifBlank { p.name })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CatalogLogo(p); Text(p.title)
                }
                Text("安装位置：$target\n分类：${categoryLabel(p.category)}\n来源：${p.marketplace}\n版本：${p.version.ifBlank { "未提供" }}")
                Text("运行器接入：Codex 已接入；Claude Code、OpenCode 等共享接入尚未完成。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                Text(if (conn == null) "本地插件供这台电脑共用，不随服务器切换。" else "安装到当前服务器的 Codex。其他服务器需分别安装。")
                Text("服务插件可能还需账号授权。同一账号的云端连接可能共享；已安装不代表服务已连接。", color = t.textMuted)
                if (!p.installable && !p.installed) Text("此插件需在原生 Codex 中处理权限或安装说明。", color = t.warning)
            }
        }, confirmButton = {
            if (!p.installed) Button({ store.install(p); selected = null }, enabled = p.installable && !store.busy && store.pendingId == null &&
                (conn == null || state.conn === conn && conn.status == Conn.Status.Connected)) { Text("安装到 $target") }
            else TextButton({ selected = null }) { Text("完成") }
        }, dismissButton = { TextButton({ selected = null }) { Text("关闭") } })
    }
}

internal fun categoryLabel(value: String) = PluginCategories.label(value)

/** Publisher URLs from the live catalog; never substitute an unrelated brand asset. */
@Composable internal fun CatalogLogo(plugin: NativePlugin) {
    val dark = Tokens.current.dark
    var bitmap by remember(plugin, dark) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(plugin, dark) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = PluginIcons.loader.load(plugin, dark) ?: return@withContext null
                org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image, "${plugin.title} 图标", Modifier.size(28.dp))
    else Icon(Icons.Outlined.Extension, "${plugin.title} 图标暂不可用", Modifier.size(28.dp), tint = Tokens.current.textMuted)
}
