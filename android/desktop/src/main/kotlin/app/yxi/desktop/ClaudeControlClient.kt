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

internal interface ClaudeControlTransport : AutoCloseable {
    val output: InputStream
    suspend fun write(text: String): Boolean
}

/** Claude stream-json control protocol, not ACP. No user prompt or approval is sent by this client. */
internal class ClaudeControlClient(private val transport: ClaudeControlTransport) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val closed = AtomicBoolean()
    private val writing = Mutex()
    private val initialization = Mutex()
    private var initialized = false
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
                            "control_request" -> error("Claude 需要原生交互，尚未发送任务")
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
            pending.clear(); events.close(); scope.cancel()
        }
    }
}
