package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable internal fun ScheduledTasksPane(state: AppState) {
    val store = state.scheduledTasks
    var editor by remember { mutableStateOf<ScheduledTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<String?>(null) }
    var confirmRun by remember { mutableStateOf<ScheduleRun?>(null) }
    fun action(block: () -> Unit) { runCatching(block).onSuccess { error = "" }.onFailure { error = it.message.orEmpty() } }
    LazyColumn(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row {
            Text("定时任务", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            Button({ creating = true }) { Text("新建任务") }
        } }
        item { Text("Yxi 运行时自动调度。电脑休眠或应用退出期间不会执行；恢复后每个计划最多补执行一次。服务器离线或 Agent 忙时跳过当次。", color = Tokens.current.textMuted) }
        if (store.error.isNotBlank() || error.isNotBlank()) item { Text(store.error.ifBlank { error }, color = Tokens.current.danger) }
        if (store.tasks.isEmpty()) item { OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) { Text("安排你的第一个任务", style = MaterialTheme.typography.titleMedium); Text("例如每天整理项目进度、每周检查待办，或在指定时间提醒 Agent 执行工作。") }
        } }
        items(store.tasks.sortedBy { it.next }, key = { it.id }) { task ->
            val active = store.runs.any { it.schedule == task.id && it.status == "执行中" }
            val last = store.runs.lastOrNull { it.schedule == task.id }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row { Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text(if (active) "执行中" else if (task.enabled) "已启用" else if (task.repeat == "一次" && last != null) last.status else "已暂停", color = Tokens.current.textMuted) }
                Text(task.target.label)
                Text("${task.repeat} · ${scheduleTime(task.next, task.zone)} · ${task.zone}", style = MaterialTheme.typography.bodySmall)
                Text(task.prompt, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.repeat != "一次" || last == null) TextButton({ action { if (task.enabled) store.pause(task.id) else store.resume(task.id, System.currentTimeMillis()) } }, enabled = !active) { Text(if (task.enabled) "暂停" else "恢复") }
                    TextButton({ editor = task }, enabled = !active) { Text("编辑") }
                    TextButton({ history = task.id }) { Text("执行记录") }
                    TextButton({ action { store.remove(task.id) } }, enabled = !active) { Text("删除") }
                }
            } }
        }
    }
    if (creating || editor != null) ScheduleEditor(state, editor) { creating = false; editor = null }
    history?.let { id -> WorkbenchDialog(onDismissRequest = { history = null }, title = { Text("执行记录") }, text = {
        LazyColumn(Modifier.widthIn(min = 440.dp, max = 680.dp).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val records = store.runs.filter { it.schedule == id }.asReversed()
            if (records.isEmpty()) item { Text("还没有执行记录") }
            items(records, key = { it.id }) { run -> Column {
                Text("${scheduleTime(run.due, ZoneId.systemDefault().id)} · ${run.status}")
                Text(run.detail, style = MaterialTheme.typography.bodySmall)
                if (run.status == "结果未确认") TextButton({ confirmRun = run }) { Text("已核对目标会话") }
            } }
        }
    }, confirmButton = { TextButton({ history = null }) { Text("关闭") } }) }
    confirmRun?.let { run -> AlertDialog(onDismissRequest = { confirmRun = null }, title = { Text("确认已核对执行结果") },
        text = { Text("请先查看目标会话或本地任务日志，确认是否执行过。本操作只标记已核对，不会重新发送；计划仍保持暂停，可手动恢复。") },
        confirmButton = { TextButton({ action { store.acknowledge(run.id) }; confirmRun = null }) { Text("确认已核对") } },
        dismissButton = { TextButton({ confirmRun = null }) { Text("取消") } }) }
}

private fun scheduleTime(time: Long, zone: String) = Instant.ofEpochMilli(time).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable private fun ScheduleEditor(state: AppState, original: ScheduledTask?, close: () -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var repeat by remember { mutableStateOf(original?.repeat ?: "每天") }
    val zone = original?.zone ?: ZoneId.systemDefault().id
    var time by remember { mutableStateOf(scheduleTime(original?.next ?: (System.currentTimeMillis() + 3600000), zone)) }
    var target by remember { mutableStateOf(original?.target ?: ScheduleTarget("local", "本地 · Codex")) }
    var directory by remember { mutableStateOf(original?.target?.directory?.ifBlank { null } ?: System.getProperty("user.home")) }
    var targetsOpen by remember { mutableStateOf(false) }
    var repeatOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val targets = listOf(ScheduleTarget("local", "本地 · Codex", "codex"), ScheduleTarget("local", "本地 · Claude Code", "claude")) +
        state.conns.filter { it.status == Conn.Status.Connected }.flatMap { conn ->
            conn.sessions.map { ScheduleTarget("terminal", "${conn.host.label} · ${it.short}", host = projectKey(conn.host, "/"), task = taskNavigationKey(conn.host, it), runtime = it.runtimeId) } +
                state.codexWorkspace.tasks(conn.host).map { ScheduleTarget("codex", "${conn.host.label} · ${it.title}", host = projectKey(conn.host, "/"), task = it.key) }
        }
    WorkbenchDialog(onDismissRequest = close, title = { Text(if (original == null) "新建定时任务" else "编辑定时任务") }, text = {
        Column(Modifier.widthIn(min = 420.dp, max = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
            Box { OutlinedButton({ targetsOpen = true }) { Text(target.label) }
                DropdownMenu(targetsOpen, { targetsOpen = false }) { targets.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { target = option; targetsOpen = false }) } }
            }
            if (target.kind == "local") OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), label = { Text("本地工作目录") }, singleLine = true)
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("到点执行的指令") }, minLines = 3, maxLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box { OutlinedButton({ repeatOpen = true }) { Text(repeat) }; DropdownMenu(repeatOpen, { repeatOpen = false }) {
                    listOf("一次", "每小时", "每天", "每周").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { repeat = option; repeatOpen = false }) }
                } }
                OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text("首次执行 yyyy-MM-dd HH:mm") }, singleLine = true)
            }
            Text("时区：$zone。服务器会话继续现有上下文；本地每次创建新任务，沿用运行器的权限设置。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { Button({ runCatching {
        val next = LocalDateTime.parse(time.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        require(next > System.currentTimeMillis()) { "首次执行时间必须晚于现在" }
        if (target.kind == "local") require(java.io.File(directory).isDirectory) { "本地工作目录不存在" }
        state.scheduledTasks.put(ScheduledTask(original?.id ?: UUID.randomUUID().toString(), name.trim(), prompt.trim(), target.copy(directory = directory), next, repeat, zone, original?.enabled ?: true))
    }.onSuccess { close() }.onFailure { error = it.message ?: "请检查任务信息" } }, enabled = name.isNotBlank() && prompt.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}
