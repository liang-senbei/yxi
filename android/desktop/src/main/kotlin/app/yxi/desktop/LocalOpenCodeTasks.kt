package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

internal class LocalOpenCodeTasks(private val queue: InstructionQueue, file: File) : AutoCloseable {
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, OpenCodeTaskController>()
    private val servers = mutableMapOf<String, LocalOpenCodeServer>()
    private val operation = Mutex()
    @Volatile private var disposed = false
    private val starting = java.util.concurrent.atomic.AtomicReference<LocalOpenCodeServer?>()
    var busy by mutableStateOf(false); private set
    var recoverySessionId by mutableStateOf(""); private set
    private val journal = DurableFile(File(file.parentFile, file.name + ".creation.json")) { require(JSONObject(it).opt("pending") is Boolean) }
    init {
        try { journal.read()?.let { JSONObject(it) }?.takeIf { it.getBoolean("pending") }?.let {
            recoverySessionId = it.optString("sessionId").ifBlank { "上次创建结果尚未确认" }
        } } catch (_: Exception) { recoverySessionId = "创建记录损坏，请核对原生历史" }
    }
    fun confirmCreationReviewed() {
        check(!busy); journal.write(JSONObject().put("pending", false).toString()); recoverySessionId = ""
    }
    suspend fun models(runtime: LocalRuntimeInstallation, directory: String): List<OpenCodeModel> = operation.withLock {
        check(!disposed); busy = true
        try {
            LocalOpenCodeServer.start(runtime, File(directory)).use {
                starting.set(it); check(!disposed)
                it.client.availableModels()
            }
        } finally { starting.set(null); busy = false }
    }
    suspend fun create(runtime: LocalRuntimeInstallation, directory: String, title: String, model: OpenCodeModel): LocalCodexTaskRecord = operation.withLock {
        check(!disposed); registry.requireWritable(); check(recoverySessionId.isBlank()) { "上次创建结果待核对：$recoverySessionId" }
        require(File(directory).isAbsolute && File(directory).isDirectory)
        val cwd = File(directory).canonicalPath
        val label = title.trim().ifBlank { File(cwd).name.ifBlank { "新对话" } }
        require(label.length <= 500 && label.none { it < ' ' })
        busy = true
        var server: LocalOpenCodeServer? = null
        try {
            val connection = LocalOpenCodeServer.start(runtime, File(cwd)); server = connection; starting.set(connection)
            check(!disposed)
            check(connection.client.availableModels().any { it.providerId == model.providerId && it.modelId == model.modelId }) { "所选模型当前不可用" }
            val pending = JSONObject().put("pending", true).put("directory", cwd).put("runtimeHome", runtime.home)
            journal.write(pending.toString()); recoverySessionId = "创建请求结果尚未确认"
            val session = connection.client.create(label)
            val id = session.getString("id"); recoverySessionId = id
            journal.write(pending.put("sessionId", id).toString())
            check(session.getString("directory") == cwd) { "运行器返回不同工作目录，未启用发送" }
            val record = LocalCodexTaskRecord(id, System.getProperty("user.name"), System.getProperty("os.name"),
                File(runtime.home).canonicalPath, cwd, label, model.modelId, System.currentTimeMillis(), "opencode", model.providerId)
            registry.save(record)
            val controller = OpenCodeTaskController(record.key, id, cwd, model.providerId, model.modelId, connection.client, queue)
            controller.refresh(); check(!disposed)
            journal.write(JSONObject().put("pending", false).toString())
            controllers[record.key] = controller; servers[record.key] = connection; recoverySessionId = ""
            record
        } catch (e: Exception) { server?.close(); throw e }
        finally { starting.compareAndSet(server, null); busy = false }
    }
    override fun close() {
        disposed = true; starting.getAndSet(null)?.close(); servers.values.toList().forEach { it.close() }; servers.clear(); controllers.clear()
    }
}
