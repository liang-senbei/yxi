package app.yxi.desktop

import androidx.compose.runtime.*
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class ServiceSettings(val directory: String, val command: String, val port: Int) {
    fun plan(project: String) = PreviewServicePlan(project, directory, command, port)
}
internal class ProjectServices(file: File, protector: CredentialProtector? = WindowsCredentialProtector.forPlatform()) {
    private val disk = CredentialFile(file, protector)
    private var records by mutableStateOf<Map<String, ServiceSettings>>(emptyMap())
    var error by mutableStateOf("")
        private set
    private var readable = true
    init { try {
        val root = disk.read()
        if (root.length() > 0) {
            require(root.getInt("version") == 1)
            val entries = root.getJSONObject("projects")
            records = entries.keys().asSequence().associateWith { key -> entries.getJSONObject(key).let {
                ServiceSettings(it.getString("directory"), it.getString("command"), it.getInt("port")).also { value -> value.plan(key) }
            } }
        }
    } catch (e: Exception) { readable = false; error = "开发服务配置无法读取，原文件已保留：${e.message}" } }
    fun get(project: String) = records[project]
    @Synchronized fun save(project: String, value: ServiceSettings, expected: ServiceSettings?) {
        check(readable) { error }; check(records[project] == expected) { "配置已变化，请重新打开" }
        value.plan(project)
        val next = records + (project to value)
        val data = JSONObject()
        next.forEach { (key, entry) -> data.put(key, JSONObject().put("directory", entry.directory).put("command", entry.command).put("port", entry.port)) }
        try { disk.write(JSONObject().put("version", 1).put("projects", data)); records = next; error = "" }
        catch (e: Exception) { error = "开发服务配置未保存：${e.message}"; throw e }
    }
}
internal class PreviewServiceController(val project: String, private val root: String) {
    private val operation = kotlinx.coroutines.sync.Mutex()
    private val mutationRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    var snapshot by mutableStateOf<JSONObject?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var mutating by mutableStateOf(false)
        private set
    private var actionError by mutableStateOf("")
    private var queryError by mutableStateOf("")
    val error get() = actionError.ifBlank { queryError }
    suspend fun refresh(conn: Conn) = execute(conn, PreviewServicePlan.statusCommand(project), false)
    suspend fun start(conn: Conn, settings: ServiceSettings) = execute(conn, settings.plan(project).startCommand(), true)
    suspend fun stop(conn: Conn, runtime: String, config: String) = execute(conn, PreviewServicePlan.stopCommand(project, runtime, config), true)
    private suspend fun execute(conn: Conn, command: String, mutation: Boolean) {
        check(projectKey(conn.host, root) == project && conn.ssh.isConnected) { "服务器身份已变化或尚未连接" }
        if (mutation) {
            check(mutationRequested.compareAndSet(false, true)) { "开发服务操作尚未完成" }
            mutating = true; actionError = ""
        } else if (mutationRequested.get()) return
        suspend fun perform() {
          operation.lock()
          busy = true
          try {
            try {
                check(projectKey(conn.host, root) == project && conn.ssh.isConnected) { "服务器身份已变化或尚未连接" }
                val result = withContext(Dispatchers.IO) { PreviewServicePlan.result(conn.ssh.exec(command)) }
                snapshot = result
                val problem = serviceProblem(result)
                if (mutation) actionError = problem else queryError = problem
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snapshot = null
                if (mutation) actionError = "状态未确认：${e.message}" else queryError = "状态未确认：${e.message}"
            } finally { busy = false }
          } finally { operation.unlock() }
        }
        try { if (mutation) withContext(NonCancellable) { perform() } else perform() }
        finally { if (mutation) { mutating = false; mutationRequested.set(false) } }
    }
}

private fun serviceProblem(result: JSONObject): String = when (result.optString("state")) {
    "error", "unknown" -> result.optString("message", "状态未确认，请重新检查")
    "port-busy" -> "端口已有服务，未启动新会话。"
    "configuration-conflict" -> "现有会话使用不同配置，请先停止或保留当前服务。"
    "unowned" -> "会话归属不匹配，未接管。"
    "changed" -> "会话实例已变化，停止请求未应用。"
    "missing-tool" -> "服务端未安装tmux。"
    "missing-directory" -> "服务器工作目录不存在。"
    else -> ""
}

internal class ServiceEditor(val project: String, root: String, val saved: ServiceSettings?, defaultPort: Int) {
    private val initial = saved ?: ServiceSettings(root, "", defaultPort)
    var directory by mutableStateOf(initial.directory)
    var command by mutableStateOf(initial.command)
    var port by mutableStateOf(initial.port.toString())
    val dirty get() = directory != initial.directory || command != initial.command || port != initial.port.toString()
    fun value(): ServiceSettings = ServiceSettings(directory, command, port.trim().toIntOrNull() ?: error("请输入有效端口"))
        .also { it.plan(project) }
}
