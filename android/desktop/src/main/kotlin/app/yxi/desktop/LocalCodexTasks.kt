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
    val engine: String = "codex", val provider: String = "openai", val hostKey: String = "", val effort: String? = null) {
    val key get() = (if (hostKey.isBlank()) "local-$engine:" else "remote-$engine:$hostKey:") + MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(user, platform, runtimeHome, threadId))
        .toString().toByteArray()).joinToString("") { "%02x".format(it) }
    fun json() = JSONObject().put("threadId", threadId).put("user", user).put("platform", platform).put("runtimeHome", runtimeHome)
        .put("directory", directory).put("title", title).put("model", model).put("createdAt", createdAt).put("engine", engine).put("provider", provider).put("hostKey", hostKey).put("effort", effort ?: JSONObject.NULL)
}

/** 索引允许的思考强度；写回前也按它校验，否则下次启动整个索引会被判损坏。 */
private val LOCAL_CODEX_EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

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
            require(!r.has("effort") || r.isNull("effort") || r.opt("effort") is String)
            LocalCodexTaskRecord(r.getString("threadId"), r.getString("user"), r.getString("platform"), r.getString("runtimeHome"),
                r.getString("directory"), r.getString("title"), r.getString("model"), r.getLong("createdAt"),
                r.optString("engine", "codex"), r.optString("provider", "openai"), r.optString("hostKey"), (r.opt("effort") as? String)).also { record ->
                require(record.effort == null || record.effort in LOCAL_CODEX_EFFORTS)
                require(record.engine in setOf("codex", "claude", "opencode", "gemini", "grok", "hermes") && record.provider.isNotBlank())
                require(record.threadId.isNotBlank() && record.user.isNotBlank() && record.platform.isNotBlank())
                require(record.runtimeHome.isNotBlank() && record.directory.isNotBlank() && record.model.isNotBlank() && record.createdAt > 0)
                require(listOf(record.threadId, record.runtimeHome, record.directory, record.model).all { value -> value.none { it < ' ' } })
            }
        } }.also { require(it.map { r -> r.key }.distinct().size == it.size) }
    }
}

