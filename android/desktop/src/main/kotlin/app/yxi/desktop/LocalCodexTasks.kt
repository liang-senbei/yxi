package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class LocalCodexTaskRecord(val threadId: String, val user: String, val platform: String,
    val runtimeHome: String, val directory: String, val title: String, val model: String, val createdAt: Long,
    val engine: String = "codex", val provider: String = "openai") {
    val key get() = "local-$engine:" + MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(user, platform, runtimeHome, threadId))
        .toString().toByteArray()).joinToString("") { "%02x".format(it) }
    fun json() = JSONObject().put("threadId", threadId).put("user", user).put("platform", platform).put("runtimeHome", runtimeHome)
        .put("directory", directory).put("title", title).put("model", model).put("createdAt", createdAt).put("engine", engine).put("provider", provider)
}

internal class LocalCodexTaskRegistry(file: File) {
    val records = mutableStateListOf<LocalCodexTaskRecord>()
    private val storage = DurableFile(file) { decode(it) }
    private val reviewMarker = File(file.parentFile, file.name + ".needs-review")
    var recoveryReviewRequired by mutableStateOf(false); private set
    var problem by mutableStateOf(""); private set
    init {
        try {
            storage.read()?.let { records.addAll(decode(it)) }
            if (storage.recovered) DurableFile.replace(reviewMarker, "Native task index recovered; review before new writes")
            recoveryReviewRequired = reviewMarker.exists()
            if (recoveryReviewRequired) problem = "本地任务索引已从备份恢复，请核对后再新建"
        }
        catch (_: Exception) { problem = "本地任务索引损坏，原文件已保留；请先恢复索引" }
    }
    fun requireWritable() { check(problem.isBlank()) { problem } }
    fun confirmRecoveryReviewed() {
        check(recoveryReviewRequired)
        check(reviewMarker.delete()) { "核对标记未能更新，尚未解除保护" }
        recoveryReviewRequired = false; problem = ""
    }
    fun save(record: LocalCodexTaskRecord) {
        requireWritable()
        val next = records.filterNot { it.key == record.key } + record
        storage.write(JSONArray(next.map { it.json() }).toString())
        records.clear(); records.addAll(next)
    }
    private fun decode(raw: String): List<LocalCodexTaskRecord> {
        require(raw.length <= 4 * 1024 * 1024)
        val rows = JSONArray(raw)
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let { r ->
            LocalCodexTaskRecord(r.getString("threadId"), r.getString("user"), r.getString("platform"), r.getString("runtimeHome"),
                r.getString("directory"), r.getString("title"), r.getString("model"), r.getLong("createdAt"),
                r.optString("engine", "codex"), r.optString("provider", "openai")).also { record ->
                require(record.engine in setOf("codex", "opencode") && record.provider.isNotBlank())
                require(record.threadId.isNotBlank() && record.user.isNotBlank() && record.platform.isNotBlank())
                require(record.runtimeHome.isNotBlank() && record.directory.isNotBlank() && record.model.isNotBlank() && record.createdAt > 0)
                require(listOf(record.threadId, record.runtimeHome, record.directory, record.model).all { value -> value.none { it < ' ' } })
            }
        } }.also { require(it.map { r -> r.key }.distinct().size == it.size) }
    }
}

/** Only newly created, currently owned local sessions are writable. Existing history stays read-only. */
internal class LocalCodexTasks(private val queue: InstructionQueue, file: File) : AutoCloseable {
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, CodexTaskController>()
    private val operation = Mutex()
    @Volatile private var disposed = false
    private val startingClient = java.util.concurrent.atomic.AtomicReference<CodexAppServer?>()
    var busy by mutableStateOf(false); private set
    var recoveryThreadId by mutableStateOf(""); private set
    private val creationJournal = DurableFile(File(file.parentFile, file.name + ".creation.json")) { raw ->
        require(JSONObject(raw).opt("pending") is Boolean)
    }
    init {
        try { creationJournal.read()?.let { raw ->
            val saved = JSONObject(raw)
            if (saved.getBoolean("pending")) recoveryThreadId = saved.optString("threadId").ifBlank { "上次创建未返回会话标识" }
        } } catch (_: Exception) { recoveryThreadId = "创建记录无法读取，请先核对原生历史" }
    }
    fun confirmCreationReviewed() {
        check(!busy)
        creationJournal.write(JSONObject().put("pending", false).toString())
        recoveryThreadId = ""
    }
    internal var connect: suspend (LocalRuntimeInstallation) -> CodexAppServer = LocalCodexProfiles::connectOfficial
    suspend fun create(runtime: LocalRuntimeInstallation, directory: String, title: String, model: String): LocalCodexTaskRecord = operation.withLock {
        check(!disposed) { "本地会话管理器已关闭" }
        registry.requireWritable()
        check(recoveryThreadId.isBlank()) { "上次创建结果待核对，原生会话：$recoveryThreadId" }
        require(File(directory).isAbsolute && File(directory).isDirectory) { "请选择存在的本机绝对目录" }
        val cwd = File(directory).canonicalPath
        require(cwd.none { it < ' ' })
        busy = true
        var client: CodexAppServer? = null
        try {
            val connection = connect(runtime); client = connection
            startingClient.set(connection)
            check(!disposed) { "应用已关闭，未继续创建" }
            val available = LocalOfficialModels.load { connection.listModels(it) }
            require(available.any { it.id == model }) { "所选模型不在当前官方模型列表中，请刷新后重新选择" }
            val pending = JSONObject().put("pending", true).put("runtimeHome", runtime.home).put("directory", cwd).put("model", model)
            creationJournal.write(pending.toString())
            recoveryThreadId = "创建请求结果尚未确认"
            val result = connection.request("thread/start", JSONObject().put("cwd", cwd).put("modelProvider", "openai").put("model", model)).getJSONObject("result")
            val thread = result.getJSONObject("thread")
            val id = thread.getString("id"); recoveryThreadId = id
            creationJournal.write(pending.put("threadId", id).toString())
            check(result.optString("modelProvider") == "openai" && result.optString("model") == model) { "运行器返回的实际线路或模型不匹配，未启用发送" }
            check(File(thread.getString("cwd")).canonicalPath == cwd) { "运行器返回了不同工作目录" }
            val record = LocalCodexTaskRecord(id, System.getProperty("user.name"), System.getProperty("os.name"),
                File(runtime.home).canonicalPath, cwd, title.trim().ifBlank { File(cwd).name.ifBlank { "新对话" } }, model, System.currentTimeMillis())
            registry.save(record)
            val controller = CodexTaskController(record.key, id, connection, queue, initialModel = model)
            try {
                controller.reconcile(thread); controller.recordSessionConfiguration(result)
                check(!disposed) { "应用已关闭，请核对已创建的原生会话" }
                creationJournal.write(JSONObject().put("pending", false).toString())
            }
            catch (e: Exception) { controller.close(); throw e }
            controllers[record.key] = controller
            recoveryThreadId = ""
            record
        } catch (e: Exception) { client?.close(); throw e }
        finally { startingClient.compareAndSet(client, null); busy = false }
    }
    override fun close() { disposed = true; startingClient.getAndSet(null)?.close(); controllers.values.toList().forEach { it.close() }; controllers.clear() }
}
