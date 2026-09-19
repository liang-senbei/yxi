package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun CodexGoalStrip(controller: CodexTaskController) {
    val scope = rememberCoroutineScope()
    var expanded by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, controller.ready) { if (controller.ready) controller.refreshGoal() }
    val goal = controller.goal
    if (goal != null) {
        val label = when (goal.status) {
            "active" -> "进行中"; "paused" -> "已暂停"; "blocked" -> "待处理"
            "usageLimited" -> "用量受限"; "budgetLimited" -> "预算已达上限"; "complete" -> "已完成"
            else -> "状态未识别"
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("目标 · $label", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "详情") }
                    TextButton({ scope.launch { controller.refreshGoal() } }, enabled = controller.ready && !controller.goalLoading) { Text("刷新") }
                }
                Text(goal.objective, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                Text("服务器累计 ${goal.seconds.coerceAtLeast(0) / 60}分 ${goal.seconds.coerceAtLeast(0) % 60}秒 · ${goal.tokens} tokens" +
                    (goal.budget?.let { " / 预算 $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (controller.goalError.isNotBlank()) Text(controller.goalError, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
            }
        }
    } else if (controller.goalError.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(controller.goalError, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        TextButton({ scope.launch { controller.refreshGoal() } }, enabled = controller.ready && !controller.goalLoading) { Text("重试") }
    }
}
