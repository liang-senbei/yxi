package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import app.yxi.agent.Session
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

internal data class ConversationModelChange(val model: String?, val effort: String?)

@Composable
internal fun ConversationModelMenu(conn: Conn, session: Session, currentModel: String, currentEffort: String, routes: () -> Unit) {
    val taskKey = taskNavigationKey(conn.host, session)
    var menu by remember(taskKey) { mutableStateOf(false) }
    var models by remember(taskKey) { mutableStateOf(listOf<String>()) }
    var chosen by remember(taskKey) { mutableStateOf("") }
    var effort by remember(taskKey) { mutableStateOf("") }
    var loading by remember(taskKey) { mutableStateOf(false) }
    var loadError by remember(taskKey) { mutableStateOf("") }
    val reportedEffort = if (currentEffort == "mid") "medium" else currentEffort
    val levels = listOf("low", "medium", "high", "xhigh", "max")
    val labels = listOf("轻度", "中等", "高", "更高", "最高")
    val pending = conn.modelChanges[session.runtimeId]
    NativeOverlay(menu)
    if (pending != null) Text("待切换 · " + listOfNotNull(pending.model, pending.effort?.let { labels.getOrElse(levels.indexOf(it)) { it } }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
    Box {
        TextButton({
            chosen = pending?.model ?: currentModel
            effort = pending?.effort ?: reportedEffort
            menu = true
        }) { Text(currentModel.ifBlank { "选择模型" } + " · " + labels.getOrElse(levels.indexOf(reportedEffort)) { reportedEffort.ifBlank { "思考强度" } } + " ⌄") }
        LaunchedEffect(menu, taskKey) {
            if (!menu) return@LaunchedEffect
            loading = true; loadError = ""
            try {
                val available = app.yxi.agent.Model.available(conn.ssh, conn.host.id)
                val routesModels = Lines.list(conn.ssh).orEmpty().filterNot { it.isCodex }.map(::routeModel)
                models = (listOf("default", currentModel, chosen) + available.models + routesModels).filter { it.isNotBlank() }.distinct()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { loadError = "暂时无法获取完整模型列表，可稍后重新打开。" }
            finally { loading = false }
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.width(300.dp)) {
            Text("选择模型", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            if (loading) Text("正在获取模型…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (loadError.isNotBlank()) Text(loadError, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            (models.ifEmpty { listOf("default", currentModel, chosen).filter { it.isNotBlank() }.distinct() }).forEach { model ->
                DropdownMenuItem(text = { Text(if (model == "default") "默认" else model) }, trailingIcon = { if (chosen == model) Text("✓") }, onClick = { chosen = model })
            }
            HorizontalDivider()
            Column(Modifier.padding(16.dp, 8.dp)) {
                Text("思考强度 · " + labels.getOrElse(levels.indexOf(effort)) { effort.ifBlank { "保持当前" } })
                Slider(value = levels.indexOf(effort).coerceAtLeast(0).toFloat(), onValueChange = { effort = levels[it.roundToInt().coerceIn(0, levels.lastIndex)] }, valueRange = 0f..4f, steps = 3)
                Text("工作中选择会在空闲后应用；支持程度由模型决定。Claude 命令也可能保存为默认设置。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                val changedModel = chosen.takeIf { it.isNotBlank() && it != currentModel }
                val changedEffort = effort.takeIf { it.isNotBlank() && it != reportedEffort }
                TextButton({ conn.modelChanges[session.runtimeId] = ConversationModelChange(changedModel, changedEffort); menu = false }, enabled = session.runtimeId.isNotBlank() && (changedModel != null || changedEffort != null)) { Text("应用选择") }
                TextButton({ menu = false; routes() }) { Text("管理第三方线路") }
            }
        }
    }
}
