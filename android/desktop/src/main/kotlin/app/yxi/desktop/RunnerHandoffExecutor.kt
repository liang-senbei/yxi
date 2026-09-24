package app.yxi.desktop

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Adapters must create a fresh native session and deliver exactly the recorded instruction ID. */
internal class RunnerHandoffExecutor(private val store: RunnerHandoffStore, private val queue: InstructionQueue,
    private val create: suspend (RunnerHandoffRecord) -> String,
    private val deliver: suspend (String, String) -> Unit) {
    private val operation = Mutex()
    suspend fun execute(id: String, revision: Long): RunnerHandoffRecord = operation.withLock {
        var current = store.records.single { it.id == id }
        check(current.revision == revision && current.stage in setOf(RunnerHandoffStage.Draft, RunnerHandoffStage.Created)) { "交接状态需要核对，不能重复创建或发送" }
        try {
            if (current.stage == RunnerHandoffStage.Draft) {
                current = store.beginCreation(id, current.revision)
                val target = create(current)
                current = store.created(id, current.revision, target)
            }
            val target = checkNotNull(current.targetTaskKey)
            current = store.beginDelivery(id, current.revision, target)
            queue.enqueue(target, current.summary, id = current.deliveryId, sourceTask = current.sourceTaskKey)
            deliver(target, current.deliveryId)
            val receipt = queue.entries.single { it.id == current.deliveryId }
            check(receipt.taskKey == target && receipt.runtimeTurnState == RuntimeTurnState.Completed && receipt.status == InstructionStatus.Accepted) {
                "目标运行器尚未确认摘要轮次完成，请核对原会话与新会话"
            }
            current = store.completed(id, current.revision, current.deliveryId, "摘要轮次已完成；原会话保留")
            current
        } catch (error: Exception) {
            withContext(NonCancellable) {
                val latest = store.records.single { it.id == id }
                if (latest.stage in setOf(RunnerHandoffStage.Creating, RunnerHandoffStage.Delivering))
                    runCatching { store.unknown(id, latest.revision, "交接未取得完整确认，请核对目标会话；不会自动重试") }
            }
            throw error
        }
    }
}
