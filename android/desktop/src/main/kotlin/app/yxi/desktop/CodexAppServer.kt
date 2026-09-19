package app.yxi.desktop

import app.yxi.ssh.SshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Codex app-server stdio over SSH. RPC IDs correlate responses; they are NOT idempotency keys.
 * Callers must persist their outbox before sending and never retry an uncertain write automatically.
 * Notifications and server approval requests remain raw events for the workspace controller.
 * Protocol: https://learn.chatgpt.com/docs/app-server
 */
internal class CodexAppServer private constructor(private val shell: SshSession.Shell) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val serverRequests = ConcurrentHashMap<String, JSONObject>()
    private val closed = AtomicBoolean(false)
    private val incoming = Channel<JSONObject>(256)
    val events = incoming.receiveAsFlow()

    init {
        scope.launch {
            try {
                shell.output.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        require(line.length <= 8 * 1024 * 1024) { "运行器事件超过大小限制" }
                        val message = JSONObject(line)
                        if (message.has("method")) {
                            if (message.has("id") && !message.isNull("id")) serverRequests[idKey(message.get("id"))] = message
                            incoming.send(message)
                        } else {
                            val id = message.opt("id") as? String ?: continue
                            pending.remove(id)?.complete(message)
                        }
                    }
                }
                shutdown(IllegalStateException("运行器连接已关闭；未确认请求不得自动重发"))
            } catch (e: Exception) { shutdown(e) }
        }
    }

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 30000): JSONObject {
        check(!closed.get()) { "运行器连接已关闭" }
        val id = "yxi-" + UUID.randomUUID().toString()
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        try {
            return withTimeout(timeoutMs) {
                check(shell.write(JSONObject().put("id", id).put("method", method).put("params", params).toString() + "\n")) {
                    "运行器写入未确认；不要自动重发"
                }
                val response = result.await()
                response.optJSONObject("error")?.let { error ->
                    throw RpcFailure(error.optInt("code"), error.optString("message", "运行器拒绝请求"))
                }
                require(response.has("result")) { "运行器响应缺少 result" }
                response
            }
        } finally { pending.remove(id) }
    }

    /** Respond only to a request actually received on this connection, and only after a user decision. */
    suspend fun respond(serverRequestId: Any, result: JSONObject) {
        val key = idKey(serverRequestId)
        check(!closed.get() && serverRequests.remove(key) != null) { "审批请求不存在、已处理或连接已失效" }
        check(withTimeout(10000) {
            shell.write(JSONObject().put("id", serverRequestId).put("result", result).toString() + "\n")
        }) { "审批响应写入未确认；请重新查询运行器状态" }
    }

    suspend fun startThread(directory: String): JSONObject {
        require(directory.startsWith('/') && '\u0000' !in directory)
        // Keep the server's configured approval, sandbox, model and provider defaults.
        return request("thread/start", JSONObject().put("cwd", directory))
    }

    suspend fun resumeThread(threadId: String) = request("thread/resume", JSONObject().put("threadId", requiredId(threadId)))

    suspend fun startTurn(threadId: String, text: String, attachments: List<InstructionAttachment> = emptyList(), model: String? = null, effort: String? = null): JSONObject = request("turn/start",
        JSONObject().put("threadId", requiredId(threadId)).put("input", userInput(text, attachments)).apply {
            model?.let { put("model", it) }; effort?.let { put("effort", it) }
        })

    suspend fun listModels(cursor: String? = null): JSONObject = request("model/list",
        JSONObject().put("limit", 100).put("includeHidden", false).apply { cursor?.let { put("cursor", it) } })

    suspend fun steer(threadId: String, turnId: String, text: String, attachments: List<InstructionAttachment> = emptyList()): JSONObject = request("turn/steer",
        JSONObject().put("threadId", requiredId(threadId)).put("expectedTurnId", requiredId(turnId)).put("input", userInput(text, attachments)))

    suspend fun interrupt(threadId: String, turnId: String): JSONObject = request("turn/interrupt",
        JSONObject().put("threadId", requiredId(threadId)).put("turnId", requiredId(turnId)))

    suspend fun readThread(threadId: String): JSONObject = request("thread/read",
        JSONObject().put("threadId", requiredId(threadId)).put("includeTurns", true))

    private fun shutdown(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        shell.close() // Unblocks the blocking reader as well as stopping outbound writes.
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear(); serverRequests.clear()
        incoming.close(error)
        scope.cancel()
    }

    override fun close() = shutdown(CancellationException("运行器连接已关闭"))

    class RpcFailure(val code: Int, message: String) : IllegalStateException(message)

    companion object {
        private fun idKey(id: Any) = JSONObject().put("id", id).toString()
        private fun requiredId(id: String) = id.also { require(it.isNotBlank()) { "缺少运行器任务或轮次 ID" } }
        internal fun userInput(text: String, attachments: List<InstructionAttachment>): JSONArray {
            require(text.isNotBlank() || attachments.isNotEmpty()) { "输入不能为空" }
            val input = JSONArray()
            if (text.isNotBlank()) input.put(JSONObject().put("type", "text").put("text", text))
            attachments.forEach { attachment ->
                require(attachment.remotePath.startsWith('/') && attachment.remotePath.none { it < ' ' }) { "附件路径无效" }
                require(attachment.remotePath.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")) { "此通道暂支持 PNG、JPEG 和 WebP 图片" }
                input.put(JSONObject().put("type", "localImage").put("path", attachment.remotePath))
            }
            return input
        }

        suspend fun connect(ssh: SshSession): CodexAppServer {
            val shell = ssh.openExecStream("""
bin=${'$'}(command -v codex || true)
if [ -z "${'$'}bin" ] && [ -x "${'$'}HOME/.local/bin/codex" ]; then bin="${'$'}HOME/.local/bin/codex"; fi
[ -n "${'$'}bin" ] || exit 127
exec "${'$'}bin" app-server
""".trimIndent())
            val client = CodexAppServer(shell)
            try {
                client.request("initialize", JSONObject().put("clientInfo", JSONObject()
                    .put("name", "yxi_desktop").put("title", "Yxi").put("version", "1.2.0")))
                check(withTimeout(10000) { shell.write(JSONObject().put("method", "initialized").put("params", JSONObject()).toString() + "\n") }) {
                    "运行器初始化确认未写入"
                }
                return client
            } catch (e: Exception) { client.close(); throw e }
        }
    }
}
