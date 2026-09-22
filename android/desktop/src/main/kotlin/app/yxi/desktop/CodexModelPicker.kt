package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun CodexModelPicker(controller: CodexTaskController, onRoutes: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember(controller) { mutableStateOf(false) }
    var selectionError by remember(controller) { mutableStateOf("") }
    fun select(action: () -> Unit) {
        try { action(); selectionError = "" }
        catch (e: Exception) { selectionError = e.message ?: "模型选择未保存" }
    }
    val chosen = controller.models.firstOrNull { it.model == controller.selectedModel }
    NativeOverlay(menu)
    fun effortLabel(value: String) = when (value) {
        "none" -> "关闭思考"; "minimal" -> "最少"; "low" -> "轻度"; "medium" -> "中等"
        "high" -> "高"; "xhigh" -> "更高"; "max" -> "最高"; else -> value
    }
    Box {
        TextButton({
            menu = true
            if (controller.models.isEmpty()) scope.launch { controller.refreshModels() }
        }, enabled = controller.ready && !controller.sending) {
            Text((chosen?.label ?: controller.selectedModel ?: "沿用任务模型") +
                (controller.selectedEffort?.let { " · ${effortLabel(it)}" } ?: "") + " ⌄")
        }
        DropdownMenu(menu, { menu = false }, Modifier.width(310.dp)) {
            Text("选择模型", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            if (controller.modelsLoading) Text("正在读取模型…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            DropdownMenuItem(text = { Text("沿用任务当前模型") },
                trailingIcon = { if (controller.selectedModel == null) Text("✓") },
                onClick = { select { controller.chooseModel(null) } })
            controller.models.forEach { model ->
                DropdownMenuItem(text = { Text(model.label) },
                    trailingIcon = { if (model.model == controller.selectedModel) Text("✓") },
                    onClick = { select { controller.chooseModel(model.model) } })
            }
            val efforts = chosen?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                HorizontalDivider()
                Column(Modifier.padding(16.dp, 8.dp)) {
                    EffortControl(chosen?.label ?: controller.selectedModel.orEmpty(), efforts, controller.selectedEffort) { value ->
                        select { controller.chooseEffort(value) }
                    }
                }
            }
            Column(Modifier.padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("选择用于下一轮；调整方向沿用正在运行的模型。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (controller.reportedModel.isNotBlank()) Text("最近任务记录 · ${controller.reportedModel}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (controller.configuredProvider.isNotBlank()) Text("当前线路 · ${controller.configuredProvider}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (controller.modelNotice.isNotBlank()) Text(controller.modelNotice, style = MaterialTheme.typography.bodySmall, color = Tokens.current.warning)
                if (controller.modelError.isNotBlank()) Text(controller.modelError, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
                if (selectionError.isNotBlank()) Text(selectionError, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("刷新模型列表") }, enabled = !controller.modelsLoading,
                onClick = { scope.launch { controller.refreshModels() } })
            DropdownMenuItem(text = { Text("管理第三方线路") }, onClick = { menu = false; onRoutes() })
        }
    }
}
