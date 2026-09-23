package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class NativeHistoryThread(val id: String, val title: String, val directory: String,
    val provider: String, val source: String, val updated: Long, val status: String)
internal data class NativeHistoryPage(val threads: List<NativeHistoryThread>, val next: String?)
internal data class NativeHistoryTurns(val turns: List<JSONObject>, val next: String?, val paginated: Boolean)
internal data class NativeAccountStatus(val label: String, val provider: String, val checkedAt: Long)

/** Read-only app-server client. No resume/start/fork/config-write/login method is exposed. */
internal class LocalCodexHistory private constructor(private val process: Process) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val serial = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val reader = Thread({
        try {
            process.inputStream.buffered().use { input ->
                while (!closed.get()) {
                    val bytes = ByteArrayOutputStream()
                    var eof = false
                    while (true) {
                        val ch = input.read()
                        if (ch == -1) { eof = true; break }
                        if (ch == 10) break
                        check(bytes.size() < 8 * 1024 * 1024) { "历史响应超过 8 MB，请使用支持分页的运行器" }
                        bytes.write(ch)
                    }
                    if (bytes.size() == 0) {
                        if (eof) break
                        // Empty lines are not protocol messages.
                        continue
                    }
                    val message = JSONObject(bytes.toString(Charsets.UTF_8))
                    if (message.has("method")) {
                        check(!message.has("id")) { "只读历史连接收到交互请求，已关闭；没有自动批准" }
                        continue
                    }
                    pending.remove(message.optString("id"))?.complete(message)
                }
            }
            fail("本地运行器已断开，点击刷新重新连接")
        } catch (e: Exception) { fail(e.message ?: "本地历史读取失败") }
    }, "yxi-local-history").apply { isDaemon = true; start() }

    private fun fail(message: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }
        close()
    }
    private suspend fun request(method: String, params: JSONObject): JSONObject {
        check(method in allowedMethods)
        check(!closed.get()) { "历史连接已关闭，请刷新" }
        val id = "yxi-read-${serial.incrementAndGet()}"
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            withContext(Dispatchers.IO) { synchronized(writer) {
                writer.write(JSONObject().put("id", id).put("method", method).put("params", params).toString()); writer.newLine(); writer.flush()
            } }
            val response = withTimeout(15000) { deferred.await() }
            response.optJSONObject("error")?.let { throw ReadFailure(it.optInt("code"), it.optString("message").take(300)) }
            return response.getJSONObject("result")
        } finally { pending.remove(id) }
    }

    suspend fun account(): NativeAccountStatus {
        val result = request("account/read", JSONObject().put("refreshToken", false))
        val account = result.optJSONObject("account")
        val label = when (account?.optString("type")) {
            "chatgpt" -> "官方订阅 · ChatGPT" + account.optString("planType").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "apiKey" -> "API Key 认证"
            "amazonBedrock" -> "Amazon Bedrock 认证"
            null -> "未检测到官方登录"
            else -> "认证类型暂不支持"
        }
        val provider = try { request("config/read", JSONObject().put("includeLayers", false))
            .optJSONObject("config")?.optString("model_provider")?.takeIf { it.isNotBlank() }.orEmpty() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { "无法确认" }
        return NativeAccountStatus(label, provider.ifBlank { "默认提供方" }, System.currentTimeMillis())
    }

    suspend fun list(archived: Boolean, query: String, cursor: String? = null): NativeHistoryPage {
        val result = request("thread/list", JSONObject().put("limit", 50).put("sortKey", "updated_at")
            .put("modelProviders", JSONArray()).put("sourceKinds", JSONArray(sourceKinds)).put("archived", archived)
            .apply { cursor?.let { put("cursor", it) }; if (query.isNotBlank()) put("searchTerm", query.trim()) })
        val rows = result.getJSONArray("data")
        return NativeHistoryPage((0 until rows.length()).map { i -> parseThread(rows.getJSONObject(i)) }, result.stringOrNull("nextCursor"))
    }

    suspend fun turns(thread: String, cursor: String? = null): NativeHistoryTurns {
        try {
            val result = request("thread/turns/list", JSONObject().put("threadId", thread).put("limit", 20)
                .put("sortDirection", "desc").put("itemsView", "full").apply { cursor?.let { put("cursor", it) } })
            val rows = result.getJSONArray("data")
            return NativeHistoryTurns((0 until rows.length()).map { rows.getJSONObject(it) }.reversed(), result.stringOrNull("nextCursor"), true)
        } catch (e: ReadFailure) {
            if (cursor != null || e.code !in setOf(-32601, -32600)) throw e
            val result = request("thread/read", JSONObject().put("threadId", thread).put("includeTurns", true)).getJSONObject("thread")
            val rows = result.optJSONArray("turns") ?: JSONArray()
            return NativeHistoryTurns((0 until rows.length()).map { rows.getJSONObject(it) }, null, false)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        LocalRuntimeDiscovery.stopOwnedProcess(process)
        pending.values.forEach { it.completeExceptionally(IllegalStateException("历史连接已关闭")) }; pending.clear()
    }
    internal class ReadFailure(val code: Int, message: String) : IllegalStateException("原生接口 $code：$message")
    companion object {
        private val allowedMethods = setOf("initialize", "account/read", "config/read", "thread/list", "thread/read", "thread/turns/list")
        internal val sourceKinds = listOf("cli", "vscode", "exec", "appServer", "subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown")
        internal fun parseThread(row: JSONObject) = NativeHistoryThread(row.getString("id"),
            row.stringOrNull("name") ?: row.stringOrNull("preview")?.take(100) ?: "未命名对话", row.optString("cwd"),
            row.optString("modelProvider"), when (val source = row.opt("source")) { is String -> source; is JSONObject -> "subAgent"; else -> "unknown" },
            row.optLong("updatedAt"), row.optJSONObject("status")?.optString("type").orEmpty())
        private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
        suspend fun connect(installation: LocalRuntimeInstallation): LocalCodexHistory {
            require(installation.engine == "codex" && installation.ready) { "请先选择验证通过的 Codex 入口" }
            var owned: Process? = null
            val process = try { withContext(Dispatchers.IO) {
                ProcessBuilder(installation.command + "app-server").directory(File(System.getProperty("user.home")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["CODEX_HOME"] = installation.home }.start().also { owned = it }
            } } catch (e: Exception) { owned?.let(LocalRuntimeDiscovery::stopOwnedProcess); throw e }
            val client = LocalCodexHistory(process)
            try {
                client.request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "yxi_local_history").put("version", "1"))
                    .put("capabilities", JSONObject().put("experimentalApi", true)))
                withContext(Dispatchers.IO) { synchronized(client.writer) {
                    client.writer.write("{\"method\":\"initialized\",\"params\":{}}\n"); client.writer.flush()
                } }
                return client
            } catch (e: Exception) { client.close(); throw e }
        }
    }
}
