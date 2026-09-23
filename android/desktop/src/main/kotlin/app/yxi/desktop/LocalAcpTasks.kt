package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** UI dispatcher owner. Authentication is explicit; only sessions created by this owner are writable. */
internal class LocalAcpTasks(private val queue: InstructionQueue, file: File,
    private val connect: suspend (LocalRuntimeInstallation, File) -> AcpClient = LocalAcpTransport::connect) : AutoCloseable {
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, AcpTaskController>()
    private val operation = Mutex()
    private val prepared = AtomicReference<AcpClient?>()
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
    suspend fun prepare(installation: LocalRuntimeInstallation, cwd: String): JSONObject = operation.withLock {
        check(!disposed && recoverySessionId.isBlank()); registry.requireWritable()
        AcpLaunch.arguments(installation.engine)
        val folder = File(cwd).canonicalFile
        require(File(cwd).isAbsolute && folder.isDirectory)
        busy = true
        prepared.getAndSet(null)?.close(); runtime = null; initialization = null
        var client: AcpClient? = null
        try {
            client = connect(installation, folder)
            check(!disposed)
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
    suspend fun create(title: String): LocalCodexTaskRecord = operation.withLock {
        check(!disposed && recoverySessionId.isBlank()); registry.requireWritable()
        val client = checkNotNull(prepared.get()) { "请先连接运行器" }
        val installation = checkNotNull(runtime)
        val label = title.trim().ifBlank { "新对话" }
        require(label.length <= 500 && label.none { it < ' ' })
        busy = true
        try {
            val pending = JSONObject().put("pending", true).put("engine", installation.engine).put("directory", directory)
            journal.write(pending.toString()); recoverySessionId = "ACP 创建结果尚未确认"
            val result = client.newSession(directory)
            val id = result.getString("sessionId")
            recoverySessionId = id; journal.write(pending.put("sessionId", id).toString())
            check(!disposed)
            val model = result.optJSONObject("models")?.optString("currentModelId")?.takeIf { it.isNotBlank() } ?: "native-default"
            val record = LocalCodexTaskRecord(id, System.getProperty("user.name"), System.getProperty("os.name"),
                File(installation.home).canonicalPath, directory, label, model, System.currentTimeMillis(), installation.engine, "native")
            registry.save(record)
            val controller = AcpTaskController(record.key, id, client, queue)
            try {
                check(!disposed)
                journal.write(JSONObject().put("pending", false).toString())
                controllers[record.key] = controller
                prepared.compareAndSet(client, null); runtime = null; initialization = null; recoverySessionId = ""
                record
            } catch (e: Exception) { controller.close(); throw e }
        } catch (e: Exception) { prepared.compareAndSet(client, null); client.close(); runtime = null; initialization = null; throw e }
        finally { busy = false }
    }
    override fun close() {
        disposed = true; prepared.getAndSet(null)?.close()
        controllers.values.toList().forEach { it.close() }; controllers.clear()
    }
}
