package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface ClaudeControlTransport : AutoCloseable {
    val output: InputStream
    suspend fun write(text: String): Boolean
}

/** Claude stream-json protocol, not ACP. Prompts require an explicit call; approvals are never automatic. */
internal class ClaudeControlClient(private val transport: ClaudeControlTransport) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val closed = AtomicBoolean()
    private val writing = Mutex()
    private val initialization = Mutex()
    private var initialized = false
    private val activeTurn = AtomicReference<CompletableDeferred<JSONObject>?>()
    private val completedResults = ConcurrentHashMap.newKeySet<String>()
    private val permissions = ConcurrentHashMap<String, JSONObject>()
    private val resolvedPermissions = ConcurrentHashMap.newKeySet<String>()
    @Volatile var sessionId: String? = null; private set
    val events = Channel<JSONObject>(64)
    init {
        scope.launch {
            try {
                transport.output.buffered().use { input ->
                    val line = ByteArrayOutputStream()
                    while (isActive) {
                        val byte = input.read()
                        if (byte < 0) break
                        if (byte != 10) { check(line.size() < 2 * 1024 * 1024) { "Claude 控制响应过大" }; line.write(byte); continue }
                        if (line.size() == 0) continue
                        val value = JSONObject(line.toString(Charsets.UTF_8)); line.reset()
                        when (value.optString("type")) {
                            "control_response" -> {
                                val response = value.getJSONObject("response")
                                val waiter = pending.remove(response.getString("request_id")) ?: continue
                                if (response.optString("subtype") == "success") waiter.complete(response.optJSONObject("response") ?: JSONObject())
                                else waiter.completeExceptionally(IllegalStateException("Claude 未完成控制请求；请核对运行器版本及原生配置"))
                            }
                            "control_request" -> {
                                val id = value.getString("request_id"); check(id.isNotBlank())
                                if (id in resolvedPermissions) continue
                                val request = value.getJSONObject("request")
                                check(request.optString("subtype") == "can_use_tool" && activeTurn.get() != null)
                                check(request.getString("tool_name").isNotBlank()); request.getJSONObject("input")
                                val previous = permissions.putIfAbsent(id, JSONObject(value.toString()))
                                if (previous == null) events.send(value) else check(previous.similar(value))
                            }
                            "control_cancel_request" -> {
                                val id = value.getString("request_id")
                                resolvedPermissions.add(id); permissions.remove(id); events.send(value)
                            }
                            "result" -> {
                                val parent = value.opt("parent_tool_use_id")
                                if (parent != null && parent != JSONObject.NULL) { events.send(value); continue }
                                val uuid = value.getString("uuid"); check(uuid.isNotBlank())
                                if (!completedResults.add(uuid)) continue
                                val turn = checkNotNull(activeTurn.get())
                                val nativeId = value.getString("session_id"); check(nativeId.isNotBlank())
                                check(sessionId == null || sessionId == nativeId) { "Claude 会话身份发生变化" }
                                sessionId = nativeId
                                resolvedPermissions.addAll(permissions.keys); permissions.clear()
                                events.send(value); turn.complete(value)
                            }
                            else -> events.send(value)
                        }
                    }
                }
            } catch (_: Exception) { /* Native payloads may contain credentials; do not surface raw parser errors. */ }
            finally { close() }
        }
    }
    suspend fun initialize(): JSONObject = initialization.withLock {
        check(!initialized) { "Claude 控制连接已初始化" }
        request("initialize").also { initialized = true }
    }
    suspend fun settings(): JSONObject {
        check(initialized) { "请先初始化 Claude 控制连接" }
        return request("get_settings")
    }
    fun pendingPermissions(): List<JSONObject> = permissions.values.map { JSONObject(it.toString()) }
    suspend fun answerPermission(id: String, allow: Boolean) = writing.withLock {
        check(!closed.get() && activeTurn.get() != null) { "Claude 审批所属轮次已结束" }
        val original = permissions.remove(id) ?: error("Claude 审批已处理或已失效")
        resolvedPermissions.add(id)
        val answer = if (allow) JSONObject().put("behavior", "allow").put("updatedInput", original.getJSONObject("request").getJSONObject("input"))
            else JSONObject().put("behavior", "deny").put("message", "用户拒绝此操作")
        try {
            check(transport.write(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
                .put("request_id", id).put("response", answer)).toString() + "\n")) { "Claude 审批答复未写入" }
        } catch (e: Exception) { close(); throw e }
    }
    suspend fun prompt(text: String, timeoutMillis: Long = 600_000): JSONObject {
        check(initialized && !closed.get()) { "Claude 控制连接尚未就绪" }
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
        val result = CompletableDeferred<JSONObject>()
        check(activeTurn.compareAndSet(null, result)) { "Claude 上一轮尚未结束" }
        try {
            return withTimeout(timeoutMillis) {
                writing.withLock {
                    check(!closed.get())
                    check(transport.write(JSONObject().put("type", "user").put("session_id", sessionId.orEmpty())
                        .put("parent_tool_use_id", JSONObject.NULL).put("uuid", UUID.randomUUID().toString())
                        .put("message", JSONObject().put("role", "user").put("content", text)).toString() + "\n")) { "Claude 提示词未写入" }
                }
                result.await()
            }
        } catch (e: Exception) { close(); throw e }
        finally { activeTurn.compareAndSet(result, null) }
    }
    private suspend fun request(subtype: String): JSONObject {
        check(!closed.get()) { "Claude 控制连接已关闭" }
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        try {
            return withTimeout(30000) {
                writing.withLock {
                    check(!closed.get())
                    check(transport.write(JSONObject().put("type", "control_request").put("request_id", id)
                        .put("request", JSONObject().put("subtype", subtype)).toString() + "\n")) { "Claude 控制请求未写入" }
                }
                response.await()
            }
        } catch (e: Exception) { close(); throw e }
        finally { pending.remove(id) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { transport.close() }
            pending.values.forEach { it.completeExceptionally(IllegalStateException("Claude 控制连接已关闭，未确认结果")) }
            pending.clear(); permissions.clear(); events.close(); scope.cancel()
            activeTurn.getAndSet(null)?.completeExceptionally(IllegalStateException("Claude 轮次结果未确认，连接已关闭"))
        }
    }
}
