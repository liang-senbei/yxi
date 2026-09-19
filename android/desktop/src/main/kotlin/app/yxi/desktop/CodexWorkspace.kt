package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CodexTaskRecord(
    val hostKey: String, val threadId: String, val directory: String,
    val title: String, val createdAt: Long,
) {
    val key get() = "codex:" + contentHash((hostKey + "\n" + threadId).toByteArray())
}

/** Local metadata only. Authentication remains in the selected server's Codex configuration. */
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
            CodexTaskRecord(task.getString("hostKey"), task.getString("threadId"), task.getString("directory"),
                task.getString("title"), task.getLong("createdAt")).also {
                require(it.hostKey.isNotBlank() && it.threadId.isNotBlank() && it.directory.startsWith('/') && it.createdAt > 0)
            }
        }
        require(result.map { it.key }.distinct().size == result.size)
        return result
    }
}

internal class CodexWorkspace(private val queue: InstructionQueue, file: File) : AutoCloseable {
    val registry = CodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, CodexTaskController>()
    private val owners = mutableMapOf<String, Conn>()
    private val operations = Mutex()
    var busy by mutableStateOf(false); private set
    var recoveryThreadId by mutableStateOf(""); private set

    fun tasks(host: Host) = registry.records.filter { it.hostKey == projectKey(host, "/") }.sortedByDescending { it.createdAt }

    suspend fun create(conn: Conn, directory: String, title: String): CodexTaskRecord = operations.withLock {
        registry.requireWritable()
        check(conn.ssh.isConnected) { "请先连接服务器" }
        require(directory.startsWith('/') && directory.none { it < ' ' }) { "请输入服务器绝对目录" }
        busy = true; recoveryThreadId = ""
        val client = try { CodexAppServer.connect(conn.ssh) } catch (e: Exception) { busy = false; throw e }
        try {
            val thread = client.startThread(directory).getJSONObject("result").getJSONObject("thread")
            val id = thread.getString("id")
            recoveryThreadId = id // Retain the server ID if local metadata cannot be written.
            val record = CodexTaskRecord(projectKey(conn.host, "/"), id, thread.optString("cwd").ifBlank { directory },
                title.trim().ifBlank { directory.substringAfterLast('/').ifBlank { "新任务" } }, System.currentTimeMillis())
            registry.save(record)
            recoveryThreadId = ""
            attach(conn, record, client)
            record
        } catch (e: Exception) { client.close(); throw e }
        finally { busy = false }
    }

    suspend fun open(conn: Conn, record: CodexTaskRecord): CodexTaskController = operations.withLock {
        check(record.hostKey == projectKey(conn.host, "/")) { "任务不属于当前服务器配置" }
        controllers[record.key]?.takeIf { it.ready && owners[record.key] === conn }?.let { return@withLock it }
        check(conn.ssh.isConnected) { "服务器未连接" }
        controllers.remove(record.key)?.close(); owners.remove(record.key)
        busy = true
        val client = try { CodexAppServer.connect(conn.ssh) } catch (e: Exception) { busy = false; throw e }
        try {
            val thread = client.resumeThread(record.threadId).getJSONObject("result").getJSONObject("thread")
            check(thread.getString("id") == record.threadId) { "恢复响应不属于原任务" }
            attach(conn, record, client)
        } catch (e: Exception) { client.close(); throw e }
        finally { busy = false }
    }

    private suspend fun attach(conn: Conn, record: CodexTaskRecord, client: CodexAppServer): CodexTaskController {
        val controller = CodexTaskController(record.key, record.threadId, client, queue)
        try { controller.reconcile() } catch (e: Exception) { controller.close(); throw e }
        controllers[record.key] = controller; owners[record.key] = conn
        return controller
    }

    fun disconnect(conn: Conn) {
        owners.filterValues { it === conn }.keys.toList().forEach { key ->
            controllers.remove(key)?.close(); owners.remove(key)
        }
    }

    override fun close() {
        controllers.values.toList().forEach { it.close() }
        controllers.clear(); owners.clear()
    }
}
