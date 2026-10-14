package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** UI dispatcher owner. Authentication is explicit; only sessions created by this owner are writable.
 * [open] reconnects them through session/load or session/resume (本机对齐 P3); [connect] is replaceable for the screenshot fixtures. */
internal class LocalAcpTasks(private val queue: InstructionQueue, file: File,
    private val onNotification: (LocalCodexTaskRecord, String) -> Unit = { _, _ -> },
    internal var connect: suspend (LocalRuntimeInstallation, File) -> AcpClient = LocalAcpTransport::connect) : AutoCloseable {
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, AcpTaskController>()
    private val operation = Mutex()
    private val prepared = AtomicReference<AcpClient?>()
    private val preparationGeneration = java.util.concurrent.atomic.AtomicLong()
    private val startingClient = AtomicReference<AcpClient?>()
    private var runtime: LocalRuntimeInstallation? = null
    private var directory = ""
    @Volatile private var disposed = false
    var busy by mutableStateOf(false); private set
    var initialization by mutableStateOf<JSONObject?>(null); private set
    var recoverySessionId by mutableStateOf(""); private set
    private val journal = DurableFile(File(file.parentFile, file.name + ".creation.json")) {
        require(JSONObject(it).opt("pending") is Boolean)
    }
    init {
        try { journal.read()?.let(::JSONObject)?.takeIf { it.getBoolean("pending") }?.let {
            recoverySessionId = it.optString("sessionId").ifBlank { "上次 ACP 创建结果未确认" }
        } } catch (_: Exception) { recoverySessionId = "ACP 创建记录损坏，请核对原生会话" }
    }
    fun confirmCreationReviewed() {
        check(!busy); journal.write(JSONObject().put("pending", false).toString()); recoverySessionId = ""
    }
    fun abandonPreparation() {
        preparationGeneration.incrementAndGet()
        prepared.getAndSet(null)?.close()
        runtime = null; initialization = null; directory = ""
    }
    suspend fun prepare(installation: LocalRuntimeInstallation, cwd: String): JSONObject = operation.withLock {
        check(!disposed && recoverySessionId.isBlank()); registry.requireWritable()
        AcpLaunch.arguments(installation.engine)
        val folder = File(cwd).canonicalFile
        require(File(cwd).isAbsolute && folder.isDirectory)
        busy = true
        val generation = preparationGeneration.incrementAndGet()
        prepared.getAndSet(null)?.close(); runtime = null; initialization = null
        var client: AcpClient? = null
        try {
            client = connect(installation, folder)
            check(!disposed && preparationGeneration.get() == generation) { "连接准备已取消" }
            val hello = requireNotNull(client.initialization)
            prepared.set(client); runtime = installation; directory = folder.path
            initialization = JSONObject(hello.toString())
            JSONObject(hello.toString())
        } catch (e: Exception) { client?.close(); throw e }
        finally { busy = false }
    }
    suspend fun authenticate(methodId: String) = operation.withLock {
        check(!disposed); busy = true
        try { checkNotNull(prepared.get()) { "请先选择并连接运行器" }.authenticate(methodId) }
        finally { busy = false }
    }
    suspend fun authenticateTerminal(methodId: String, interactive: suspend (AcpTerminalAuthPlan) -> Int) = operation.withLock {
        check(!disposed && recoverySessionId.isBlank())
        val installation = checkNotNull(runtime)
        val cwd = directory
        val generation = preparationGeneration.get()
        val methods = checkNotNull(initialization).getJSONArray("authMethods")
        val method = (0 until methods.length()).map { methods.getJSONObject(it) }.single { it.getString("id") == methodId }
        val plan = acpTerminalAuthPlan(installation, File(cwd), method)
        busy = true
        prepared.getAndSet(null)?.close()
        var renewed: AcpClient? = null
        try {
            val exitCode = interactive(plan)
            check(exitCode == 0) { "认证终端退出码为 $exitCode，未确认配置成功" }
            check(!disposed && preparationGeneration.get() == generation) { "认证已取消" }
            renewed = connect(installation, File(cwd))
            check(!disposed && preparationGeneration.get() == generation) { "认证连接已取消" }
            prepared.set(renewed)
            initialization = JSONObject(checkNotNull(renewed.initialization).toString())
        } catch (e: Exception) {
            renewed?.close(); runtime = null; initialization = null; throw e
        } finally { busy = false }
    }
    suspend fun create(title: String): LocalCodexTaskRecord = operation.withLock {
        check(!disposed && recoverySessionId.isBlank()); registry.requireWritable()
        val client = checkNotNull(prepared.get()) { "请先连接运行器" }
        val installation = checkNotNull(runtime)
        val generation = preparationGeneration.get()
        val label = title.trim().ifBlank { "新对话" }
        require(label.length <= 500 && label.none { it < ' ' })
        busy = true
        try {
            val pending = JSONObject().put("pending", true).put("engine", installation.engine).put("directory", directory)
            journal.write(pending.toString()); recoverySessionId = "ACP 创建结果尚未确认"
            val result = client.newSession(directory)
            val id = result.getString("sessionId")
            recoverySessionId = id; journal.write(pending.put("sessionId", id).toString())
            check(!disposed && preparationGeneration.get() == generation) { "创建期间窗口已关闭，请核对原生会话" }
            val model = acpConfigSelectors(client.configOptions(id)).firstOrNull { it.category == "model" }?.current?.takeIf { it.isNotBlank() }
                ?: result.optJSONObject("models")?.optString("currentModelId")?.takeIf { it.isNotBlank() } ?: "native-default"
            val record = LocalCodexTaskRecord(id, System.getProperty("user.name"), System.getProperty("os.name"),
                File(installation.home).canonicalPath, directory, label, model, System.currentTimeMillis(), installation.engine, "native")
            registry.save(record)
            val controller = newController(record, client)
            try {
                check(!disposed)
                journal.write(JSONObject().put("pending", false).toString())
                controllers[record.key] = controller
                prepared.compareAndSet(client, null); runtime = null; initialization = null; recoverySessionId = ""
                record
            } catch (e: Exception) { controller.close(); throw e }
        } catch (e: Exception) {
            if (e is AcpRpcException && e.code == -32000 && !disposed && preparationGeneration.get() == generation) {
                journal.write(JSONObject().put("pending", false).toString()); recoverySessionId = ""
                throw IllegalStateException("运行器要求先登录，请选择上方原生认证方式后重试", e)
            }
            prepared.compareAndSet(client, null); client.close(); runtime = null; initialization = null; throw e
        }
        finally { busy = false }
    }

    /** 接回此电脑上建的会话（本机对齐 P3）：起运行器，按它声明的能力 session/load（回放历史）或 session/resume，接回成功才登记控制器。
     *  核对同本机 Codex 的 open：同一用户和系统、原数据目录、原工作目录。接回期间 [busy]（计入退出保护，新建对话等它）。 */
    suspend fun open(runtime: LocalRuntimeInstallation, key: String): AcpTaskController = operation.withLock {
        check(!disposed) { "本地会话管理器已关闭" }
        val record = registry.records.singleOrNull { it.key == key } ?: error("会话记录不存在")
        controllers[key]?.takeIf { it.ready }?.let { return@withLock it }
        registry.requireWritable()
        check(record.hostKey.isBlank() && runtime.engine == record.engine) { "运行器和会话不匹配" }
        AcpLaunch.arguments(record.engine)
        check(record.user == System.getProperty("user.name") && record.platform == System.getProperty("os.name")) { "会话不是在此电脑和用户下创建的" }
        check(runtime.ready) { LocalRuntimeDiscovery.title(record.engine) + " 运行器不可用：" + runtime.problem.ifBlank { "未检测到版本" } }
        check(File(runtime.home).canonicalPath == record.runtimeHome) { "会话属于另一个数据目录（${record.runtimeHome}），请在运行器里选回原来的那个" }
        require(File(record.directory).isAbsolute && File(record.directory).isDirectory) { "原工作目录在此电脑不可用：${record.directory}" }
        controllers[key]?.let { old -> check(!old.busy) { "当前轮次尚未结束" }; controllers.remove(key); old.close() }
        busy = true
        var client: AcpClient? = null
        var controller: AcpTaskController? = null
        try {
            val connection = connect(runtime, File(record.directory))
            client = connection
            startingClient.set(connection)
            check(!disposed) { "应用已关闭，未继续接回" }
            check(connection.supportsLoadSession() || connection.supportsResume()) { "此运行器不支持继续已有会话，可以新建对话" }
            val restored = newController(record, connection, restoring = true)
            controller = restored
            restored.restore(record.directory)
            check(!disposed) { "应用已关闭" }
            controllers[key] = restored
            restored
        } catch (e: Exception) {
            controller?.close(); client?.close()
            if (e is AcpRpcException && e.code == -32000) throw IllegalStateException("运行器要求先登录：请在新建对话里完成原生认证后重试", e)
            throw e
        } finally { startingClient.compareAndSet(client, null); busy = false }
    }

    /** 新建和接回共用：选的模型写回记录，下次接回按它显示。 */
    private fun newController(record: LocalCodexTaskRecord, client: AcpClient, restoring: Boolean = false) =
        AcpTaskController(record.key, record.threadId, client, queue, onConfigurationChanged = { options ->
            val currentModel = acpConfigSelectors(options).firstOrNull { it.category == "model" }?.current?.takeIf { it.isNotBlank() }
            val current = registry.records.singleOrNull { it.key == record.key }
            if (current != null && currentModel != null && current.model != currentModel) registry.save(current.copy(model = currentModel))
        }, onModelChanged = { model ->
            val current = registry.records.singleOrNull { it.key == record.key }
            if (current != null && current.model != model) registry.save(current.copy(model = model))
        }, onNotification = { title -> onNotification(record, title) }, restoring = restoring)

    override fun close() {
        disposed = true; prepared.getAndSet(null)?.close(); startingClient.getAndSet(null)?.close()
        controllers.values.toList().forEach { it.close() }; controllers.clear()
    }
}
