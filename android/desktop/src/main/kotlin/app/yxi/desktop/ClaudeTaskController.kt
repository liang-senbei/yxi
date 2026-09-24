package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class ClaudeMessage(val id: String, val role: String, val text: String, val kind: String = "message")

/** Owns the event consumer and durable outbox for a previously prepared native connection. */
internal class ClaudeTaskController(val taskKey: String, private val client: ClaudeControlClient, private val queue: InstructionQueue,
    private val beforeSend: suspend () -> Unit = { ClaudeSubscriptionSettings.requireOfficialRoute(client.settings()) },
    private val promptTimeoutMillis: Long = 600_000,
    private val onNotification: (String) -> Unit = {},
    initialModel: String = "",
    val availableModels: List<String> = emptyList(),
    private val onModelChanged: (String) -> Unit = {},
    val history: List<app.yxi.agent.ChatItem> = emptyList(),
    private val modelEfforts: Map<String, List<String>> = emptyMap(), initialEffort: String? = null) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val mutation = Mutex()
    private val rendered = mutableMapOf<String, CompletableDeferred<Unit>>()
    val messages = mutableStateListOf<ClaudeMessage>()
    val pendingApprovals = mutableStateMapOf<String, JSONObject>()
    var ready by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var cancelling by mutableStateOf(false); private set
    var model by mutableStateOf(initialModel); private set
    var effort by mutableStateOf(initialEffort); private set
    val effortLevels get() = modelEfforts[model].orEmpty()
    var changingModel by mutableStateOf(false); private set
    private var preparing = false
    private var stopInFlight = false
    var note by mutableStateOf("已连接 Claude 会话"); private set
    private var disposed = false
    init {
        require(taskKey.isNotBlank())
        scope.launch {
            try { for (event in client.events) receive(event) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { ready = false; note = "Claude 消息处理失败，请核对原生会话"; client.close() }
            finally {
                val wasReady = ready
                ready = false
                rendered.values.forEach { it.completeExceptionally(IllegalStateException("Claude 消息尚未处理完成")) }
                if (!disposed && wasReady) note = "Claude 连接已结束，未确认指令不会自动重发"
            }
        }
    }
    suspend fun selectModel(value: String): Unit = withContext(Dispatchers.Swing) {
        check(ready && !disposed && !busy && !cancelling && !changingModel && pendingApprovals.isEmpty()) { "请等待当前操作结束后再切换模型" }
        require(value in availableModels) { "模型不在当前运行器返回的列表中" }
        if (value == model) return@withContext
        changingModel = true
        try {
            val settings = client.setModel(value)
            ClaudeSubscriptionSettings.requireOfficialRoute(settings)
            val actual = settings.getJSONObject("applied").getString("model")
            onModelChanged(actual)
            model = actual
            effort = settings.getJSONObject("applied").optString("effort").takeIf { it in effortLevels }
            note = "当前模型：$actual"
        } catch (e: Exception) {
            ready = false; client.close(); note = "模型切换未确认，请核对原生配置"
            throw e
        } finally { changingModel = false }
    }
    suspend fun selectEffort(value: String): Unit = withContext(Dispatchers.Swing) {
        check(ready && !disposed && !busy && !cancelling && !changingModel && pendingApprovals.isEmpty()) { "请等待当前操作结束后再修改思考强度" }
        require(value in effortLevels) { "当前模型未声明支持此思考强度" }
        if (effort == value) return@withContext
        changingModel = true
        try {
            val settings = client.setEffort(value)
            ClaudeSubscriptionSettings.requireOfficialRoute(settings)
            check(settings.getJSONObject("applied").getString("model") == model) { "修改思考强度时模型发生变化" }
            effort = value; note = "思考强度：${effortName(value)}"
        } catch (e: Exception) {
            ready = false; client.close(); note = "思考强度未确认，请核对原生配置"
            throw e
        } finally { changingModel = false }
    }
    fun enqueue(text: String): QueuedInstruction {
        require(text.isNotBlank() && text.length <= 100_000)
        return queue.enqueue(taskKey, text)
    }
    private fun receive(event: JSONObject) {
        when (event.optString("type")) {
            "control_request" -> {
                if (cancelling) return
                val id = event.getString("request_id")
                if (client.pendingPermissions().none { it.getString("request_id") == id }) return
                val previous = pendingApprovals.put(id, event)
                note = "Claude 正在等待审批"
                if (previous == null) notify("等待批准")
            }
            "control_cancel_request" -> pendingApprovals.remove(event.getString("request_id"))
            "assistant", "user" -> {
                val content = event.optJSONObject("message")?.optJSONArray("content") ?: return
                val base = event.optString("uuid").ifBlank { "event-${messages.size}" }
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    val type = block.optString("type")
                    val role = if (event.optString("type") == "assistant") "Assistant" else "Tool"
                    val text = when (type) {
                        "text" -> if (role == "Assistant") block.optString("text") else continue
                        "tool_use" -> block.optString("name") + "\n" + block.optJSONObject("input").toString()
                        "tool_result" -> block.opt("content")?.toString().orEmpty()
                        else -> continue
                    }
                    messages.add(ClaudeMessage("$base:$index", role, text, type))
                }
            }
            "result" -> {
                pendingApprovals.clear()
                rendered.getOrPut(event.getString("uuid")) { CompletableDeferred() }.complete(Unit)
            }
        }
    }
    suspend fun answerPermission(id: String, allow: Boolean) {
        check(!cancelling) { "Claude 正在停止，不能继续批准" }
        check(pendingApprovals.containsKey(id)) { "审批已处理或已失效" }
        try { client.answerPermission(id, allow) }
        finally { if (client.pendingPermissions().none { it.getString("request_id") == id }) pendingApprovals.remove(id) }
    }
    suspend fun cancelTurn() {
        if (!busy || cancelling || disposed) return
        cancelling = true; pendingApprovals.clear()
        note = "已请求停止，等待 Claude 原生结束回执"
        if (preparing) { client.close(); return }
        stopInFlight = true
        try { if (!client.interrupt() && busy) cancelling = false }
        catch (e: Exception) { ready = false; client.close(); throw e }
        finally { stopInFlight = false; if (!busy) cancelling = false }
    }
    fun dispatchNext(): Job = scope.launch { try { sendNext() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    suspend fun sendNext(): Unit = withContext(Dispatchers.Swing) { mutation.withLock {
        check(ready && !disposed && !busy && !cancelling && !changingModel && pendingApprovals.isEmpty()) { "Claude 当前不能接收新指令" }
        val item = queue.entries.firstOrNull { it.taskKey == taskKey && it.status !in setOf(InstructionStatus.Cancelled, InstructionStatus.Sent, InstructionStatus.Accepted, InstructionStatus.Resolved) }
            ?: return@withLock
        check(item.status == InstructionStatus.Local) { "前一条指令尚未确认，请先核对" }
        check(item.attachments.isEmpty()) { "Claude 附件输入尚未接入" }
        busy = true; preparing = true
        var started: QueuedInstruction? = null
        var receipt: JSONObject? = null
        try {
            beforeSend()
            check(!cancelling) { "发送前已停止" }; preparing = false
            started = queue.beginDelivery(item.id, item.revision)
            messages.add(ClaudeMessage("user-${item.id}", "User", item.text))
            note = "正在等待 Claude 回复"
            val result = client.prompt(item.text, promptTimeoutMillis); receipt = result
            val turn = result.getString("uuid")
            withTimeout(5000) { rendered.getOrPut(turn) { CompletableDeferred() }.await() }; rendered.remove(turn)
            val outcome = when {
                cancelling && result.optString("terminal_reason") == "aborted_streaming" -> RuntimeTurnState.Interrupted
                result.opt("is_error") == false && result.optString("subtype") == "success" -> RuntimeTurnState.Completed
                else -> RuntimeTurnState.Failed
            }
            queue.confirmRuntimeAccepted(item.id, started.revision, turn, result.toString())
            check(queue.completeRuntimeTurn(taskKey, turn, outcome, result.toString(), if (outcome == RuntimeTurnState.Failed) "Claude 返回未成功结束的轮次" else ""))
            notify(when (outcome) { RuntimeTurnState.Completed -> "任务完成"; RuntimeTurnState.Interrupted -> "任务已停止"; else -> "任务未成功" })
            note = when (outcome) { RuntimeTurnState.Completed -> "本轮处理结束"; RuntimeTurnState.Interrupted -> "本轮已停止"; else -> "本轮未成功，请核对结果" }
        } catch (e: Exception) {
            ready = false; client.close()
            note = if (started == null && cancelling) "发送前已停止，指令仍在本地"
                else if (started == null) "发送前检查未通过，指令仍在本地：${e.message.orEmpty()}"
                else if (receipt != null) "已收到结束回执，但本地记录未完成，请核对结果" else "投递结果未确认，不会自动重发"
            withContext(NonCancellable) {
                val current = queue.entries.singleOrNull { it.id == item.id }
                if (current?.status == InstructionStatus.Delivering) runCatching { queue.markUnknown(current.id, current.revision, note) }
            }
            notify(if (started == null) "发送未完成" else if (receipt != null) "结果记录未完成" else "投递结果未确认")
            throw e
        } finally { busy = false; preparing = false; if (!stopInFlight) cancelling = false }
    } }
    private fun notify(title: String) { if (!disposed) runCatching { onNotification(title) } }
    override fun close() { disposed = true; ready = false; client.close(); scope.cancel(); pendingApprovals.clear() }
}
