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

internal data class CodexResumeOverrides(val provider: String?, val model: String?, val effort: String?)

/** Codex app-server stdio over SSH or an owned local process. RPC IDs are NOT idempotency keys.
 * Callers must persist their outbox before sending and never retry an uncertain write automatically.
 * Notifications and server approval requests remain raw events for the workspace controller.
 * Protocol: https://learn.chatgpt.com/docs/app-server
 */
internal class CodexAppServer internal constructor(private val transport: CodexTransport,
    private val profileOverrides: CodexResumeOverrides? = null,
    private val providerModelLoader: (suspend () -> List<ProviderModels.Model>)? = null,
    internal val profileLabel: String? = null) : AutoCloseable {
    internal constructor(shell: SshSession.Shell, profileOverrides: CodexResumeOverrides? = null,
        providerModelLoader: (suspend () -> List<ProviderModels.Model>)? = null, profileLabel: String? = null) :
        this(SshCodexTransport(shell), profileOverrides, providerModelLoader, profileLabel)
    internal val hasIndependentProfile get() = profileOverrides != null
    internal var beforeLocalMutation: (suspend (String, JSONObject) -> Unit)? = null
    internal suspend fun independentModels(): List<String> {
        check(!closed.get() && providerModelLoader != null) { "独立配置连接已关闭" }
        return providerModelLoader.invoke().map { it.id }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val serverRequests = ConcurrentHashMap<String, JSONObject>()
    private val closed = AtomicBoolean(false)
    private val incoming = Channel<JSONObject>(256)
    val events = incoming.receiveAsFlow()

    init {
        scope.launch {
            try {
                transport.output.bufferedReader(Charsets.UTF_8).use { reader ->
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

    suspend fun authenticationSummary(): String {
        val result = request("account/read", JSONObject().put("refreshToken", false)).getJSONObject("result")
        require(result.opt("requiresOpenaiAuth") is Boolean) { "运行器认证响应格式无法识别" }
        require(!result.has("account") || result.isNull("account") || result.opt("account") is JSONObject) { "运行器认证响应格式无法识别" }
        return when (result.optJSONObject("account")?.optString("type")) {
            "chatgpt" -> "运行器已保存 ChatGPT 登录"
            "apiKey" -> "运行器使用 API Key 认证"
            "amazonBedrock" -> "运行器使用 Amazon Bedrock 认证"
            null -> if (result.optBoolean("requiresOpenaiAuth", true)) "运行器尚未登录 OpenAI 账号" else "当前提供方不要求 OpenAI 登录"
            else -> "运行器返回了暂不支持的认证类型"
        }
    }

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 30000): JSONObject {
        check(!closed.get()) { "运行器连接已关闭" }
        if (transport.local && method in setOf("thread/start", "thread/resume", "thread/fork", "turn/start", "turn/steer")) beforeLocalMutation?.invoke(method, params)
        val id = "yxi-" + UUID.randomUUID().toString()
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        try {
            return withTimeout(timeoutMs) {
                check(transport.write(JSONObject().put("id", id).put("method", method).put("params", params).toString() + "\n")) {
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
            transport.write(JSONObject().put("id", serverRequestId).put("result", result).toString() + "\n")
        }) { "审批响应写入未确认；请重新查询运行器状态" }
    }

    suspend fun startThread(directory: String): JSONObject {
        require(validDirectory(directory))
        // Keep the server's configured approval, sandbox, model and provider defaults.
        return request("thread/start", JSONObject().put("cwd", directory).apply { applyProfile(this, profileOverrides) })
    }

    suspend fun resumeThread(threadId: String, overrides: CodexResumeOverrides? = null) = request("thread/resume",
        JSONObject().put("threadId", requiredId(threadId)).apply {
            applyProfile(this, overrides ?: profileOverrides)
        })

    internal fun conversationOverrides(model: String?, effort: String?): CodexResumeOverrides? =
        if (profileOverrides == null && model == null && effort == null) null
        else CodexResumeOverrides(profileOverrides?.provider, model ?: profileOverrides?.model, effort ?: profileOverrides?.effort)

    private fun applyProfile(params: JSONObject, overrides: CodexResumeOverrides?) {
        overrides?.provider?.let { params.put("modelProvider", it) }
        overrides?.model?.let { params.put("model", it) }
        overrides?.effort?.let { params.put("config", JSONObject().put("model_reasoning_effort", it)) }
    }

    internal fun verifyProfile(result: JSONObject, verifyModel: Boolean = true) {
        val expected = profileOverrides ?: return
        check(result.optString("modelProvider") == expected.provider &&
            (!verifyModel || expected.model == null || result.optString("model") == expected.model)) {
            "运行器返回的供应商或模型与所选独立配置不一致；未启用发送"
        }
    }

    suspend fun readResumeOverrides(directory: String): CodexResumeOverrides {
        require(validDirectory(directory)) { "项目目录无效" }
        profileOverrides?.let { return it }
        val config = request("config/read", JSONObject().put("cwd", directory).put("includeLayers", false))
            .getJSONObject("result").getJSONObject("config")
        fun value(key: String) = config.optString(key).takeIf { it.isNotBlank() && it != "null" }
        // Only return non-secret effective fields; never log or persist the full config response.
        return CodexResumeOverrides(value("model_provider"), value("model"), value("model_reasoning_effort"))
    }

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

    suspend fun readGoal(threadId: String): JSONObject = request("thread/goal/get",
        JSONObject().put("threadId", requiredId(threadId)))

    private fun shutdown(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        transport.close() // Unblocks the blocking reader as well as stopping outbound writes.
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear(); serverRequests.clear()
        incoming.close(error)
        scope.cancel()
    }

    override fun close() = shutdown(CancellationException("运行器连接已关闭"))

    private fun validDirectory(path: String) = path.none { it < ' ' } &&
        if (transport.local) java.io.File(path).isAbsolute && java.io.File(path).isDirectory else path.startsWith('/')

    internal suspend fun initializeLocal() {
        check(transport.local)
        request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "yxi_desktop_local").put("version", "1")))
        check(transport.write("{\"method\":\"initialized\",\"params\":{}}\n")) { "本地运行器初始化未确认" }
    }

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
                if (attachment.remotePath.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
                    input.put(JSONObject().put("type", "localImage").put("path", attachment.remotePath))
                } else {
                    val reference = JSONObject().put("name", attachment.name).put("path", attachment.remotePath)
                    input.put(JSONObject().put("type", "text").put("text",
                        "用户上传的服务器文件（内容未内嵌，请按任务需要使用文件工具读取）：\n$reference"))
                }
            }
            return input
        }

        suspend fun connect(ssh: SshSession, profile: app.yxi.agent.Lines.Line? = null, profileScope: String? = null): CodexAppServer {
            val prepared = profile?.let { CodexProfileLaunch.prepare(ssh, it, profileScope) }
            val shell = try { ssh.openExecStream(prepared?.command ?: """
bin=${'$'}(command -v codex || true)
if [ -z "${'$'}bin" ] && [ -x "${'$'}HOME/.local/bin/codex" ]; then bin="${'$'}HOME/.local/bin/codex"; fi
[ -n "${'$'}bin" ] || exit 127
exec "${'$'}bin" app-server
""".trimIndent()) } catch (e: Exception) {
                if (prepared != null) CodexProfileLaunch.cleanup(ssh, prepared)
                throw e
            }
            val client = CodexAppServer(shell, prepared?.overrides,
                profile?.let { line -> suspend { ProviderModels.fetch(ssh, line.baseUrl, line.apiKey, line.modelsUrl) } }, profile?.name)
            try {
                client.request("initialize", JSONObject().put("clientInfo", JSONObject()
                    .put("name", "yxi_desktop").put("title", "Yxi").put("version", System.getProperty("jpackage.app-version", "dev"))))
                check(withTimeout(10000) { shell.write(JSONObject().put("method", "initialized").put("params", JSONObject()).toString() + "\n") }) {
                    "运行器初始化确认未写入"
                }
                return client
            } catch (e: Exception) { client.close(); throw e }
            finally { if (prepared != null) CodexProfileLaunch.cleanup(ssh, prepared) }
        }
    }
}
