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
internal class AcpRpcException(val code: Int, detail: String) : IllegalStateException("ACP $code: $detail")

/** ACP v1 JSON-RPC. Timeouts are unknown writes and are never retried here. */
internal class AcpClient(private val transport: AcpTransport) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val approvals = ConcurrentHashMap<String, JSONObject>()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionModes = ConcurrentHashMap<String, JSONObject>()
    private val sessionModels = ConcurrentHashMap<String, JSONObject>()
    private val notifiedModes = ConcurrentHashMap<String, String>()
    private val sessionConfigurations = ConcurrentHashMap<String, JSONArray>()
    private val activePrompts = ConcurrentHashMap.newKeySet<String>()
    private val cancellingSessions = ConcurrentHashMap.newKeySet<String>()
    // 正在接回（session/load 回放整段对话）的会话：期间事件通道背压，回放再长也不因消费暂时落后而断开
    private val loadingSessions = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean()
    private val writing = Mutex()
    private sealed interface Event {
        data class Message(val value: JSONObject) : Event
        data class Fence(val completion: CompletableDeferred<Unit>) : Event
    }
    private val incoming = Channel<Event>(64)
    private val consuming = AtomicBoolean()
    private val fences = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()
    suspend fun consumeEvents(onReady: () -> Unit = {}, handler: suspend (JSONObject) -> Unit) {
        check(consuming.compareAndSet(false, true)) { "ACP 事件只能由一个会话控制器消费" }
        try {
            check(!closed.get()) { "ACP 连接已关闭" }
            onReady()
            for (event in incoming) when (event) {
                is Event.Message -> handler(event.value)
                is Event.Fence -> event.completion.complete(Unit)
            }
        } finally { consuming.set(false) }
    }
    suspend fun synchronizeEvents() {
        check(consuming.get() && !closed.get()) { "ACP 事件处理器尚未就绪" }
        val completion = CompletableDeferred<Unit>()
        fences.add(completion)
        try { withTimeout(30_000) { incoming.send(Event.Fence(completion)); completion.await() } }
        finally { fences.remove(completion) }
    }
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
                        if (message.optString("method") == "session/update") {
                            val params = message.getJSONObject("params")
                            val update = params.getJSONObject("update")
                            if (update.optString("sessionUpdate") == "config_option_update") {
                                check(sessionConfigurations.size < 256 || sessionConfigurations.containsKey(params.getString("sessionId")))
                                val options = update.getJSONArray("configOptions")
                                acpConfigSelectors(options)
                                sessionConfigurations[params.getString("sessionId")] = JSONArray(options.toString())
                            }
                            if (update.optString("sessionUpdate") == "current_mode_update") {
                                val session = params.getString("sessionId")
                                val mode = update.getString("currentModeId")
                                check(session.isNotBlank() && mode.isNotBlank() && (notifiedModes.containsKey(session) || notifiedModes.size < 256))
                                notifiedModes[session] = mode
                                sessionModes.computeIfPresent(session) { _, previous -> JSONObject(previous.toString()).put("currentModeId", mode) }
                            }
                        }
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
                        if (loadingSessions.isEmpty()) check(incoming.trySend(Event.Message(message)).isSuccess) { "ACP 事件消费未跟上，请核对会话" }
                        else incoming.send(Event.Message(message))
                    } else {
                        val id = message.opt("id") as? String ?: continue
                        pending.remove(id)?.complete(message)
                    }
                }
            }
            shutdown(IllegalStateException("ACP 连接已关闭，未确认投递不会自动重发"))
        } catch (e: Exception) { shutdown(e) }
    } }

    suspend fun initialize(terminalAuthentication: Boolean = false): JSONObject {
        check(!initialized)
        val capabilities = JSONObject().apply { if (terminalAuthentication) put("auth", JSONObject().put("terminal", true)) }
        val result = request("initialize", JSONObject().put("protocolVersion", 1).put("clientCapabilities", capabilities)
            .put("clientInfo", JSONObject().put("name", "yxi").put("version", "1")))
        if (result.optInt("protocolVersion") != 1) { close(); error("运行器 ACP 版本不兼容") }
        initialization = result; initialized = true
        return result
    }
    suspend fun authenticate(methodId: String): JSONObject {
        check(initialized)
        val methods = initialization?.optJSONArray("authMethods") ?: JSONArray()
        val method = (0 until methods.length()).map { methods.getJSONObject(it) }.singleOrNull { it.getString("id") == methodId }
            ?: error("请选择运行器提供的唯一认证方式")
        require(method.optString("type", "agent") == "agent") { "此认证需要独立交互终端，不能通过普通登录请求执行" }
        return request("authenticate", JSONObject().put("methodId", methodId), 120_000)
    }
    suspend fun newSession(directory: String): JSONObject {
        check(initialized)
        require(directory.isNotBlank() && directory.none { it < ' ' })
        return request("session/new", JSONObject().put("cwd", directory).put("mcpServers", JSONArray())).also {
            val id = it.getString("sessionId"); check(id.isNotBlank()); sessions.add(id)
            it.optJSONObject("models")?.let { models -> sessionModels[id] = JSONObject(models.toString()) }
            it.optJSONArray("configOptions")?.let { options ->
                acpConfigSelectors(options); sessionConfigurations.putIfAbsent(id, JSONArray(options.toString()))
            }
            it.optJSONObject("modes")?.let { modes -> sessionModes.compute(id) { _, _ ->
                JSONObject(modes.toString()).apply { notifiedModes[id]?.let { current -> put("currentModeId", current) } }
            } }
        }
    }
    /** 运行器在 initialize 里声明了 loadSession（默认 false，没声明就不能调 session/load）。 */
    fun supportsLoadSession() = initialization?.optJSONObject("agentCapabilities")?.optBoolean("loadSession", false) == true
    /** 运行器声明了 sessionCapabilities.resume：能接回会话，但不回放历史。 */
    fun supportsResume() = initialization?.optJSONObject("agentCapabilities")?.optJSONObject("sessionCapabilities")?.optJSONObject("resume") != null
    /** 接回已有会话并回放历史：运行器先用 session/update 回放整段对话，回放完才回包。调用前必须已有人在消费事件。 */
    suspend fun loadSession(sessionId: String, directory: String): JSONObject {
        check(supportsLoadSession()) { "运行器没有声明可以接回会话" }
        return restoreSession("session/load", sessionId, directory, 120_000)
    }
    /** 接回已有会话但不回放（运行器只声明了 resume）。 */
    suspend fun resumeSession(sessionId: String, directory: String): JSONObject {
        check(supportsResume()) { "运行器没有声明可以继续会话" }
        return restoreSession("session/resume", sessionId, directory, 30_000)
    }
    private suspend fun restoreSession(method: String, sessionId: String, directory: String, timeout: Long): JSONObject {
        check(initialized)
        require(sessionId.isNotBlank() && sessionId.none { it < ' ' } && directory.isNotBlank() && directory.none { it < ' ' })
        check(sessionId !in sessions) { "会话已在此连接上" }
        check(consuming.get() && !closed.get()) { "ACP 事件处理器尚未就绪" }
        check(loadingSessions.add(sessionId)) { "会话正在接回" }
        try {
            val result = request(method, JSONObject().put("sessionId", sessionId).put("cwd", directory).put("mcpServers", JSONArray()), timeout, emptyResult = true)
            // 回包里的是当前值，覆盖回放里的旧值；回包没给的项保留回放留下的
            result.optJSONArray("configOptions")?.let { options -> acpConfigSelectors(options); sessionConfigurations[sessionId] = JSONArray(options.toString()) }
            result.optJSONObject("models")?.let { models -> sessionModels[sessionId] = JSONObject(models.toString()) }
            result.optJSONObject("modes")?.let { modes -> sessionModes[sessionId] = JSONObject(modes.toString()) }
            notifiedModes.remove(sessionId)
            sessions.add(sessionId)
            // 回放可能还有一截在通道里：消费完再解除背压，免得紧接着的实时事件把通道挤满
            synchronizeEvents()
            return result
        } finally { loadingSessions.remove(sessionId) }
    }
    fun modes(sessionId: String): JSONObject? = sessionModes[sessionId]?.let { JSONObject(it.toString()) }
    fun models(sessionId: String): JSONObject? = sessionModels[sessionId]?.let { JSONObject(it.toString()) }
    suspend fun setModel(sessionId: String, modelId: String, timeoutMillis: Long = 30_000) {
        check(sessionId in sessions)
        val previous = sessionModels[sessionId] ?: error("运行器没有提供模型列表")
        val available = previous.getJSONArray("availableModels")
        require((0 until available.length()).any { available.getJSONObject(it).getString("modelId") == modelId }) { "请选择原生模型列表中的模型" }
        check(activePrompts.add(sessionId)) { "会话仍有未确认操作" }
        try { request("session/set_model", JSONObject().put("sessionId", sessionId).put("modelId", modelId), timeoutMillis) }
        catch (e: AcpRpcException) {
            if (e.code in setOf(-32601, -32602)) activePrompts.remove(sessionId)
            throw e
        }
        sessionModels[sessionId] = JSONObject(previous.toString()).put("currentModelId", modelId)
        activePrompts.remove(sessionId)
    }
    fun configOptions(sessionId: String): JSONArray = sessionConfigurations[sessionId]?.let { JSONArray(it.toString()) } ?: JSONArray()
    suspend fun setConfigOption(sessionId: String, configId: String, value: String, timeoutMillis: Long = 30_000) {
        check(sessionId in sessions)
        val previous = sessionConfigurations[sessionId] ?: error("运行器没有提供配置选项")
        val selector = acpConfigSelectors(previous).singleOrNull { it.id == configId } ?: error("配置选项已失效")
        require(selector.values.any { it.id == value }) { "请选择运行器提供的值" }
        check(activePrompts.add(sessionId)) { "会话仍有未确认操作" }
        val result = request("session/set_config_option", JSONObject().put("sessionId", sessionId).put("configId", configId).put("value", value), timeoutMillis)
        val confirmed = result.getJSONArray("configOptions")
        acpConfigSelectors(confirmed)
        sessionConfigurations.compute(sessionId) { _, current -> if (current === previous) JSONArray(confirmed.toString()) else current }
        activePrompts.remove(sessionId)
    }

    suspend fun setMode(sessionId: String, modeId: String, timeoutMillis: Long = 30_000) {
        check(sessionId in sessions)
        val modes = sessionModes[sessionId] ?: error("运行器没有提供会话模式")
        val available = modes.getJSONArray("availableModes")
        require((0 until available.length()).any { available.getJSONObject(it).getString("id") == modeId }) { "请选择运行器提供的模式" }
        check(activePrompts.add(sessionId)) { "会话仍有未确认操作，暂不能切换模式" }
        // An ambiguous mode change blocks sending: the permission semantics may have changed.
        request("session/set_mode", JSONObject().put("sessionId", sessionId).put("modeId", modeId), timeoutMillis)
        // A native notification received while awaiting acknowledgement is more current than our request.
        sessionModes.computeIfPresent(sessionId) { _, current ->
            if (current === modes) JSONObject(current.toString()).put("currentModeId", modeId) else current
        }
        activePrompts.remove(sessionId)
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
    /** [emptyResult]：result 缺失或为 null 时当成空对象（session/load 和 session/resume 的回包可以是 null）。 */
    private suspend fun request(method: String, params: JSONObject, timeout: Long = 30_000, emptyResult: Boolean = false): JSONObject {
        check(!closed.get()) { "ACP 连接已关闭" }
        val id = "yxi-" + UUID.randomUUID(); val response = CompletableDeferred<JSONObject>(); pending[id] = response
        try { return withTimeout(timeout) {
            write(JSONObject().put("id", id).put("method", method).put("params", params))
            val reply = response.await()
            reply.optJSONObject("error")?.let { throw AcpRpcException(it.getInt("code"), it.optString("message")) }
            if (emptyResult && reply.isNull("result")) JSONObject() else reply.getJSONObject("result")
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
        fences.forEach { it.completeExceptionally(error) }; fences.clear()
        incoming.close(error); transport.close(); scope.cancel()
    }
    override fun close() = shutdown(IllegalStateException("ACP 连接已关闭"))
    companion object { private fun idKey(id: Any) = JSONArray().put(id).toString() }
}
