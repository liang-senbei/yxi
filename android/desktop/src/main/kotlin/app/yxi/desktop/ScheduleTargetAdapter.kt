package app.yxi.desktop

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

internal data class ScheduleTargetResult(val status: String, val detail: String)

/** An existing connection only: resolving or scheduling never launches or resumes a runtime. */
internal class ScheduleTargetAdapter(
    private val queue: InstructionQueue,
    private val resolve: (ScheduleTarget) -> Connection?,
    private val pollMillis: Long = 250,
) {
    internal class Connection(val available: () -> Boolean, val awaitingApproval: () -> Boolean, val dispatch: (String) -> Job)

    suspend fun execute(task: ScheduledTask, run: ScheduleRun, progress: (String) -> Unit): ScheduleTargetResult {
        val target = task.target
        require(target.kind == "local-session" && target.task.isNotBlank())
        val existing = queue.entries.firstOrNull { it.id == run.id }
        if (existing != null) {
            check(existing.taskKey == target.task) { "定时回执目标不一致，请核对记录" }
            return result(existing, false) // Never replay a previously claimed delivery.
        }
        val connection = resolve(target) ?: return ScheduleTargetResult("跳过", "目标会话尚未连接，请打开原会话后恢复连接；本次不补发")
        if (!connection.available()) return ScheduleTargetResult("跳过", "目标会话正在运行、切换设置或等待批准；本次不补发")
        if (queue.entries.any { it.taskKey == target.task && (it.status in setOf(InstructionStatus.Local, InstructionStatus.Delivering, InstructionStatus.Unknown) || it.runtimeTurnState == RuntimeTurnState.InProgress) })
            return ScheduleTargetResult("跳过", "目标会话有待处理指令，请先核对队列；本次不插队")
        require(task.prompt.isNotBlank() && task.prompt.length <= 100_000)
        queue.enqueue(target.task, task.prompt, id = run.id)
        val operation = connection.dispatch(run.id)
        var lastProgress = ""
        while (operation.isActive) {
            val detail = if (connection.awaitingApproval()) "等待用户批准 · 请打开目标会话处理；不会自动批准或重复投递" else "正在等待运行器的本轮完成回执"
            if (lastProgress != detail) { progress(detail); lastProgress = detail }
            delay(pollMillis)
        }
        return result(queue.entries.single { it.id == run.id }, true)
    }
    private fun result(item: QueuedInstruction, cancelUnsent: Boolean): ScheduleTargetResult {
        val detail = "指令 ${item.id} · "
        return when {
            item.runtimeTurnState == RuntimeTurnState.Completed -> ScheduleTargetResult("已完成", detail + "运行器已确认本轮完成")
            item.runtimeTurnState == RuntimeTurnState.Failed -> ScheduleTargetResult("失败", detail + "运行器已确认本轮失败；请打开目标会话查看详情")
            item.runtimeTurnState == RuntimeTurnState.Interrupted -> ScheduleTargetResult("失败", detail + "本轮已停止；请打开目标会话查看详情")
            item.status == InstructionStatus.Local && cancelUnsent -> {
                queue.cancel(item.id, item.revision)
                ScheduleTargetResult("跳过", detail + "未投递，已撤回本次定时指令；没有发送其他指令")
            }
            item.status == InstructionStatus.Cancelled -> ScheduleTargetResult("跳过", detail + "本次指令已撤回")
            else -> ScheduleTargetResult("结果未确认", detail + "尚无本轮完成回执，请核对目标会话；不会自动重发")
        }
    }
    companion object {
        fun connectedTargets(state: AppState): List<ScheduleTarget> =
            state.localClaudeTasks.registry.records.filter { state.localClaudeTasks.controllers[it.key]?.ready == true }.map(::target) +
                state.localAcpTasks.registry.records.filter { state.localAcpTasks.controllers[it.key]?.ready == true }.map(::target)
        private fun target(record: LocalCodexTaskRecord) = ScheduleTarget("local-session", "本地现有会话 · ${record.title} · ${record.engine}",
            engine = record.engine, directory = record.directory, task = record.key)
        fun forState(state: AppState) = ScheduleTargetAdapter(state.instructions, resolve = { target ->
            if (target.engine == "claude") {
                val record = state.localClaudeTasks.registry.records.singleOrNull { it.key == target.task && it.engine == target.engine }
                record?.let { state.localClaudeTasks.controllers[it.key] }?.let { controller ->
                    Connection({ controller.ready && !controller.busy && !controller.cancelling && !controller.changingModel && controller.pendingApprovals.isEmpty() },
                        { controller.pendingApprovals.isNotEmpty() }, controller::dispatchScheduled)
                }
            } else {
                val record = state.localAcpTasks.registry.records.singleOrNull { it.key == target.task && it.engine == target.engine }
                record?.let { state.localAcpTasks.controllers[it.key] }?.let { controller ->
                    Connection({ controller.ready && !controller.busy && !controller.cancelling && !controller.changingMode && controller.pendingApprovals.isEmpty() },
                        { controller.pendingApprovals.isNotEmpty() }, controller::dispatchScheduled)
                }
            }
        })
    }
}
