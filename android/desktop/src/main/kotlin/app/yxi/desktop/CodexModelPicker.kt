package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun CodexModelPicker(controller: CodexTaskController, onRoutes: () -> Unit) {
    val scope = rememberCoroutineScope()
    var modelMenu by remember(controller) { mutableStateOf(false) }
    var effortMenu by remember(controller) { mutableStateOf(false) }
    val chosen = controller.models.firstOrNull { it.model == controller.selectedModel }
    NativeOverlay(modelMenu || effortMenu)
    fun effortLabel(value: String) = when (value) {
        "none" -> "关闭思考"; "minimal" -> "最少"; "low" -> "低"; "medium" -> "中"
        "high" -> "高"; "xhigh" -> "更高"; else -> value
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                TextButton({ modelMenu = true; if (controller.models.isEmpty()) scope.launch { controller.refreshModels() } },
                    enabled = controller.ready && !controller.sending) {
                    Text(if (controller.modelsLoading) "读取模型…" else chosen?.label ?: controller.selectedModel ?: "沿用任务模型")
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    DropdownMenuItem(text = { Text("沿用任务当前模型") }, onClick = { controller.chooseModel(null); modelMenu = false })
                    controller.models.forEach { model ->
                        DropdownMenuItem(text = { Text(model.label) }, onClick = { controller.chooseModel(model.model); modelMenu = false })
                    }
                    DropdownMenuItem(text = { Text("刷新服务器模型列表") }, enabled = !controller.modelsLoading,
                        onClick = { scope.launch { controller.refreshModels() } })
                }
            }
            if (!chosen?.efforts.isNullOrEmpty()) Box {
                TextButton({ effortMenu = true }, enabled = controller.ready && !controller.sending) {
                    Text("思考 · " + (controller.selectedEffort?.let(::effortLabel) ?: "默认"))
                }
                DropdownMenu(effortMenu, { effortMenu = false }) {
                    chosen?.efforts.orEmpty().forEach { effort ->
                        DropdownMenuItem(text = { Text(effortLabel(effort)) }, onClick = { controller.chooseEffort(effort); effortMenu = false })
                    }
                }
            }
            TextButton(onRoutes) { Text("线路设置") }
        }
        if (controller.selectedModel != null) Text("用于之后发送的轮次；引导消息沿用当前轮次模型。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        if (controller.configuredProvider.isNotBlank() || controller.configuredModel.isNotBlank()) Text(
            "本次连接配置：" + listOf(controller.configuredProvider, controller.configuredModel).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        if (controller.reportedModel.isNotBlank()) Text(
            "任务记录模型：" + controller.reportedModel,
            style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        if (controller.modelNotice.isNotBlank()) Text(controller.modelNotice, style = MaterialTheme.typography.bodySmall, color = Tokens.current.warning)
        if (controller.modelError.isNotBlank()) Text(controller.modelError, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
    }
}
