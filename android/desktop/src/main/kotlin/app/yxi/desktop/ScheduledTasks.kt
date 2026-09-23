package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.*
import java.util.UUID

internal data class ScheduleTarget(val kind: String, val label: String, val engine: String = "codex", val directory: String = "",
    val host: String = "", val task: String = "", val runtime: String = "")
internal data class ScheduledTask(val id: String, val name: String, val prompt: String, val target: ScheduleTarget,
    val next: Long, val repeat: String, val zone: String, val enabled: Boolean = true)
internal data class ScheduleRun(val id: String, val schedule: String, val due: Long, val status: String, val detail: String = "")

internal class ScheduledTasks(file: File) {
    var tasks by mutableStateOf<List<ScheduledTask>>(emptyList()); private set
    var runs by mutableStateOf<List<ScheduleRun>>(emptyList()); private set
    var error by mutableStateOf(""); private set
    private var writable = true
    private val disk = DurableFile(file) { decode(it) }
    private val review = File(file.parentFile, file.name + ".needs-review")
    init {
        try {
            disk.read()?.let { val pair = decode(it); tasks = pair.first; runs = pair.second }
            if (disk.recovered) DurableFile.replace(review, "Reconcile scheduled executions before resuming")
            check(!review.exists()) { "定时任务记录恢复过备份，请核对执行记录后再恢复调度" }
            if (runs.any { it.status == "执行中" }) save(tasks.map { task ->
                if (runs.any { it.schedule == task.id && it.status == "执行中" }) task.copy(enabled = false) else task
            }, runs.map { if (it.status == "执行中") it.copy(status = "结果未确认", detail = "上次退出时未取得结果，已暂停此计划，请核对目标会话") else it })
        } catch (e: Exception) { writable = false; error = e.message ?: "定时任务记录读取失败" }
    }
    private fun save(nextTasks: List<ScheduledTask>, nextRuns: List<ScheduleRun> = runs) {
        check(writable) { error }
        try { disk.write(encode(nextTasks, nextRuns)); tasks = nextTasks; runs = nextRuns; error = "" }
        catch (e: Exception) { writable = false; error = "定时任务未保存，已停止调度：${e.message}"; throw e }
    }
    fun put(task: ScheduledTask) {
        require(task.name.isNotBlank() && task.prompt.isNotBlank())
        require(task.repeat in setOf("一次", "每小时", "每天", "每周")); ZoneId.of(task.zone)
        require(task.target.kind in setOf("local", "terminal", "codex"))
        check(runs.none { it.schedule == task.id && it.status == "执行中" }) { "请等待本次执行结束" }
        check(!task.enabled || runs.none { it.schedule == task.id && it.status == "结果未确认" }) { "请先核对未确认执行，再启用计划" }
        save(tasks.filterNot { it.id == task.id } + task)
    }
    fun pause(id: String) = save(tasks.map { if (it.id == id) it.copy(enabled = false) else it })
    fun resume(id: String, now: Long) {
        check(runs.none { it.schedule == id && it.status in setOf("执行中", "结果未确认") }) { "存在未确认执行，请先查看记录与目标会话" }
        save(tasks.map { if (it.id == id) it.copy(enabled = true, next = maxOf(it.next, now + 60000)) else it })
    }
    fun remove(id: String) {
        check(runs.none { it.schedule == id && it.status in setOf("执行中", "结果未确认") }) { "此计划还有未确认执行" }
        save(tasks.filterNot { it.id == id })
    }
    fun acknowledge(id: String) = save(tasks, runs.map { if (it.id == id && it.status == "结果未确认") it.copy(status = "已人工核对", detail = "用户已核对目标会话；未重新执行") else it })
    fun claim(now: Long): Pair<ScheduledTask, ScheduleRun>? {
        if (!writable) return null
        val task = tasks.filter { it.enabled && it.next <= now && runs.none { r -> r.schedule == it.id && r.status == "执行中" } }.minByOrNull { it.next } ?: return null
        val run = ScheduleRun(UUID.randomUUID().toString(), task.id, task.next, "执行中")
        val next = if (task.repeat == "一次") task.copy(enabled = false) else task.copy(next = nextTime(task, now))
        // Commit receipt and advance before any side effect. Crash means unknown, never automatic replay.
        save(tasks.map { if (it.id == task.id) next else it }, runs + run)
        return task to run
    }
    fun finish(id: String, status: String, detail: String) {
        val run = runs.single { it.id == id }; check(run.status == "执行中")
        save(tasks.map { if (it.id == run.schedule && status in setOf("失败", "结果未确认")) it.copy(enabled = false) else it },
            runs.map { if (it.id == id) it.copy(status = status, detail = detail.take(4000)) else it })
    }
    companion object {
        fun nextTime(task: ScheduledTask, now: Long): Long {
            if (task.repeat == "每小时") return task.next + ((now - task.next).coerceAtLeast(0) / 3600000 + 1) * 3600000
            val zone = ZoneId.of(task.zone); val start = Instant.ofEpochMilli(task.next).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            var date = maxOf(start.toLocalDate(), today)
            if (task.repeat == "每周") while (date.dayOfWeek != start.dayOfWeek) date = date.plusDays(1)
            var next = date.atTime(start.toLocalTime()).atZone(zone)
            if (next.toInstant().toEpochMilli() <= now) next = if (task.repeat == "每周") next.plusWeeks(1) else next.plusDays(1)
            return next.toInstant().toEpochMilli()
        }
        private fun encode(tasks: List<ScheduledTask>, runs: List<ScheduleRun>) = JSONObject().put("version", 1)
            .put("tasks", JSONArray(tasks.map { t -> JSONObject().put("id", t.id).put("name", t.name).put("prompt", t.prompt).put("next", t.next)
                .put("repeat", t.repeat).put("zone", t.zone).put("enabled", t.enabled).put("target", JSONObject().put("kind", t.target.kind)
                    .put("label", t.target.label).put("engine", t.target.engine).put("directory", t.target.directory).put("host", t.target.host).put("task", t.target.task).put("runtime", t.target.runtime)) }))
            .put("runs", JSONArray(runs.map { JSONObject().put("id", it.id).put("schedule", it.schedule).put("due", it.due).put("status", it.status).put("detail", it.detail) })).toString()
        private fun decode(raw: String): Pair<List<ScheduledTask>, List<ScheduleRun>> {
            val json = JSONObject(raw); require(json.getInt("version") == 1)
            val ts = json.getJSONArray("tasks"); val rs = json.getJSONArray("runs")
            val tasks = (0 until ts.length()).map { val t = ts.getJSONObject(it); val target = t.getJSONObject("target")
                ScheduledTask(t.getString("id"), t.getString("name"), t.getString("prompt"), ScheduleTarget(target.getString("kind"), target.getString("label"), target.getString("engine"), target.getString("directory"), target.getString("host"), target.getString("task"), target.getString("runtime")),
                    t.getLong("next"), t.getString("repeat"), t.getString("zone"), t.getBoolean("enabled")).also { require(it.repeat in setOf("一次", "每小时", "每天", "每周")); ZoneId.of(it.zone) }
            }
            require(tasks.map { it.id }.distinct().size == tasks.size)
            return tasks to (0 until rs.length()).map { val r = rs.getJSONObject(it); ScheduleRun(r.getString("id"), r.getString("schedule"), r.getLong("due"), r.getString("status"), r.getString("detail")) }
        }
    }
}

