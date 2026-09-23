package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

internal class RemoteOpenCodeTasks(private val queue: InstructionQueue, file: File) : AutoCloseable {
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, OpenCodeTaskController>()
    private val servers = mutableMapOf<String, Pair<Conn, RemoteOpenCodeServer>>()
    private val operation = Mutex()
    @Volatile private var disposed = false
    private val starting = java.util.concurrent.atomic.AtomicReference<RemoteOpenCodeServer?>()
    var busy by mutableStateOf(false); private set
    var recoverySessionId by mutableStateOf(""); private set
    private val journal = DurableFile(File(file.parentFile, file.name + ".creation.json")) { require(JSONObject(it).opt("pending") is Boolean) }
    init {
        try { journal.read()?.let { JSONObject(it) }?.takeIf { it.getBoolean("pending") }?.let {
            recoverySessionId = it.optString("sessionId").ifBlank { "上次服务器创建结果尚未确认" }
        } } catch (_: Exception) { recoverySessionId = "创建记录损坏，请核对服务器原生历史" }
    }
    fun tasks(host: Host) = registry.records.filter { it.hostKey == projectKey(host, "/") }
    fun confirmCreationReviewed() { check(!busy); journal.write(JSONObject().put("pending", false).toString()); recoverySessionId = "" }
    suspend fun models(conn: Conn, directory: String): List<OpenCodeModel> = operation.withLock {
        check(!disposed); busy = true
        try { RemoteOpenCodeServer.start(conn.ssh, directory).use { starting.set(it); check(!disposed); it.client.availableModels() } }
        finally { starting.set(null); busy = false }
    }
    suspend fun create(conn: Conn, directory: String, title: String, model: OpenCodeModel): LocalCodexTaskRecord = operation.withLock {
        check(!disposed && conn.ssh.isConnected); registry.requireWritable(); check(recoverySessionId.isBlank()) { "上次创建需要核对：$recoverySessionId" }
        require(title.length <= 500 && title.none { it < ' ' })
        busy = true
        var server: RemoteOpenCodeServer? = null
        try {
            val connection = RemoteOpenCodeServer.start(conn.ssh, directory); server = connection; starting.set(connection); check(!disposed)
            check(connection.client.availableModels().any { it.providerId == model.providerId && it.modelId == model.modelId }) { "所选服务器模型当前不可用" }
            val hostKey = projectKey(conn.host, "/")
            val pending = JSONObject().put("pending", true).put("hostKey", hostKey).put("directory", connection.directory)
            journal.write(pending.toString()); recoverySessionId = "服务器创建请求尚未确认"
            val session = connection.client.create(title.ifBlank { "OpenCode 新对话" })
            val id = session.getString("id"); recoverySessionId = id; journal.write(pending.put("sessionId", id).toString())
            check(session.getString("directory") == connection.directory)
            val record = LocalCodexTaskRecord(id, conn.host.username, "remote", connection.runtimeHome, connection.directory,
                session.getString("title"), model.modelId, System.currentTimeMillis(), "opencode", model.providerId, hostKey)
            registry.save(record)
            val controller = OpenCodeTaskController(record.key, id, record.directory, model.providerId, model.modelId, connection.client, queue)
            controller.refresh(); check(!disposed && conn.ssh.isConnected)
            journal.write(JSONObject().put("pending", false).toString())
            controllers[record.key] = controller; servers[record.key] = conn to connection; recoverySessionId = ""
            record
        } catch (e: Exception) { server?.close(); throw e }
        finally { starting.compareAndSet(server, null); busy = false }
    }
    fun disconnect(conn: Conn) {
        servers.filterValues { it.first === conn }.keys.toList().forEach { key -> servers.remove(key)?.second?.close(); controllers.remove(key) }
    }
    override fun close() { disposed = true; starting.getAndSet(null)?.close(); servers.values.toList().forEach { it.second.close() }; servers.clear(); controllers.clear() }
}
