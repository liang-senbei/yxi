package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Registers only new, checked connections. Loading an index never resumes or replays a native task. */
internal class LocalClaudeTasks(private val queue: InstructionQueue, file: File,
    private val subscription: LocalClaudeSubscription = LocalClaudeSubscription(),
    private val resumeConnection: suspend (LocalRuntimeInstallation, LocalCodexTaskRecord) -> LocalClaudeSubscription.Prepared = { runtime, record -> subscription.resume(runtime, record) },
    private val readHistory: (LocalCodexTaskRecord) -> List<app.yxi.agent.ChatItem> = LocalClaudeHistory::read) : AutoCloseable {
    var onNotification: (LocalCodexTaskRecord, String) -> Unit = { _, _ -> }
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, ClaudeTaskController>()
    private val operation = Mutex()
    private val starting = AtomicReference<Job?>()
    @Volatile private var disposed = false
    var busy by mutableStateOf(false); private set
    suspend fun create(runtime: LocalRuntimeInstallation, directory: String, title: String): LocalCodexTaskRecord = coroutineScope {
        operation.withLock {
            check(!disposed); registry.requireWritable()
            val label = title.trim().ifBlank { "新对话" }
            require(label.length <= 500 && label.none { it < ' ' })
            val requested = File(directory)
            require(requested.isAbsolute && requested.isDirectory)
            val cwd = requested.canonicalFile
            val job = currentCoroutineContext().job
            starting.set(job); busy = true
            var prepared: LocalClaudeSubscription.Prepared? = null
            try {
                prepared = subscription.prepare(runtime, cwd)
                check(!disposed); currentCoroutineContext().ensureActive()
                val sessionId = checkNotNull(prepared.client.requestedSessionId) { "Claude 启动未分配可追踪的会话身份" }
                val model = prepared.settings.optJSONObject("applied")?.optString("model").orEmpty()
                check(model.isNotBlank()) { "Claude 未返回当前模型" }
                val record = LocalCodexTaskRecord(sessionId, System.getProperty("user.name"), System.getProperty("os.name"),
                    File(runtime.home).canonicalPath, cwd.path, label, model, System.currentTimeMillis(), "claude", "official:claude")
                attach(record, prepared)
                record
            } catch (e: Exception) { prepared?.close(); throw e }
            finally { starting.compareAndSet(job, null); busy = false }
        }
    }
    suspend fun resume(runtime: LocalRuntimeInstallation, key: String): ClaudeTaskController = coroutineScope {
        operation.withLock {
            check(!disposed); registry.requireWritable()
            val record = registry.records.singleOrNull { it.key == key } ?: error("会话记录不存在")
            controllers[key]?.let { existing ->
                if (existing.ready) return@withLock existing
                check(!existing.busy && !existing.cancelling && !existing.changingModel) { "原连接仍在处理，请稍后恢复" }
            }
            check(queue.entries.none { it.taskKey == key && (it.status in setOf(InstructionStatus.Delivering, InstructionStatus.Unknown) || it.runtimeTurnState == RuntimeTurnState.InProgress) }) {
                "此会话还有未确认指令，请先核对投递结果"
            }
            val job = currentCoroutineContext().job
            starting.set(job); busy = true
            var prepared: LocalClaudeSubscription.Prepared? = null
            try {
                val history = withContext(Dispatchers.IO) { readHistory(record) }
                currentCoroutineContext().ensureActive()
                prepared = resumeConnection(runtime, record)
                check(!disposed); currentCoroutineContext().ensureActive()
                check(prepared.client.requestedSessionId == record.threadId) { "恢复会话身份不一致" }
                val model = prepared.settings.optJSONObject("applied")?.optString("model").orEmpty()
                check(model.isNotBlank()) { "Claude 未返回恢复后的模型" }
                controllers.remove(key)?.close()
                attach(record.copy(model = model), prepared, history)
            } catch (e: Exception) { prepared?.close(); throw e }
            finally { starting.compareAndSet(job, null); busy = false }
        }
    }
    private fun attach(record: LocalCodexTaskRecord, prepared: LocalClaudeSubscription.Prepared, history: List<app.yxi.agent.ChatItem> = emptyList()): ClaudeTaskController {
        val nativeModels = prepared.initialization.optJSONArray("models")
        val models = (0 until (nativeModels?.length() ?: 0)).mapNotNull { index ->
            val entry = nativeModels?.optJSONObject(index) ?: return@mapNotNull null
            entry.optString("resolvedModel").takeIf { it.isNotBlank() && it.length <= 500 && it.none { c -> c < ' ' } }
        }.distinct()
        registry.save(record)
        return ClaudeTaskController(record.key, prepared.client, queue,
            onNotification = { title -> onNotification(record, title) }, initialModel = record.model, availableModels = models,
            onModelChanged = { actual -> registry.save(record.copy(model = actual)) }, history = history.toList()).also { controllers[record.key] = it }
    }
    override fun close() {
        disposed = true; starting.getAndSet(null)?.cancel()
        controllers.values.toList().forEach { it.close() }; controllers.clear()
    }
}
