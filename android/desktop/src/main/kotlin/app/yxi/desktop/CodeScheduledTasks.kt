package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Code 风格的定时任务页（M4l）：参照端没有这一页的实测，照自定义页（规格表 §3.10）的写法——960 宽的列居中，标题 22 粗，
 * 右上黑底「新建任务」；每个计划一张卡片（面板底、细线框、圆角 10、内边距 16）：名字 15 粗 + 状态（次色），目标、时间（弱色），指令最多 3 行，
 * 最下一排次按钮：立即运行 / 暂停或恢复 / 编辑 / 执行记录 / 打开目标会话 / 删除。数据、规则和对话框都是经典那套（[ScheduledTasksPane]）。
 */
@Composable
internal fun CodeScheduledTasksList(state: AppState, error: String, onCreate: () -> Unit, onEdit: (ScheduledTask) -> Unit,
    onHistory: (String) -> Unit, action: (() -> Unit) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val store = state.scheduledTasks
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 960.dp).fillMaxWidth(), contentPadding = PaddingValues(40.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("定时任务", Modifier.weight(1f), color = t.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        CodeApprovalButton(CodeApprovalAction("新建任务", primary = true, onClick = onCreate))
                    }
                    Text("Yxi 运行时自动调度。电脑休眠或应用退出期间不会执行；恢复后每个计划最多补执行一次。服务器离线或 Agent 忙时跳过当次。",
                        color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    val problem = store.error.ifBlank { error }
                    if (problem.isNotBlank()) Text(problem, color = t.danger, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            if (store.tasks.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(hairline(), p.popupRing, RoundedCornerShape(10.dp)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("安排你的第一个任务", color = t.textPrimary, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("例如每天整理项目进度、每周检查待办，或在指定时间提醒 Agent 执行工作。", color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            items(store.tasks.sortedBy { it.next }, key = { it.id }) { task ->
                val active = store.runs.any { it.schedule == task.id && it.status == "执行中" }
                val unconfirmed = store.runs.any { it.schedule == task.id && it.status == "结果未确认" }
                val queued = task.id in store.requested
                val last = store.runs.lastOrNull { it.schedule == task.id }
                val status = when {
                    active -> "执行中"; queued -> "即将运行"; task.enabled -> "已启用"
                    task.repeat == "一次" && last != null -> last.status; else -> "已暂停"
                }
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.fillMaxWidth().background(p.popup, shape).border(hairline(), p.popupRing, shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.name, Modifier.weight(1f), color = t.textPrimary, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, color = if (status in setOf("失败", "结果未确认")) t.danger else t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    Text(task.target.label, color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${task.repeat} · ${Instant.ofEpochMilli(task.next).atZone(ZoneId.of(task.zone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} · ${task.zone}",
                        color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    if (active && last?.detail?.isNotBlank() == true) Text(last.detail, color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(task.prompt, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodeApprovalButton(CodeApprovalAction("立即运行", enabled = !active && !queued && !unconfirmed) { action { store.requestNow(task.id) } })
                        if (task.repeat != "一次" || last == null) CodeApprovalButton(CodeApprovalAction(if (task.enabled) "暂停" else "恢复", enabled = !active) {
                            action { if (task.enabled) store.pause(task.id) else store.resume(task.id, System.currentTimeMillis()) }
                        })
                        CodeApprovalButton(CodeApprovalAction("编辑", enabled = !active) { onEdit(task) })
                        CodeApprovalButton(CodeApprovalAction("执行记录") { onHistory(task.id) })
                        if (task.target.kind == "local-session") CodeApprovalButton(CodeApprovalAction("打开目标会话") {
                            action { check(state.openNotifiedTask(task.target.task)) { "目标会话不存在" } }
                        })
                        Spacer(Modifier.weight(1f))
                        CodeApprovalButton(CodeApprovalAction("删除", enabled = !active) { action { store.remove(task.id) } })
                    }
                }
            }
        }
    }
}
