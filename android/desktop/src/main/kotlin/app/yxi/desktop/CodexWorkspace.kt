package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.swing.Swing

internal data class CodexTaskRecord(
    val hostKey: String, val threadId: String, val directory: String,
    val title: String, val createdAt: Long, val profileId: String = "", val profileScope: String = "",
    val modelOverride: String? = null, val effortOverride: String? = null,
    val sharedMcp: List<SharedMcpDefinition> = emptyList(),
) {
    val key get() = "codex:" + contentHash((hostKey + "\n" + threadId).toByteArray())
}

/** Local metadata only. Profile IDs reference the selected server's catalog; no credentials are copied here. */
internal class CodexTaskRegistry(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    var records by mutableStateOf<List<CodexTaskRecord>>(emptyList()); private set
    var error by mutableStateOf(""); private set
    private var readable = true

    init {
        try { records = disk.read()?.let(::decode).orEmpty() }
        catch (e: Exception) { readable = false; error = "任务登记无法读取，原文件已保留：${e.message}" }
    }

    fun requireWritable() { check(readable) { error } }

    @Synchronized fun save(record: CodexTaskRecord) {
        requireWritable()
        require(record.threadId.isNotBlank() && record.hostKey.isNotBlank())
        val next = records.filterNot { it.key == record.key } + record
        try {
            disk.write(JSONObject().put("version", 1).put("tasks", JSONArray(next.map { task ->
                JSONObject().put("hostKey", task.hostKey).put("threadId", task.threadId)
                    .put("directory", task.directory).put("title", task.title).put("createdAt", task.createdAt)
                    .put("profileId", task.profileId).put("profileScope", task.profileScope)
                    .put("modelOverride", task.modelOverride).put("effortOverride", task.effortOverride)
                    .put("sharedMcp", JSONArray(task.sharedMcp.map { it.json() }))
            })).toString(2))
            records = next; error = ""
        } catch (e: Exception) { error = "任务登记未保存：${e.message}"; throw e }
    }

    private fun decode(raw: String): List<CodexTaskRecord> {
        val data = JSONObject(raw)
        require(data.getInt("version") == 1)
        val tasks = data.getJSONArray("tasks")
        val result = (0 until tasks.length()).map { index ->
            val task = tasks.getJSONObject(index)
            require(!task.has("sharedMcp") || task.opt("sharedMcp") is JSONArray) { "任务共享 MCP 快照格式无效" }
            fun optionalText(key: String): String? {
                val value = task.opt(key)
                require(value == null || value === JSONObject.NULL || value is String) { "任务配置字段 $key 不是文本" }
                return (value as? String)?.takeIf { it.isNotBlank() }
            }
            CodexTaskRecord(task.getString("hostKey"), task.getString("threadId"), task.getString("directory"),
                task.getString("title"), task.getLong("createdAt"), optionalText("profileId").orEmpty(), optionalText("profileScope").orEmpty(),
                optionalText("modelOverride"), optionalText("effortOverride"), task.optJSONArray("sharedMcp")?.let { definitions ->
                    (0 until definitions.length()).map { SharedMcpDefinition.parse(definitions.getJSONObject(it)) }
                }.orEmpty()).also {
                require(it.sharedMcp.all { definition -> definition.hostKey == it.hostKey } && it.sharedMcp.map { definition -> definition.name }.distinct().size == it.sharedMcp.size)
                require(it.hostKey.isNotBlank() && it.threadId.isNotBlank() && it.directory.startsWith('/') && it.createdAt > 0)
                require(it.profileId.length <= 200 && it.profileId.none { c -> c < ' ' })
                require(it.profileScope.isEmpty() || it.profileScope.matches(Regex("[a-f0-9]{32}")))
                require(it.modelOverride == null || it.modelOverride.length <= 512 && it.modelOverride.none { c -> c < ' ' || c == '\u007f' })
                require(it.effortOverride == null || it.effortOverride.matches(Regex("[a-z]{1,20}")))
            }
        }
        require(result.map { it.key }.distinct().size == result.size)
        return result
    }
}

