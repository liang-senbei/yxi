package app.yxi.desktop

import androidx.compose.foundation.Image
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
    LaunchedEffect(store) { store.refresh() }
    val t = Tokens.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunnerBrandIcon("codex", Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("$target · Codex", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
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
        val shown = store.entries.filter { (!installedOnly || it.installed) &&
            "${it.title} ${it.name} ${it.description} ${it.category} ${it.marketplace}".contains(query, true) }
        Text("${shown.size} 个插件", style = MaterialTheme.typography.labelMedium, color = t.textMuted)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 760.dp) 2 else 1
            val groups = shown.groupBy { categoryLabel(it.category) }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!store.busy && shown.isEmpty()) item {
                    Text(if (installedOnly) "这台机器暂无匹配的已安装 Codex 插件" else "暂无匹配插件；目录由这台机器的 Codex 登录和市场配置提供",
                        color = t.textMuted, modifier = Modifier.padding(vertical = 20.dp))
                }
                groups.forEach { (category, plugins) ->
                    item("category:$category") { Text(category, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }
                    items(plugins.sortedBy { it.title.lowercase() }.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { p ->
                                Surface(onClick = { selected = p }, modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = t.surface1) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CatalogLogo(p.iconUrl)
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
                Text("应用位置：$target\n运行器：Codex\n来源：${p.marketplace}\n版本：${p.version.ifBlank { "未提供" }}")
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

internal fun categoryLabel(value: String) = when (value.lowercase()) {
    "productivity" -> "效率"; "developer tools", "development" -> "开发工具"; "communication" -> "沟通协作"
    "design", "creative", "creativity" -> "创意"; "research", "science", "scientific research" -> "科学研究"
    "business", "business & operations" -> "业务与运营"; "finance" -> "金融"; "security" -> "安全"
    "travel" -> "旅行"; "entertainment" -> "娱乐"; "health", "health & fitness" -> "医疗健康"
    "healthcare" -> "医疗健康"; "education & research" -> "教育与研究"; "data & analytics" -> "数据与分析"
    "engineering" -> "工程"; "other" -> "其他"
    else -> value
}

/** Publisher URLs from the live catalog; never substitute an unrelated brand asset. */
@Composable private fun CatalogLogo(url: String?) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val uri = java.net.URI(url ?: return@withContext null)
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
                val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 5000; connection.readTimeout = 5000
                try {
                    require(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { it.readNBytes(1024 * 1024 + 1) }
                    require(bytes.size <= 1024 * 1024)
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
                } finally { connection.disconnect() }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image, null, Modifier.size(28.dp))
    else Icon(Icons.Outlined.Extension, null, Modifier.size(28.dp), tint = Tokens.current.textMuted)
}
