package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Registers only new, checked connections. Loading an index never resumes or replays a native task. */
internal class LocalClaudeTasks(private val queue: InstructionQueue, file: File,
    private val subscription: LocalClaudeSubscription = LocalClaudeSubscription()) : AutoCloseable {
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
                registry.save(record)
                controllers[record.key] = ClaudeTaskController(record.key, prepared.client, queue)
                record
            } catch (e: Exception) { prepared?.close(); throw e }
            finally { starting.compareAndSet(job, null); busy = false }
        }
    }
    override fun close() {
        disposed = true; starting.getAndSet(null)?.cancel()
        controllers.values.toList().forEach { it.close() }; controllers.clear()
    }
}
