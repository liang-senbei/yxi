package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** UI-dispatcher owner; creation uncertainty and task identity are scoped to the selected host. */
internal class RemoteAcpTasks(private val queue: InstructionQueue, private val file: File,
    private val onNotification: (LocalCodexTaskRecord, String) -> Unit = { _, _ -> }) : AutoCloseable {
    private data class Prepared(val conn: Conn, val engine: String, val directory: String, val home: String, val client: AcpClient)
    private class Creation(file: File, val hostKey: String) {
        val disk = DurableFile(file) { require(JSONObject(it).getString("hostKey") == hostKey && JSONObject(it).opt("pending") is Boolean) }
        var recovery by mutableStateOf("")
        init { try { disk.read()?.let(::JSONObject)?.takeIf { it.getBoolean("pending") }?.let {
            recovery = it.optString("sessionId").ifBlank { "服务器创建结果未确认" }
        } } catch (_: Exception) { recovery = "创建记录损坏，请核对服务器原生历史" } }
        fun clear() { disk.write(JSONObject().put("hostKey", hostKey).put("pending", false).toString()); recovery = "" }
        fun pending(directory: String, id: String = "") {
            disk.write(JSONObject().put("hostKey", hostKey).put("pending", true).put("directory", directory).put("sessionId", id).toString())
            recovery = id.ifBlank { "服务器创建结果未确认" }
        }
    }
    val registry = LocalCodexTaskRegistry(file)
    val controllers = mutableStateMapOf<String, AcpTaskController>()
    private val owners = mutableMapOf<String, Conn>()
    private val creations = mutableMapOf<String, Creation>()
    private val prepared = AtomicReference<Prepared?>()
    private val authenticating = AtomicReference<RemoteAuthenticationPlan?>()
    private val generation = AtomicLong()
    private val operation = Mutex()
    @Volatile private var disposed = false
    var busy by mutableStateOf(false); private set
    var initialization by mutableStateOf<JSONObject?>(null); private set
    private fun creation(conn: Conn): Creation {
        val key = projectKey(conn.host, "/")
        return creations.getOrPut(key) { Creation(File(file.parentFile, "remote-acp-creation/${contentHash(key.toByteArray())}.json"), key) }
    }
    fun recoverySessionId(conn: Conn) = creation(conn).recovery
    fun confirmCreationReviewed(conn: Conn) { check(!busy); creation(conn).clear() }
    fun tasks(host: Host) = registry.records.filter { it.hostKey == projectKey(host, "/") }
    fun tasks(conn: Conn) = tasks(conn.host)
    fun abandonPreparation() { generation.incrementAndGet(); prepared.getAndSet(null)?.client?.close(); authenticating.getAndSet(null)?.close(); initialization = null }
    suspend fun prepare(conn: Conn, engine: String, directory: String): JSONObject = operation.withLock {
        check(!disposed && conn.ssh.isConnected); registry.requireWritable()
        check(creation(conn).recovery.isBlank()) { "请先核对上次服务器创建结果" }
        AcpLaunch.arguments(engine)
        require(directory.startsWith('/') && directory.none { it < ' ' })
        abandonPreparation(); val epoch = generation.get(); busy = true
        var client: AcpClient? = null
        try {
            val metadata = JSONObject(conn.ssh.exec("python3 -c ${Shell.q(identityScript)} ${Shell.q(engine)} ${Shell.q(directory)}").trim())
            client = RemoteAcpTransport.connect(conn.ssh, engine, metadata.getString("directory"), terminalAuthentication = true)
            check(!disposed && generation.get() == epoch && conn.ssh.isConnected) { "服务器连接准备已取消" }
            prepared.set(Prepared(conn, engine, metadata.getString("directory"), metadata.getString("home"), client))
            val hello = JSONObject(checkNotNull(client.initialization).toString()); initialization = hello
            JSONObject(hello.toString())
        } catch (e: Exception) { client?.close(); throw e }
        finally { busy = false }
    }
    suspend fun authenticate(conn: Conn, methodId: String) = operation.withLock {
        val current = checkNotNull(prepared.get()); check(!disposed && current.conn === conn)
        busy = true
        try { current.client.authenticate(methodId) } finally { busy = false }
    }
    suspend fun authenticateTerminal(conn: Conn, methodId: String, interactive: suspend (RemoteAuthenticationPlan) -> Int?) = operation.withLock {
        val current = checkNotNull(prepared.get()); check(!disposed && current.conn === conn)
        val methods = checkNotNull(current.client.initialization).getJSONArray("authMethods")
        val method = (0 until methods.length()).map { methods.getJSONObject(it) }.single { it.getString("id") == methodId }
        require(method.optString("type") == "terminal")
        val epoch = generation.get(); busy = true
        prepared.compareAndSet(current, null); current.client.close()
        val plan = RemoteAuthenticationPlan(conn.ssh, current.engine, current.directory, JSONObject(method.toString()))
        authenticating.set(plan)
        var renewed: AcpClient? = null
        try {
            val code = interactive(plan)
            check(code == 0) { "服务器认证未成功结束：${code ?: "退出状态未知"}" }
            check(!disposed && generation.get() == epoch && conn.ssh.isConnected) { "服务器认证已取消" }
            renewed = RemoteAcpTransport.connect(conn.ssh, current.engine, current.directory, terminalAuthentication = true)
            check(!disposed && generation.get() == epoch && conn.ssh.isConnected) { "服务器认证连接已取消" }
            prepared.set(current.copy(client = renewed)); initialization = JSONObject(checkNotNull(renewed.initialization).toString())
        } catch (e: Exception) { renewed?.close(); initialization = null; throw e }
        finally { authenticating.compareAndSet(plan, null); plan.close(); busy = false }
    }
    suspend fun create(conn: Conn, title: String): LocalCodexTaskRecord = operation.withLock {
        check(!disposed && conn.ssh.isConnected); registry.requireWritable()
        val current = checkNotNull(prepared.get()); check(current.conn === conn)
        val pending = creation(conn); check(pending.recovery.isBlank())
        val label = title.trim().ifBlank { "新对话" }; require(label.length <= 500 && label.none { it < ' ' })
        val epoch = generation.get(); busy = true
        try {
            pending.pending(current.directory)
            val result = current.client.newSession(current.directory)
            val id = result.getString("sessionId"); pending.pending(current.directory, id)
            check(!disposed && epoch == generation.get() && conn.ssh.isConnected) { "服务器创建期间连接变化，请核对会话" }
            val model = acpConfigSelectors(current.client.configOptions(id)).firstOrNull { it.category == "model" }?.current?.takeIf { it.isNotBlank() }
                ?: result.optJSONObject("models")?.optString("currentModelId")?.takeIf { it.isNotBlank() } ?: "native-default"
            val record = LocalCodexTaskRecord(id, conn.host.username, "remote", current.home, current.directory, label, model,
                System.currentTimeMillis(), current.engine, "native", projectKey(conn.host, "/"))
            registry.save(record)
            fun saveModel(value: String) { registry.records.singleOrNull { it.key == record.key }?.let { if (it.model != value) registry.save(it.copy(model = value)) } }
            val controller = AcpTaskController(record.key, id, current.client, queue,
                onConfigurationChanged = { options -> acpConfigSelectors(options).firstOrNull { it.category == "model" }?.current?.let(::saveModel) }, onModelChanged = ::saveModel,
                onNotification = { title -> onNotification(record, title) })
            try { pending.clear(); controllers[record.key] = controller; owners[record.key] = conn }
            catch (e: Exception) { controller.close(); throw e }
            prepared.compareAndSet(current, null); initialization = null
            record
        } catch (e: Exception) {
            if (e is AcpRpcException && e.code == -32000 && !disposed && epoch == generation.get()) {
                pending.clear(); throw IllegalStateException("服务器运行器要求先登录，请先完成原生认证", e)
            }
            prepared.compareAndSet(current, null); current.client.close(); initialization = null; throw e
        } finally { busy = false }
    }
    fun disconnect(conn: Conn) {
        if (prepared.get()?.conn === conn || authenticating.get()?.ssh === conn.ssh) abandonPreparation()
        owners.filterValues { it === conn }.keys.toList().forEach { key -> controllers.remove(key)?.close(); owners.remove(key) }
    }
    override fun close() { disposed = true; abandonPreparation(); controllers.values.toList().forEach { it.close() }; controllers.clear(); owners.clear() }
    companion object {
        private val identityScript = """
import json,os,sys
os.chdir(sys.argv[2])
engine=sys.argv[1]
home=os.path.expanduser('~')
runtime_home=os.environ.get('HERMES_HOME',os.path.join(home,'.hermes')) if engine=='hermes' else os.path.join(os.environ.get('GEMINI_CLI_HOME',home),'.gemini') if engine=='gemini' else os.path.join(home,'.grok')
print(json.dumps({'directory':os.getcwd(),'home':os.path.abspath(runtime_home)}))
""".trimIndent()
    }
}
