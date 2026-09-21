package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import app.yxi.agent.Session
import kotlinx.coroutines.launch

internal data class ConversationModelChange(val model: String?, val effort: String?)

@Composable
internal fun ConversationModelMenu(conn: Conn, session: Session, currentModel: String, currentEffort: String, routes: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var models by remember(conn) { mutableStateOf(listOf<String>()) }
    var chosen by remember(currentModel) { mutableStateOf(currentModel) }
    var effort by remember(currentEffort) { mutableStateOf(currentEffort.ifBlank { "high" }.replace("mid", "medium")) }
    val levels = listOf("low", "medium", "high", "xhigh", "max")
    val labels = listOf("轻度", "中等", "高", "更高", "最高")
    val pending = conn.modelChanges[session.runtimeId]
    NativeOverlay(menu)
    Box {
        TextButton({
            menu = true
            scope.launch {
                val available = runCatching { app.yxi.agent.Model.available(conn.ssh, conn.host.id) }.getOrNull()
                val routesModels = runCatching { Lines.list(conn.ssh).orEmpty().filterNot { it.isCodex }.map(::routeModel) }.getOrDefault(emptyList())
                models = (listOf("default", currentModel) + available?.models.orEmpty() + routesModels).filter { it.isNotBlank() }.distinct()
            }
        }) { Text((pending?.model ?: currentModel).ifBlank { "选择模型" } + " · " + labels.getOrElse(levels.indexOf(pending?.effort ?: effort)) { effort } + " ⌄") }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.width(300.dp)) {
            Text("选择模型", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            (models.ifEmpty { listOf("default", currentModel).filter { it.isNotBlank() }.distinct() }).forEach { model ->
                DropdownMenuItem(text = { Text(if (model == "default") "默认" else model) }, trailingIcon = { if (chosen == model) Text("✓") }, onClick = { chosen = model })
            }
            HorizontalDivider()
            Column(Modifier.padding(16.dp, 8.dp)) {
                Text("思考强度 · " + labels.getOrElse(levels.indexOf(effort)) { effort })
                Slider(value = levels.indexOf(effort).coerceAtLeast(0).toFloat(), onValueChange = { effort = levels[it.toInt().coerceIn(0, levels.lastIndex)] }, valueRange = 0f..4f, steps = 3)
                Text("工作中选择会在空闲后应用；支持程度由模型决定。Claude 命令也可能保存为默认设置。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                TextButton({ conn.modelChanges[session.runtimeId] = ConversationModelChange(chosen.takeIf { it != currentModel }, effort); menu = false }, enabled = chosen.isNotBlank()) { Text("应用 · 下次请求生效") }
                TextButton({ menu = false; routes() }) { Text("管理第三方线路") }
            }
        }
    }
}