/** Yxi 在本机建的会话归 Yxi 持有：create 新建，open 用 thread/resume 接回（只由 Code 风格触发）。原生历史不经这里，仍只读。 */
internal class LocalCodexTasks(private val queue: InstructionQueue, file: File, private val sharedMcp: SharedMcpRegistry? = null,
    private val onNotification: (LocalCodexTaskRecord, String) -> Unit = { _, _ -> }) : AutoCloseable {
    val registry = LocalCodexTaskRegistry(file)
    val images = LocalCodexImages(File(file.absoluteFile.parentFile, "codex-images"))
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
    internal var autoQueue: (String) -> Boolean = QueuePreferences::enabled
    internal var codeStyle: () -> Boolean = { UiStyle.from(Store.pref(UiStyle.PREF, UiStyle.Classic.key)) == UiStyle.Code }
    /**
     * 新建会话：[requestedModel] 为空时取运行器这次报的默认模型（Code 首页回车直接建），不为空就必须在当前官方列表里（经典对话框选的）。
     * [autoRun] 为真时按会话的排队偏好自动逐轮（同接回），否则手动发送（经典）。
     */
    suspend fun create(runtime: LocalRuntimeInstallation, directory: String, title: String, requestedModel: String?, autoRun: Boolean = false): LocalCodexTaskRecord = operation.withLock {
        check(!disposed) { "本地会话管理器已关闭" }
        registry.requireWritable()
        check(sharedMcp?.problem.isNullOrBlank()) { sharedMcp?.problem.orEmpty() }
        check(recoveryThreadId.isBlank()) { "上次创建结果待核对，原生会话：$recoveryThreadId" }
        require(File(directory).isAbsolute && File(directory).isDirectory) { "请选择存在的本机绝对目录" }
        val cwd = File(directory).canonicalPath
        require(cwd.none { it < ' ' })
        busy = true
        var client: CodexAppServer? = null
        try {
            val resources = sharedMcp?.forHost("@local")?.filter { "codex" in it.desiredRunners }.orEmpty()
            val connection = if (resources.isEmpty()) connect(runtime) else LocalCodexProfiles.connectOfficialWithSharedMcp(runtime, resources, cwd)
            client = connection
            startingClient.set(connection)
            check(!disposed) { "应用已关闭，未继续创建" }
            val available = LocalOfficialModels.load { connection.listModels(it) }
            val model = requestedModel ?: (available.firstOrNull { it.isDefault } ?: available.first()).id
            require(available.any { it.id == model }) { "所选模型不在当前官方模型列表中，请刷新后重新选择" }
            val pending = JSONObject().put("pending", true).put("runtimeHome", runtime.home).put("directory", cwd).put("model", model)
            creationJournal.write(pending.toString())
            recoveryThreadId = "创建请求结果尚未确认"
            val result = connection.request("thread/start", JSONObject().put("cwd", cwd).put("modelProvider", "openai").put("model", model)).getJSONObject("result")
            val thread = result.getJSONObject("thread")
            val id = thread.getString("id"); recoveryThreadId = id
            creationJournal.write(pending.put("threadId", id).toString())
            check(sharedMcp?.forHost("@local")?.filter { "codex" in it.desiredRunners }.orEmpty() == resources) { "共享插件配置在创建期间发生变化，请核对已创建会话" }
            check(result.optString("modelProvider") == "openai" && result.optString("model") == model) { "运行器返回的实际线路或模型不匹配，未启用发送" }
            check(File(thread.getString("cwd")).canonicalPath == cwd) { "运行器返回了不同工作目录" }
            val record = LocalCodexTaskRecord(id, System.getProperty("user.name"), System.getProperty("os.name"),
                File(runtime.home).canonicalPath, cwd, title.trim().ifBlank { File(cwd).name.ifBlank { "新对话" } }, model, System.currentTimeMillis())
            registry.save(record)
            val controller = newController(record, connection)
            try {
                controller.reconcile(thread); controller.recordSessionConfiguration(result)
                check(!disposed) { "应用已关闭，请核对已创建的原生会话" }
                creationJournal.write(JSONObject().put("pending", false).toString())
            }
            catch (e: Exception) { controller.close(); throw e }
            controllers[record.key] = controller
            if (autoRun) controller.setAutoDispatch(autoQueue(record.key))
            recoveryThreadId = ""
            record
        } catch (e: Exception) { client?.close(); throw e }
        finally { startingClient.compareAndSet(client, null); busy = false }
    }

    /** 接回 Yxi 建的会话：按保存的线路、模型和强度 thread/resume，核对编号、线路、模型和目录后才启用发送。同服务器 open，不检查未确认指令。 */
    suspend fun open(runtime: LocalRuntimeInstallation, key: String, autoRun: Boolean = true): CodexTaskController = operation.withLock {
        check(!disposed) { "本地会话管理器已关闭" }
        val record = registry.records.singleOrNull { it.key == key } ?: error("会话记录不存在")
        controllers[key]?.takeIf { it.ready }?.let {
            if (!autoRun) it.setAutoDispatch(false)
            return@withLock it
        }
        registry.requireWritable()
        check(sharedMcp?.problem.isNullOrBlank()) { sharedMcp?.problem.orEmpty() }
        check(record.engine == "codex" && record.provider == "openai" && record.hostKey.isBlank()) { "不是本机官方订阅的 Codex 会话" }
        check(record.user == System.getProperty("user.name") && record.platform == System.getProperty("os.name")) { "会话不是在此电脑和用户下创建的" }
        check(runtime.engine == "codex" && runtime.ready) { "Codex 运行器不可用：" + runtime.problem.ifBlank { "未检测到版本" } }
        check(File(runtime.home).canonicalPath == record.runtimeHome) { "会话属于另一个 Codex 数据目录（${record.runtimeHome}），请在运行器里选回原来的那个" }
        require(File(record.directory).isDirectory) { "原工作目录在此电脑不可用：${record.directory}" }
        controllers.remove(key)?.close()
        busy = true
        var client: CodexAppServer? = null
        try {
            val resources = sharedMcp?.forHost("@local")?.filter { "codex" in it.desiredRunners }.orEmpty()
            val connection = if (resources.isEmpty()) connect(runtime) else LocalCodexProfiles.connectOfficialWithSharedMcp(runtime, resources, record.directory)
            client = connection
            startingClient.set(connection)
            check(!disposed) { "应用已关闭，未继续接回" }
            val result = try { connection.resumeThread(record.threadId, CodexResumeOverrides("openai", record.model, record.effort)).getJSONObject("result") }
                catch (e: CodexAppServer.RpcFailure) {
                    if (e.message?.contains("no rollout found", ignoreCase = true) != true) throw e
                    throw IllegalStateException("本机没有找到此会话的历史文件。尚未发送过内容的新会话关闭后可能无法恢复；" +
                        "也请确认用的是原来的 Codex 数据目录和账号。会话编号：${record.threadId}。", e)
                }
            val thread = result.getJSONObject("thread")
            check(thread.getString("id") == record.threadId) { "恢复响应不属于原会话" }
            check(result.optString("modelProvider") == "openai") { "运行器恢复的线路不是官方订阅，未启用发送" }
            check(result.optString("model") == record.model) { "运行器未恢复保存的模型（${record.model}）" }
            check(thread.optString("cwd").let { it.isNotBlank() && File(it).canonicalPath == record.directory }) { "运行器返回了不同工作目录" }
            val controller = newController(record, connection)
            try { controller.reconcile(); controller.recordSessionConfiguration(result); check(!disposed) { "应用已关闭" } }
            catch (e: Exception) { controller.close(); throw e }
            controllers[key] = controller
            controller.setAutoDispatch(autoRun && autoQueue(key))
            controller
        } catch (e: Exception) { client?.close(); throw e }
        finally { startingClient.compareAndSet(client, null); busy = false }
    }

    /** 选模型和强度写回记录，下次接回按它核对；通知只在 Code 风格下发，经典保持原样。附件只认 images 的快照引用。 */
    private fun newController(record: LocalCodexTaskRecord, connection: CodexAppServer): CodexTaskController {
        connection.attachmentPath = images::resolve
        lateinit var controller: CodexTaskController
        controller = CodexTaskController(record.key, record.threadId, connection, queue, initialModel = record.model, initialEffort = record.effort,
            onModelSelection = { model, effort ->
                check(controllers[record.key] === controller) { "会话连接已变化，请重新打开模型菜单" }
                require(effort == null || effort in LOCAL_CODEX_EFFORTS) { "此思考强度暂不支持保存" }
                val current = registry.records.single { it.key == record.key }
                val next = model?.takeIf { it.isNotBlank() } ?: current.model
                require(next.none { it < ' ' }) { "模型名无效" }
                registry.save(current.copy(model = next, effort = effort))
            }) { title -> if (codeStyle()) onNotification(record, title) }
        return controller
    }
    override fun close() { disposed = true; startingClient.getAndSet(null)?.close(); controllers.values.toList().forEach { it.close() }; controllers.clear() }
}
