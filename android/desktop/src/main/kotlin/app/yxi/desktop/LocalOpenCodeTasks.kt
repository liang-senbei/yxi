package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** 本机 OpenCode 会话：[create] 新建，[open] 接回此电脑上建过的（本机对齐 P4）。会话本身存在 OpenCode 自己的数据里，Yxi 只登记、只持有自己起的服务。
 *  [serve] 起服务，生产是 [LocalOpenCodeServer.start]；单测和截图夹具换成回环上的假服务。 */
internal class LocalOpenCodeTasks(private val queue: InstructionQueue, private val file: File, private val sharedMcp: SharedMcpRegistry? = null, private val inherited: Map<String, String>? = null) : AutoCloseable {
    // 用到时再取：macOS 上登录 shell 的 PATH 是后台读的，构造时可能还没读完
    private val environment: Map<String, String> get() = inherited ?: LoginShellPath.environment()
    internal var serve: suspend (LocalRuntimeInstallation, File, List<SharedMcpDefinition>) -> OpenCodeServing =
        { runtime, directory, mcp -> LocalOpenCodeServer.start(runtime, directory, environment, sharedMcp = mcp) }
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, OpenCodeTaskController>()
    private val servers = mutableMapOf<String, OpenCodeServing>()
    private val operation = Mutex()
    @Volatile private var disposed = false
    private val starting = java.util.concurrent.atomic.AtomicReference<OpenCodeServing?>()
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
            serve(runtime, File(directory), emptyList()).use {
                starting.set(it); check(!disposed)
                it.client.availableModels()
            }
        } finally { starting.set(null); busy = false }
    }
    suspend fun create(runtime: LocalRuntimeInstallation, directory: String, title: String, model: OpenCodeModel): LocalCodexTaskRecord = operation.withLock {
        check(!disposed); registry.requireWritable(); check(recoverySessionId.isBlank()) { "上次创建结果待核对：$recoverySessionId" }
        check(sharedMcp?.problem.isNullOrBlank()) { sharedMcp?.problem.orEmpty() }
        require(File(directory).isAbsolute && File(directory).isDirectory)
        val cwd = File(directory).canonicalPath
        val label = title.trim().ifBlank { File(cwd).name.ifBlank { "新对话" } }
        require(label.length <= 500 && label.none { it < ' ' })
        busy = true
        var server: OpenCodeServing? = null
        try {
            val (connection, resources) = launch(runtime, cwd) { client ->
                check(client.availableModels().any { it.providerId == model.providerId && it.modelId == model.modelId }) { "所选模型当前不可用" }
            }
            server = connection
            val pending = JSONObject().put("pending", true).put("directory", cwd).put("runtimeHome", runtime.home)
            journal.write(pending.toString()); recoverySessionId = "创建请求结果尚未确认"
            val session = connection.client.create(label)
            val id = session.getString("id"); recoverySessionId = id
            journal.write(pending.put("sessionId", id).toString())
            check(sharedMcp?.forHost("@local")?.filter { "opencode" in it.desiredRunners }.orEmpty() == resources) { "共享插件配置在创建期间发生变化，请核对已创建会话" }
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

    /** 接回此电脑上建的会话（本机对齐 P4）：重新起服务、挂上和新建时同样的共享插件，再按记录里的会话 id 读回；读回的 id 和工作目录对得上、
     *  控制器第一次刷新成功才登记。核对同本机 ACP / Codex 的 open：同一用户和系统、原数据目录、原工作目录、运行器可用。
     *  不在这里核模型：发送时 sendNext 会核所选模型是否还可用。接回期间 [busy]（计入退出保护，新建对话等它）；接回中 [close] 会关掉刚起的服务。 */
    suspend fun open(runtime: LocalRuntimeInstallation, key: String): OpenCodeTaskController = operation.withLock {
        check(!disposed) { "本地会话管理器已关闭" }
        val record = registry.records.singleOrNull { it.key == key } ?: error("会话记录不存在")
        controllers[key]?.let { return@withLock it }
        registry.requireWritable()
        check(record.hostKey.isBlank() && record.engine == "opencode" && runtime.engine == record.engine) { "运行器和会话不匹配" }
        check(LocalOwner.owns(record.user, record.platform)) { "会话不是在此电脑和用户下创建的" }
        check(runtime.ready) { "OpenCode 运行器不可用：" + runtime.problem.ifBlank { "未检测到版本" } }
        check(File(runtime.home).canonicalPath == record.runtimeHome) { "会话属于另一个数据目录（${record.runtimeHome}），请在运行器里选回原来的那个" }
        require(File(record.directory).isAbsolute && File(record.directory).isDirectory) { "原工作目录在此电脑不可用：${record.directory}" }
        check(sharedMcp?.problem.isNullOrBlank()) { sharedMcp?.problem.orEmpty() }
        busy = true
        var server: OpenCodeServing? = null
        try {
            val (connection, _) = launch(runtime, record.directory) {}
            server = connection
            val session = try { connection.client.session(record.threadId) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw IllegalStateException("OpenCode 里读不到这个会话，可能已在原生端删除（${e.message.orEmpty()}）", e) }
            check(session.optString("id") == record.threadId && session.optString("directory") == record.directory) { "OpenCode 会话归属或工作目录已变化，未接回" }
            val controller = OpenCodeTaskController(record.key, record.threadId, record.directory, record.provider, record.model, connection.client, queue)
            controller.refresh(); check(!disposed) { "应用已关闭，未继续接回" }
            controllers[key] = controller; servers[key] = connection
            controller
        } catch (e: Exception) { server?.close(); throw e }
        finally { starting.compareAndSet(server, null); busy = false }
    }

    /** 新建和接回共用：起服务，按共享插件配置挂上 MCP（带请求头变量的要在启动时加载，所以先核对再按它们重起一次）。
     *  [verify] 在每次起好服务后调用（新建核所选模型）。返回服务和这次挂上的资源；出错时关掉自己起的服务。 */
    private suspend fun launch(runtime: LocalRuntimeInstallation, cwd: String, verify: suspend (OpenCodeClient) -> Unit): Pair<OpenCodeServing, List<SharedMcpRecord>> {
        var server: OpenCodeServing? = null
        try {
            var connection = serve(runtime, File(cwd), emptyList()); server = connection; starting.set(connection)
            check(!disposed)
            verify(connection.client)
            val scopeId = java.util.UUID.randomUUID().toString()
            val resources = sharedMcp?.forHost("@local")?.filter { "opencode" in it.desiredRunners }.orEmpty()
            val startupResources = resources.filter { it.definition.headerVariables.isNotEmpty() }
            if (startupResources.isNotEmpty()) {
                val existing = connection.client.mcpStatus()
                check(resources.none { existing.has(it.definition.name) }) { "原生运行器已有同名 MCP，未覆盖" }
                connection.close(); starting.compareAndSet(connection, null)
                check(!disposed)
                connection = serve(runtime, File(cwd), startupResources.map { it.definition })
                server = connection; starting.set(connection); check(!disposed)
                verify(connection.client)
            }
            resources.forEach { resource ->
                val status = if (resource in startupResources) connection.client.mcpStatus().optJSONObject(resource.definition.name)?.optString("status")
                else OpenCodeMcpBindings("@local", scopeId, connection.client, File(file.parentFile, "mcp-bindings/$scopeId/${resource.definition.key}.json")).apply(resource)
                check(status == "connected") { "共享插件 ${resource.definition.name} 尚未连接，请先核对插件授权或服务" }
                check(sharedMcp?.records?.any { it == resource } == true) { "共享插件配置在创建期间发生变化，请重新核对" }
            }
            return connection to resources
        } catch (e: Exception) { server?.close(); starting.compareAndSet(server, null); throw e }
    }

    override fun close() {
        disposed = true; starting.getAndSet(null)?.close(); servers.values.toList().forEach { it.close() }; servers.clear(); controllers.clear()
    }
}