internal class ScheduleDispatcher(private val state: AppState) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    fun start() { scope.launch { while (isActive) {
        runCatching { state.scheduledTasks.claim(System.currentTimeMillis()) }.getOrNull()?.let { (task, run) ->
            launch {
                try { execute(task, run) }
                catch (e: Exception) { runCatching { state.scheduledTasks.finish(run.id, "结果未确认", e.message ?: "请核对目标任务") } }
            }
        }
        delay(1000)
    } } }
    private suspend fun execute(task: ScheduledTask, run: ScheduleRun) {
        val target = task.target
        if (target.kind == "local") {
            val job = state.localAgents.start(target.engine, target.directory, task.prompt)
            while (job.running) delay(500)
            state.scheduledTasks.finish(run.id, if (job.status == "已完成") "已完成" else if (job.status.contains("未确认")) "结果未确认" else "失败", "本地任务 ${job.id} · ${job.status}\n${job.output.takeLast(2000)}")
            return
        }
        val conn = state.conns.firstOrNull { projectKey(it.host, "/") == target.host && it.status == Conn.Status.Connected }
        if (conn == null) { state.scheduledTasks.finish(run.id, "跳过", "服务器未连接，下个周期再检查；本次不积压补发"); return }
        if (target.kind == "terminal") {
            val session = conn.sessions.firstOrNull { taskNavigationKey(conn.host, it) == target.task && it.runtimeId == target.runtime }
            if (session == null) { state.scheduledTasks.finish(run.id, "失败", "原会话已变化，请编辑计划重新选择目标"); return }
            if (session.state != app.yxi.agent.SessionState.Idle) { state.scheduledTasks.finish(run.id, "跳过", "目标会话忙或需要用户处理"); return }
            val item = state.instructions.enqueue(target.task, task.prompt, id = run.id)
            try { deliverInstruction(conn, session, state.instructions, item) } catch (_: Exception) { }
        } else {
            val record = state.codexWorkspace.tasks(conn.host).firstOrNull { it.key == target.task }
            if (record == null) { state.scheduledTasks.finish(run.id, "失败", "原 Codex 会话不存在"); return }
            val controller = state.codexWorkspace.controllers[record.key]
            if (controller == null || !controller.ready) { state.scheduledTasks.finish(run.id, "跳过", "请先打开目标 Codex 会话以建立运行器连接"); return }
            if (controller.activeTurnId != null || controller.sending || controller.pendingRequests.isNotEmpty()) { state.scheduledTasks.finish(run.id, "跳过", "目标会话正在运行或等待批准"); return }
            state.instructions.enqueue(target.task, task.prompt, id = run.id)
            controller.sendNext(run.id)
            while (state.instructions.entries.single { it.id == run.id }.runtimeTurnState == RuntimeTurnState.InProgress) delay(500)
        }
        val item = state.instructions.entries.single { it.id == run.id }
        if (item.runtimeTurnState == RuntimeTurnState.Completed) { state.scheduledTasks.finish(run.id, "已完成", "指令 ${run.id} · 运行器已确认本轮完成"); return }
        if (item.runtimeTurnState in setOf(RuntimeTurnState.Failed, RuntimeTurnState.Interrupted)) { state.scheduledTasks.finish(run.id, "失败", "指令 ${run.id} · ${item.runtimeCompletion}"); return }
        val delivered = item.status in setOf(InstructionStatus.Sent, InstructionStatus.Accepted)
        if (item.status == InstructionStatus.Local) state.instructions.cancel(item.id, item.revision)
        state.scheduledTasks.finish(run.id, if (delivered) "已投递" else if (item.status == InstructionStatus.Local) "跳过" else "结果未确认", "指令 ${run.id} · ${item.detail}；已投递不代表 Agent 已完成")
    }
    override fun close() { scope.cancel() }
}
