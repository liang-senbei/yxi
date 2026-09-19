package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun PluginHistoryDialog(conn: Conn, operations: PluginOperations, dismiss: () -> Unit, restore: (PluginOperationEntry) -> Unit) {
    val t = Tokens.current
    val host = projectKey(conn.host, "/")
    var query by remember { mutableStateOf("") }
    var reviewing by remember { mutableStateOf<PluginOperationEntry?>(null) }
    reviewing?.let { entry ->
        WorkbenchDialog(onDismissRequest = { reviewing = null }, title = { Text("确认已核对插件状态") },
            text = { Text("请先查看服务器插件列表，确认安装、版本与启用状态，并确认没有插件操作仍在运行。继续后将记录为“人工已核对”，允许后续操作；这不会重试原操作，也不代表安装成功。") },
            confirmButton = { TextButton({ operations.review(conn, entry); reviewing = null }, enabled = conn.status == Conn.Status.Connected && entry.id !in operations.running && operations.error.isBlank()) { Text("已核对，解除待确认") } },
            dismissButton = { TextButton({ reviewing = null }) { Text("返回") } })
        return
    }
    val listState = rememberLazyListState()
    val entries = operations.history(host).filter { "${it.request} ${it.status} ${it.id}".contains(query.trim(), ignoreCase = true) }
    WorkbenchDialog(onDismissRequest = dismiss, title = { Text("插件操作记录 · ${conn.host.label}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索插件或操作编号") }, modifier = Modifier.fillMaxWidth())
            Text("最近记录在前，恢复前会再次检查服务器配置。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            Box(Modifier.height(380.dp).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entries.isEmpty()) item { Text("没有匹配的操作记录", color = t.textMuted) }
                items(entries, key = { it.id }) { entry ->
                    val request = JSONObject(entry.request)
                    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(request.optString("plugin").ifBlank { "插件操作" }, style = MaterialTheme.typography.titleSmall)
                            Text(when(request.optString("action")) { "install" -> "安装"; "update" -> "更新"; "uninstall" -> "卸载"; "restore" -> "恢复设置"; "rollback" -> "恢复插件包"; "set" -> if(request.optBoolean("enabled")) "启用" else "停用"; else -> "操作" } + " · " + when(entry.status) { "sending" -> "发送中"; "unknown" -> "待确认"; "rejected" -> "未执行"; "reviewed" -> "人工已核对"; else -> "已确认" }, style = MaterialTheme.typography.bodySmall, color = if(entry.status == "unknown") t.warning else t.textMuted)
                            if (entry.beforeVersion.isNotBlank() || entry.afterVersion.isNotBlank()) Text(if (entry.beforeVersion.isBlank()) "版本：${entry.afterVersion}" else "${entry.beforeVersion} → ${entry.afterVersion.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                            Text("范围：${when(request.optString("scope")) { "user" -> "用户级"; "project" -> "项目级"; "local" -> "本地项目级"; else -> "未记录" }}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                            if (request.optString("directory").isNotBlank()) Text(request.optString("directory"), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                            SelectionContainer { Text(entry.id, style = MaterialTheme.typography.labelSmall, color = t.textMuted) }
                            Row {
                                if (entry.status == "reviewed") Text("人工已核对，未自动确认执行成功", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                                if (entry.status == "unknown") TextButton({ reviewing = entry }, enabled = conn.status == Conn.Status.Connected && entry.id !in operations.running && operations.error.isBlank()) { Text("人工核对…") }
                                TextButton({ operations.query(conn, entry) }, enabled = conn.status == Conn.Status.Connected && entry.id !in operations.running && entry.status != "rejected") { Text("查询结果") }
                                if(entry.status in setOf("configured", "updated", "uninstalled")) TextButton({ restore(entry) }, enabled = conn.status == Conn.Status.Connected && !operations.unresolved(host) && operations.error.isBlank()) { Text(if(entry.status == "configured") "恢复设置…" else "恢复插件包…") }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        } }, confirmButton = { TextButton(dismiss) { Text("关闭") } })
}

