package app.yxi.desktop

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

internal data class ConversationModelChange(val model: String?, val effort: String?)

@Composable
internal fun ConversationModelMenu(conn: Conn, session: Session, currentModel: String, currentEffort: String, routes: () -> Unit, store: ModelChangeStore? = null, onTerminal: () -> Unit = {}) {
    val taskKey = taskNavigationKey(conn.host, session)
    var menu by remember(taskKey) { mutableStateOf(false) }
    var models by remember(taskKey) { mutableStateOf(listOf<String>()) }
    var mappedCurrent by remember(taskKey, currentModel) { mutableStateOf<String?>(null) }
    var chosen by remember(taskKey) { mutableStateOf("") }
    var effort by remember(taskKey) { mutableStateOf("") }
    var loading by remember(taskKey) { mutableStateOf(false) }
    var loadError by remember(taskKey) { mutableStateOf("") }
    var actionError by remember(taskKey) { mutableStateOf("") }
    val reportedEffort = if (currentEffort == "mid") "medium" else currentEffort
    val levels = listOf("low", "medium", "high", "xhigh", "max")
    val labels = listOf("轻度", "中等", "高", "更高", "最高")
    val pending = conn.modelChanges[session.runtimeId]
    val request = store?.latest(taskKey)
    val requestLabel = when (request?.status) {
        ModelChangeStatus.Pending -> "等待空闲后切换"
        ModelChangeStatus.Delivering -> "正在发送切换请求"
        ModelChangeStatus.SentAwaitEvidence -> "已发送 · 等待本会话报告新配置"
        ModelChangeStatus.AwaitConfirm -> "运行器正在等待确认"
        ModelChangeStatus.Unknown -> "切换结果未确认 · 点击查看"
        ModelChangeStatus.Applied -> "切换已确认"
        else -> ""
    }
    NativeOverlay(menu)
    LaunchedEffect(menu, taskKey) {
        if (!menu) return@LaunchedEffect
        loading = true; loadError = ""; models = emptyList()
        try {
            val configured = ConfiguredModels.load(conn.ssh, session.cwd, currentModel, session)
            models = configured.models
            mappedCurrent = configured.resolvedCurrent
            chosen = chosen.takeIf { it in models } ?: configured.resolvedCurrent?.takeIf { it in models }.orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = "暂时无法获取完整模型列表，可稍后重新打开。" }
        finally { loading = false }
    }
    // 打开 / 取消 / 应用两种外观共用（Code 风格只换样子，行为不分叉）
    val pendingLabel = pending?.let { p -> "待切换 · " + listOfNotNull(p.model, p.effort?.let { e -> labels.getOrElse(levels.indexOf(e)) { e } }).joinToString(" · ") }
    val modelLabel = (mappedCurrent ?: currentModel.takeIf(ConfiguredModels::concrete)).orEmpty().ifBlank { "选择模型" }
    val effortLabel = labels.getOrElse(levels.indexOf(reportedEffort)) { reportedEffort.ifBlank { "思考强度" } }
    val openRequest: () -> Unit = { menu = true; chosen = currentModel; effort = reportedEffort }
    val openMenu: () -> Unit = {
        chosen = pending?.model ?: request?.takeIf { it.active }?.model ?: currentModel
        effort = pending?.effort ?: request?.takeIf { it.active }?.effort ?: reportedEffort
        menu = true
    }
    val canTerminal = request?.status in setOf(ModelChangeStatus.Unknown, ModelChangeStatus.AwaitConfirm)
    val canDismiss = request?.status in setOf(ModelChangeStatus.Pending, ModelChangeStatus.Unknown, ModelChangeStatus.SentAwaitEvidence)
    val dismissLabel = if (request?.status == ModelChangeStatus.Pending) "取消待切换选择" else "沿用运行器当前配置，继续队列"
    val dismissRequest: () -> Unit = {
        try { store?.dismiss(taskKey, request!!.revision); conn.modelChanges.remove(session.runtimeId); actionError = "" }
        catch (e: Exception) { actionError = e.message.orEmpty() }
    }
    val changedModel = chosen.takeIf { it in models && it != (mappedCurrent ?: currentModel) }
    val changedEffort = effort.takeIf { it.isNotBlank() && it != reportedEffort }
    val canApply = !loading && loadError.isBlank() && session.runtimeId.isNotBlank() && (changedModel != null || changedEffort != null)
    val apply: () -> Unit = {
        try {
            if (store == null || !store.propose(taskKey, session.runtimeId, changedModel, changedEffort))
                conn.modelChanges[session.runtimeId] = ConversationModelChange(changedModel, changedEffort)
            else conn.modelChanges.remove(session.runtimeId)
            actionError = ""; menu = false
        } catch (e: Exception) { actionError = e.message.orEmpty() }
    }
    val hint = "工作中选择会在空闲后应用；支持程度由模型决定。Claude 命令也可能保存为默认设置。"
    if (LocalThemeSpec.current.style == UiStyle.Code) {
        // 模型、强度两个 chip 打开同一个浮层（Yxi 选完「应用选择」才生效），右缘对齐强度 chip、向上展开
        var fromEffort by remember(taskKey) { mutableStateOf(false) }
        if (pendingLabel != null) CodeFooterNote(pendingLabel, Tokens.current.textMuted)
        else if (requestLabel.isNotBlank()) CodeFooterChip(requestLabel, { fromEffort = false; openRequest() }, held = menu && !fromEffort)
        Box {
            Row {
                CodeFooterChip(modelLabel, { fromEffort = false; openMenu() }, held = menu && !fromEffort)
                CodeFooterChip(effortLabel, { fromEffort = true; openMenu() }, held = menu && fromEffort)
            }
            if (menu) CodePopup({ menu = false }, side = PopupSide.Above, alignEnd = true, gap = 2.4.dp, width = 300.dp) {
                if (loading) CodeMenuNote("正在获取模型…")
                if (loadError.isNotBlank()) CodeMenuNote(loadError)
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    models.forEach { model -> CodeMenuItem(model, { chosen = model }, checked = chosen == model) }
                }
                if (!loading && models.isEmpty()) CodeMenuNote("请在线路配置中填写具体模型名称。")
                CodeMenuDivider()
                if (requestLabel.isNotBlank()) CodeMenuNote(requestLabel, Tokens.current.textPrimary)
                if (!request?.detail.isNullOrBlank()) CodeMenuNote(request.detail)
                if (canTerminal) CodeMenuItem("查看终端", { menu = false; onTerminal() })
                if (canDismiss) CodeMenuItem(dismissLabel, dismissRequest)
                if (actionError.isNotBlank()) CodeMenuNote(actionError, Tokens.current.danger)
                if (!store?.error.isNullOrBlank()) CodeMenuNote(store.error, Tokens.current.danger)
                Box(Modifier.padding(start = 8.8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    EffortControl(chosen.ifBlank { mappedCurrent ?: currentModel }, levels, effort.takeIf { it in levels }) { effort = it }
                }
                CodeMenuNote(hint)
                CodeMenuDivider()
                CodeMenuItem("应用选择", apply, enabled = canApply)
                CodeMenuItem("管理第三方线路", { menu = false; routes() })
            }
        }
        return
    }
    if (pendingLabel != null) Text(pendingLabel, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
    else if (requestLabel.isNotBlank()) TextButton(openRequest) { Text(requestLabel, style = MaterialTheme.typography.labelSmall) }
    Box {
        TextButton(openMenu) { Text("$modelLabel · $effortLabel ⌄") }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.width(300.dp)) {
            Text("选择模型", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
            if (loading) Text("正在获取模型…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (loadError.isNotBlank()) Text(loadError, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            models.forEach { model ->
                DropdownMenuItem(text = { Text(model) }, trailingIcon = { if (chosen == model) Text("✓") }, onClick = { chosen = model })
            }
            if (!loading && models.isEmpty()) Text("请在线路配置中填写具体模型名称。", Modifier.padding(16.dp), color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Column(Modifier.padding(16.dp, 8.dp)) {
                if (requestLabel.isNotBlank()) Text(requestLabel, style = MaterialTheme.typography.labelMedium)
                if (!request?.detail.isNullOrBlank()) Text(request.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (canTerminal) TextButton({ menu = false; onTerminal() }) { Text("查看终端") }
                if (canDismiss) TextButton(dismissRequest) { Text(dismissLabel) }
                if (actionError.isNotBlank()) Text(actionError, color = Tokens.current.danger, style = MaterialTheme.typography.bodySmall)
                if (!store?.error.isNullOrBlank()) Text(store.error, color = Tokens.current.danger, style = MaterialTheme.typography.bodySmall)
                EffortControl(chosen.ifBlank { mappedCurrent ?: currentModel }, levels, effort.takeIf { it in levels }) { effort = it }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                TextButton(apply, enabled = canApply) { Text("应用选择") }
                TextButton({ menu = false; routes() }) { Text("管理第三方线路") }
            }
        }
    }
}
