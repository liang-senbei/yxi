package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal interface AcpTransport : AutoCloseable {
    val output: InputStream
    suspend fun write(text: String): Boolean
}

/** ACP v1 JSON-RPC. Timeouts are unknown writes and are never retried here. */
internal class AcpClient(private val transport: AcpTransport) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val approvals = ConcurrentHashMap<String, JSONObject>()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val activePrompts = ConcurrentHashMap.newKeySet<String>()
    private val cancellingSessions = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean()
    private val writing = Mutex()
    private val incoming = Channel<JSONObject>(64)
    val events = incoming.receiveAsFlow()
    @Volatile private var initialized = false
    @Volatile var initialization: JSONObject? = null; private set

    init { scope.launch {
        try {
            transport.output.buffered().use { stream ->
                val line = ByteArrayOutputStream()
                while (isActive) {
                    val byte = stream.read()
                    if (byte < 0) break
                    if (byte != 10) { check(line.size() < 8 * 1024 * 1024) { "ACP 消息超过大小限制" }; line.write(byte); continue }
                    val raw = line.toString(Charsets.UTF_8); line.reset()
                    if (raw.isBlank()) continue
                    val message = JSONObject(raw)
                    check(message.optString("jsonrpc") == "2.0") { "ACP 返回了无法识别的协议数据" }
                    if (message.has("method")) {
                        if (message.has("id")) {
                            if (message.getString("method") != "session/request_permission") {
                                write(JSONObject().put("id", message.get("id")).put("error", JSONObject().put("code", -32601).put("message", "Client capability not supported")))
                                continue
                            }
                            check(approvals.size < 64)
                            val key = idKey(message.get("id"))
                            check(approvals.putIfAbsent(key, message) == null) { "ACP 重复审批标识" }
                            val sessionId = message.getJSONObject("params").getString("sessionId")
                            if (sessionId in cancellingSessions) { cancelPermission(key, sessionId); continue }
                        }
                        check(incoming.trySend(message).isSuccess) { "ACP 事件消费未跟上，请核对会话" }
                    } else {
                        val id = message.opt("id") as? String ?: continue
                        pending.remove(id)?.complete(message)
                    }
                }
            }
            shutdown(IllegalStateException("ACP 连接已关闭，未确认投递不会自动重发"))
        } catch (e: Exception) { shutdown(e) }
    } }

    suspend fun initialize(): JSONObject {
        check(!initialized)
        val result = request("initialize", JSONObject().put("protocolVersion", 1).put("clientCapabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "yxi").put("version", "1")))
        if (result.optInt("protocolVersion") != 1) { close(); error("运行器 ACP 版本不兼容") }
        initialization = result; initialized = true
        return result
    }
    suspend fun authenticate(methodId: String): JSONObject {
        check(initialized)
        val methods = initialization?.optJSONArray("authMethods") ?: JSONArray()
        require((0 until methods.length()).any { methods.getJSONObject(it).getString("id") == methodId }) { "请选择运行器提供的认证方式" }
        return request("authenticate", JSONObject().put("methodId", methodId), 120_000)
    }
    suspend fun newSession(directory: String): JSONObject {
        check(initialized)
        require(directory.isNotBlank() && directory.none { it < ' ' })
        return request("session/new", JSONObject().put("cwd", directory).put("mcpServers", JSONArray())).also {
            val id = it.getString("sessionId"); check(id.isNotBlank()); sessions.add(id)
        }
    }
    suspend fun prompt(sessionId: String, text: String, timeoutMillis: Long = 600_000): JSONObject {
        require(text.isNotBlank() && text.length <= 100_000)
        check(sessionId in sessions && activePrompts.add(sessionId)) { "会话未登记或仍有未确认轮次" }
        cancellingSessions.remove(sessionId)
        // A timeout leaves this session blocked until native state is reconciled by its owner.
        val result = request("session/prompt", JSONObject().put("sessionId", sessionId)
            .put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text))), timeoutMillis)
        check(result.getString("stopReason").isNotBlank()) { "缺少 ACP 轮次结束原因" }
        activePrompts.remove(sessionId)
        return result
    }
    suspend fun cancel(sessionId: String) {
        check(sessionId in sessions)
        writing.withLock {
            cancellingSessions.add(sessionId)
            writeLocked(JSONObject().put("method", "session/cancel").put("params", JSONObject().put("sessionId", sessionId)))
        }
        pendingPermissions().filter { it.getJSONObject("params").optString("sessionId") == sessionId }.forEach {
            cancelPermission(idKey(it.get("id")), sessionId)
        }
    }
    fun pendingPermissions(): List<JSONObject> = approvals.values.map { JSONObject(it.toString()) }
    suspend fun answerPermission(requestId: Any, sessionId: String, optionId: String?) = writing.withLock {
        val key = idKey(requestId)
        val request = approvals[key] ?: error("审批已处理或连接已失效")
        val params = request.getJSONObject("params")
        check(sessionId in sessions && params.getString("sessionId") == sessionId) { "审批不属于当前会话" }
        check(optionId == null || sessionId !in cancellingSessions) { "会话正在取消，不能继续批准" }
        val options = params.getJSONArray("options")
        require(optionId == null || (0 until options.length()).any { options.getJSONObject(it).getString("optionId") == optionId }) { "审批选项不在原生列表中" }
        check(approvals.remove(key, request))
        val outcome = JSONObject().put("outcome", if (optionId == null) "cancelled" else "selected").apply { optionId?.let { put("optionId", it) } }
        writeLocked(JSONObject().put("id", requestId).put("result", JSONObject().put("outcome", outcome)))
    }
    private suspend fun cancelPermission(key: String, sessionId: String) = writing.withLock {
        val request = approvals[key] ?: return@withLock
        check(request.getJSONObject("params").getString("sessionId") == sessionId)
        if (approvals.remove(key, request)) writeLocked(JSONObject().put("id", request.get("id")).put("result",
            JSONObject().put("outcome", JSONObject().put("outcome", "cancelled"))))
    }
    private suspend fun request(method: String, params: JSONObject, timeout: Long = 30_000): JSONObject {
        check(!closed.get()) { "ACP 连接已关闭" }
        val id = "yxi-" + UUID.randomUUID(); val response = CompletableDeferred<JSONObject>(); pending[id] = response
        try { return withTimeout(timeout) {
            write(JSONObject().put("id", id).put("method", method).put("params", params))
            val reply = response.await()
            reply.optJSONObject("error")?.let { throw IllegalStateException("ACP ${it.optInt("code")}: ${it.optString("message")}") }
            reply.getJSONObject("result")
        } } finally { pending.remove(id) }
    }
    private suspend fun write(message: JSONObject) = writing.withLock {
        writeLocked(message)
    }
    private suspend fun writeLocked(message: JSONObject) {
        check(!closed.get() && transport.write(message.put("jsonrpc", "2.0").toString() + "\n")) { "ACP 写入未确认，请核对会话" }
    }
    private fun shutdown(error: Exception) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(error) }; pending.clear(); approvals.clear()
        incoming.close(error); transport.close(); scope.cancel()
    }
    override fun close() = shutdown(IllegalStateException("ACP 连接已关闭"))
    companion object { private fun idKey(id: Any) = JSONArray().put(id).toString() }
}