internal class CodexWorkspace(private val queue: InstructionQueue, file: File, private val sharedMcp: SharedMcpRegistry? = null,
    private val onNotice: (CodexTaskRecord, String) -> Unit = { _, _ -> }) : AutoCloseable {
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val attachments = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<DraftAttach>>()
    fun stageAttachment(conn: Conn, task: CodexTaskRecord, image: DraftAttach) {
        check(task.hostKey == projectKey(conn.host, "/")) { "附件目标服务器已改变" }
        val drafts = attachments.getOrPut(task.key) { mutableStateListOf() }
        require(drafts.size < 10) { "一次最多添加10个附件" }
        drafts.add(image)
        image.state = DraftState.Uploading(0, image.size)
        Attach.launchUpload(conn, "codex-" + task.key.removePrefix("codex:"), image, uploadScope)
    }
    fun retryAttachment(conn: Conn, task: CodexTaskRecord, image: DraftAttach) {
        check(task.hostKey == projectKey(conn.host, "/") && attachments[task.key]?.contains(image) == true)
        check(image.state is DraftState.Failed)
        image.cancelled.set(false)
        image.state = DraftState.Uploading(0, image.size)
        Attach.launchUpload(conn, "codex-" + task.key.removePrefix("codex:"), image, uploadScope)
    }
    val registry = CodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, CodexTaskController>()
    private val owners = mutableMapOf<String, Conn>()
    private val operations = Mutex()
    var busy by mutableStateOf(false); private set
    var recoveryThreadId by mutableStateOf(""); private set
    // 测试缝（机械改动，仅供离线协议测试注入假运行器；生产默认即真实实现）
    internal var clientFactory: suspend (Conn) -> CodexAppServer = { CodexAppServer.connect(it.ssh) }
    internal var profileClientFactory: suspend (Conn, app.yxi.agent.Lines.Line, String) -> CodexAppServer = { conn, line, scope -> CodexAppServer.connect(conn.ssh, line, scope) }
    internal var connected: (Conn) -> Boolean = { it.ssh.isConnected }

    private fun scopeFor(record: CodexTaskRecord) = record.profileScope.ifBlank { contentHash(record.key.toByteArray()).take(32) }
    private suspend fun clientFor(conn: Conn, profileId: String, scope: String, definitions: List<SharedMcpDefinition> = emptyList()): CodexAppServer {
        val line = if (profileId.isBlank()) null else (app.yxi.agent.Lines.list(conn.ssh)?.singleOrNull { it.id == profileId && it.agent == app.yxi.agent.Lines.CODEX }
            ?: error("此 Agent 的供应商配置无法读取或已删除，请先恢复该配置；不会改用全局配置"))
        suspend fun baseline() = if (line == null) clientFactory(conn) else profileClientFactory(conn, line, scope)
        if (definitions.isEmpty()) return baseline()
        val records = definitions.map { SharedMcpRecord(it, setOf("codex"), 0) }
        val arguments = SharedMcpSettings.codexArguments(records, projectKey(conn.host, "/"))
        baseline().use { probe ->
            val existing = probe.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config").optJSONObject("mcp_servers")
            check(definitions.none { it.name == "codex_apps" || existing?.has(it.name) == true }) { "服务器原生 Codex 已有同名或内置 MCP，未覆盖" }
        }
        val client = CodexAppServer.connect(conn.ssh, line, scope, arguments)
        try {
            suspend fun verify() = LocalCodexProfiles.verifySharedMcp(client.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config"), records)
            verify()
            client.beforeMutation = { _, params ->
                check(params.optJSONObject("config")?.keys()?.asSequence()?.none { it == "mcp_servers" || it.startsWith("mcp_servers.") } != false) { "会话 MCP 覆盖未经核对" }
                verify()
            }
            return client
        } catch (e: Exception) { client.close(); throw e }
    }

    fun tasks(host: Host) = registry.records.filter { it.hostKey == projectKey(host, "/") }.sortedByDescending { it.createdAt }

    suspend fun create(conn: Conn, directory: String, title: String, profileId: String = ""): CodexTaskRecord = operations.withLock {
        registry.requireWritable()
        check(sharedMcp?.problem.isNullOrBlank()) { sharedMcp?.problem.orEmpty() }
        check(connected(conn)) { "请先连接服务器" }
        require(directory.startsWith('/') && directory.none { it < ' ' }) { "请输入服务器绝对目录" }
        busy = true; recoveryThreadId = ""
        val profileScope = java.util.UUID.randomUUID().toString().replace("-", "")
        val resources = sharedMcp?.forHost(projectKey(conn.host, "/"))?.filter { "codex" in it.desiredRunners }.orEmpty()
        val client = try { clientFor(conn, profileId, profileScope, resources.map { it.definition }) } catch (e: Exception) { busy = false; throw e }
        try {
            val result = client.startThread(directory).getJSONObject("result")
            val thread = result.getJSONObject("thread")
            val id = thread.getString("id")
            recoveryThreadId = id // Retain the server ID if local metadata cannot be written.
            check(sharedMcp?.forHost(projectKey(conn.host, "/"))?.filter { "codex" in it.desiredRunners }.orEmpty() == resources) { "共享插件配置在创建期间发生变化，请核对原生会话" }
            val record = CodexTaskRecord(projectKey(conn.host, "/"), id, thread.optString("cwd").ifBlank { directory },
                title.trim().ifBlank { directory.substringAfterLast('/').ifBlank { "新任务" } }, System.currentTimeMillis(), profileId, profileScope, sharedMcp = resources.map { it.definition })
            registry.save(record)
            client.verifyProfile(result)
            recoveryThreadId = ""
            attach(conn, record, client, thread.takeIf { it.optJSONArray("turns")?.length() == 0 && it.optJSONObject("status")?.optString("type") == "idle" }).recordSessionConfiguration(result)
            record
        } catch (e: Exception) { client.close(); throw e }
        finally { busy = false }
    }

    suspend fun open(conn: Conn, record: CodexTaskRecord, autoRun: Boolean = true): CodexTaskController = operations.withLock {
        check(record.hostKey == projectKey(conn.host, "/")) { "任务不属于当前服务器配置" }
        controllers[record.key]?.takeIf { it.ready && owners[record.key] === conn }?.let {
            if (!autoRun) it.setAutoDispatch(false)
            return@withLock it
        }
        check(connected(conn)) { "服务器未连接" }
        controllers.remove(record.key)?.close(); owners.remove(record.key)
        busy = true
        val client = try { clientFor(conn, record.profileId, scopeFor(record), record.sharedMcp) } catch (e: Exception) { busy = false; throw e }
        try {
            val overrides = client.conversationOverrides(record.modelOverride, record.effortOverride)
            val result = resumeExistingThread(client, record.threadId, overrides)
            val thread = result.getJSONObject("thread")
            check(thread.getString("id") == record.threadId) { "恢复响应不属于原任务" }
            client.verifyProfile(result, verifyModel = false)
            check(overrides?.model == null || result.optString("model") == overrides.model) { "运行器未恢复此 Agent 已保存的模型选择" }
            attach(conn, record, client, autoRun = autoRun).also { it.recordSessionConfiguration(result) }
        } catch (e: Exception) { client.close(); throw e }
        finally { busy = false }
    }

    suspend fun applyCurrentConfiguration(conn: Conn, record: CodexTaskRecord, profileId: String = record.profileId): CodexTaskController = operations.withLock {
        registry.requireWritable()
        check(record.hostKey == projectKey(conn.host, "/") && connected(conn)) { "请连接任务原服务器" }
        val previous = controllers[record.key]
        check(previous != null && owners[record.key] === conn) { "请先打开原任务" }
        busy = true
        try {
            val client = clientFor(conn, profileId, scopeFor(record), record.sharedMcp)
            var originalClosed = false
            try {
                val overrides = client.readResumeOverrides(record.directory)
                previous.closeForConfigurationChange()
                originalClosed = true
                controllers.remove(record.key); owners.remove(record.key)
                val result = client.resumeThread(record.threadId, overrides).getJSONObject("result")
                val thread = result.getJSONObject("thread")
                check(thread.getString("id") == record.threadId && normalizeProjectPath(thread.getString("cwd")) == normalizeProjectPath(record.directory)) { "恢复响应与原任务不一致" }
                client.verifyProfile(result)
                val updated = record.copy(profileId = profileId, profileScope = scopeFor(record), modelOverride = null, effortOverride = null)
                registry.save(updated)
                attach(conn, updated, client, autoRun = false).also { it.recordSessionConfiguration(result) }
            } catch (e: Exception) {
                client.close()
                if (e is CancellationException) throw e
                if (originalClosed) throw IllegalStateException("线路应用未完成，原任务编号、草稿和队列已保留。请点击“连接任务”重新读取历史，确认连接配置后再发送。原因：${e.message}", e)
                throw e
            }
        } finally { busy = false }
    }

    /** Import an existing server thread without creating a replacement or sending a turn. */
    suspend fun recover(conn: Conn, threadId: String, title: String): CodexTaskRecord = operations.withLock {
        registry.requireWritable()
        val id = threadId.trim()
        require(id.isNotBlank() && id.length <= 256 && id.none { it.isWhitespace() || it < ' ' }) { "请输入有效的任务编号" }
        val hostKey = projectKey(conn.host, "/")
        registry.records.firstOrNull { it.hostKey == hostKey && it.threadId == id }?.let { return@withLock it }
        check(connected(conn)) { "请先连接服务器" }
        busy = true
        val client = try { clientFactory(conn) } catch (e: Exception) { busy = false; throw e }
        try {
            val result = resumeExistingThread(client, id)
            val thread = result.getJSONObject("thread")
            check(thread.getString("id") == id) { "恢复响应不属于原任务" }
            val directory = thread.getString("cwd")
            require(directory.startsWith('/') && directory.none { it < ' ' }) { "服务器返回的项目目录无效" }
            val record = CodexTaskRecord(hostKey, id, directory,
                title.trim().ifBlank { thread.optString("name").takeUnless { it == "null" }.orEmpty().ifBlank { directory.substringAfterLast('/').ifBlank { "恢复的任务" } } },
                System.currentTimeMillis())
            recoveryThreadId = id
            registry.save(record)
            recoveryThreadId = ""
            attach(conn, record, client).recordSessionConfiguration(result)
            record
        } catch (e: Exception) { client.close(); throw e }
        finally { busy = false }
    }

    private suspend fun resumeExistingThread(client: CodexAppServer, id: String, overrides: CodexResumeOverrides? = null): JSONObject {
        try {
            return client.resumeThread(id, overrides).getJSONObject("result")
        } catch (e: Exception) {
            if (e.message?.contains("no rollout found", ignoreCase = true) == true) {
                throw IllegalStateException("服务器没有找到此任务的历史文件。尚未发送过内容的新任务，关闭后可能无法恢复；也请确认连接的是原服务器和账号。任务编号：$id。可保留此记录，手动新建任务后再使用原草稿。", e)
            }
            throw e
        }
    }

    private suspend fun attach(conn: Conn, record: CodexTaskRecord, client: CodexAppServer, createdThread: JSONObject? = null, autoRun: Boolean = true): CodexTaskController {
        lateinit var controller: CodexTaskController
        controller = CodexTaskController(record.key, record.threadId, client, queue,
            initialModel = record.modelOverride, initialEffort = record.effortOverride,
            onModelSelection = { model, effort ->
                check(controllers[record.key] === controller) { "任务连接已变化，请重新打开模型菜单" }
                val current = registry.records.single { it.key == record.key }
                registry.save(current.copy(modelOverride = model, effortOverride = effort))
            }) { title -> onNotice(record, title) }
        try { controller.reconcile(createdThread) } catch (e: Exception) { controller.close(); throw e }
        controllers[record.key] = controller; owners[record.key] = conn
        controller.setAutoDispatch(autoRun && QueuePreferences.enabled(record.key))
        return controller
    }

    fun disconnect(conn: Conn) {
        owners.filterValues { it === conn }.keys.toList().forEach { key ->
            controllers.remove(key)?.close(); owners.remove(key)
        }
    }

    override fun close() {
        attachments.values.flatten().forEach { it.cancelled.set(true) }
        uploadScope.cancel()
        controllers.values.toList().forEach { it.close() }
        controllers.clear(); owners.clear()
    }
}
