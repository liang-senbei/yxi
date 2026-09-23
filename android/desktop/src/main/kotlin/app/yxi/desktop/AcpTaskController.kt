package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject

internal data class AcpMessage(val id: String, val author: String, val text: String,
    val kind: String = "message", val status: String = "")

/** 单会话 ACP 任务控制器：一个实例独占收集一个 AcpClient 的 events（Channel 分发语义要求
 * 每个客户端只有一个收集者），按 sessionId 筛选后驱动流式消息与审批。指令正文仍以
 * InstructionQueue 为唯一持久事实：prompt 的 JSON-RPC 响应即轮次终止回执（stopReason），
 * 在拿到它之前投递状态一律视为未知，超时/断连不自动重发。会话模式同步由属主处理，
 * 本控制器只透传原始事件（onRawEvent），不消费 current_mode_update。 */
internal class AcpTaskController(
    val taskKey: String,
    val sessionId: String,
    private val client: AcpClient,
    private val queue: InstructionQueue,
    private val promptTimeoutMillis: Long = 600_000,
    private val onRawEvent: (JSONObject) -> Unit = {},
    private val onConfigurationChanged: (JSONArray) -> Unit = {},
    private val onModelChanged: (String) -> Unit = {},
    private val onNotification: (String) -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val mutation = Mutex()
    private val agentText = StringBuilder()
    private var activeMessageId = ""
    val messages = mutableStateListOf<AcpMessage>()
    val pendingApprovals = mutableStateMapOf<String, JSONObject>()
    var ready by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var cancelling by mutableStateOf(false); private set
    var changingMode by mutableStateOf(false); private set
    var modes by mutableStateOf(client.modes(sessionId)); private set
    var models by mutableStateOf(client.models(sessionId)); private set
    var configOptions by mutableStateOf(client.configOptions(sessionId)); private set
    var lastStopReason by mutableStateOf(""); private set
    var note by mutableStateOf("已连接 ACP 会话"); private set
    private var disposed = false

    init {
        require(taskKey.isNotBlank() && sessionId.isNotBlank())
        scope.launch {
            try {
                client.consumeEvents { event -> onRawEvent(event); receive(event) }
                ready = false
                if (!disposed) note = "运行器连接已结束，未确认指令不会自动重发"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ready = false; client.close(); note = "运行器状态未确认：${e.message}" }
        }
    }

    fun enqueue(text: String): QueuedInstruction {
        require(text.isNotBlank() && text.length <= 100_000)
        return queue.enqueue(taskKey, text)
    }
    fun dispatchNext(): Job = scope.launch {
        try { sendNext() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { note = e.message ?: "发送未确认，请核对会话" }
    }

    private fun receive(event: JSONObject) {
        val method = event.optString("method")
        val params = event.optJSONObject("params") ?: JSONObject()
        if (method == "session/request_permission") {
            if (params.optString("sessionId") != sessionId) return
            // The reader may have queued this event before cancel removed the native request.
            if (cancelling || client.pendingPermissions().none { idKey(it.get("id")) == idKey(event.get("id")) }) return
            val previous = pendingApprovals.put(idKey(event.get("id")), event)
            note = "运行器正在等待审批"
            if (previous == null) notify("等待批准")
            return
        }
        if (params.optString("sessionId") != sessionId || method != "session/update") return
        val update = params.optJSONObject("update") ?: return
        when (update.optString("sessionUpdate")) {
            "current_mode_update" -> modes = client.modes(sessionId)
            "config_option_update" -> {
                configOptions = client.configOptions(sessionId)
                onConfigurationChanged(configOptions)
            }
            "agent_message_chunk" -> {
                check(activeMessageId.isNotBlank()) { "收到未关联轮次的消息" }
                agentText.append(update.optJSONObject("content")?.optString("text").orEmpty())
                check(agentText.length <= 4_000_000) { "运行器回复过长，请核对原生会话" }
                putMessage(AcpMessage(activeMessageId, "Assistant", agentText.toString()))
                if (!cancelling && pendingApprovals.isEmpty()) note = "正在生成回复"
            }
            "tool_call", "tool_call_update" -> {
                val toolCallId = update.optString("toolCallId")
                if (toolCallId.isBlank()) return
                val previous = messages.firstOrNull { it.id == toolCallId }
                val title = update.optString("title").takeIf { it.isNotBlank() }
                    ?: previous?.author.takeUnless { it.isNullOrBlank() }
                    ?: update.optString("kind").takeIf { it.isNotBlank() } ?: "工具"
                val text = toolText(update).ifBlank { previous?.text.orEmpty() }
                val status = update.optString("status").takeIf { it.isNotBlank() } ?: previous?.status.orEmpty()
                putMessage(AcpMessage(toolCallId, title, text, "tool", status))
            }
            // user_message_chunk / agent_thought_chunk / plan / available_commands_update /
            // current_mode_update 不进入会话视图：模式同步归属主，其余见限制说明。
        }
    }

    private fun putMessage(message: AcpMessage) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) messages.add(message) else messages[index] = message
    }

    private fun toolText(update: JSONObject): String {
        val contents = update.optJSONArray("content") ?: return ""
        return (0 until contents.length()).mapNotNull { index ->
            contents.optJSONObject(index)?.let { item -> when (item.optString("type")) {
                "text" -> item.optString("text")
                "content" -> item.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.optString("text")
                "diff" -> "[改动] ${item.optString("path")}".trim()
                else -> null
            } }
        }.joinToString("\n")
    }

    private fun firstPending() = queue.entries.firstOrNull { it.taskKey == taskKey &&
        it.status !in setOf(InstructionStatus.Sent, InstructionStatus.Accepted, InstructionStatus.Cancelled, InstructionStatus.Resolved) }

    suspend fun changeMode(modeId: String) = mutation.withLock {
        check(ready && !disposed && !busy && pendingApprovals.isEmpty()) { "当前会话暂不能切换模式" }
        changingMode = true
        try {
            client.setMode(sessionId, modeId)
            client.synchronizeEvents()
            modes = client.modes(sessionId)
            note = "会话模式已由运行器确认"
        } catch (e: Exception) {
            ready = false; note = "模式切换未确认，请核对原生会话后再发送"
            throw e
        } finally { changingMode = false }
    }
    suspend fun changeModel(modelId: String) = mutation.withLock {
        check(ready && !disposed && !busy && pendingApprovals.isEmpty()) { "当前会话暂不能切换模型" }
        changingMode = true
        var acknowledged = false
        try {
            client.setModel(sessionId, modelId)
            acknowledged = true
            client.synchronizeEvents()
            models = client.models(sessionId)
            onModelChanged(checkNotNull(models).getString("currentModelId"))
            note = "模型已由运行器确认"
        } catch (e: Exception) {
            if (!acknowledged && (e is IllegalArgumentException || e is AcpRpcException && e.code in setOf(-32601, -32602))) {
                note = "所选模型未被接受，仍可使用当前模型"
            } else if (acknowledged) {
                ready = false; note = "运行器已确认切换，但本地状态同步失败，请核对后再发送"
            } else { ready = false; note = "模型切换未确认，请核对原生会话后再发送" }
            throw e
        } finally { changingMode = false }
    }
    suspend fun changeConfig(configId: String, value: String) = mutation.withLock {
        check(ready && !disposed && !busy && pendingApprovals.isEmpty()) { "当前会话暂不能修改配置" }
        changingMode = true
        try {
            client.setConfigOption(sessionId, configId, value)
            client.synchronizeEvents()
            configOptions = client.configOptions(sessionId)
            onConfigurationChanged(configOptions)
            note = "会话配置已由运行器确认"
        } catch (e: Exception) {
            ready = false; note = "配置切换未确认，请核对原生会话后再发送"; throw e
        } finally { changingMode = false }
    }

    /** 提交下一条本地指令并等待原生轮次结束。prompt 响应携带 stopReason，是唯一可接受的
     * 轮次回执；超时/断连一律记 Unknown，不重发。 */
    suspend fun sendNext() = mutation.withLock {
        check(ready && !disposed) { "请先连接并核对运行器状态" }
        check(!busy) { "当前轮次尚未结束" }
        check(pendingApprovals.isEmpty()) { "运行器正在等待审批" }
        val item = firstPending() ?: return@withLock
        check(item.status == InstructionStatus.Local) { "前一条指令尚未确认，不能重发或跳过" }
        check(item.attachments.isEmpty()) { "ACP 附件输入尚未接入" }
        val started = queue.beginDelivery(item.id, item.revision)
        agentText.setLength(0)
        activeMessageId = "assistant-${started.id}"
        messages.add(AcpMessage("user-${started.id}", "User", started.text))
        busy = true
        lastStopReason = ""
        note = "正在等待运行器回复"
        try {
            val result = client.prompt(sessionId, started.text, promptTimeoutMillis)
            client.synchronizeEvents()
            lastStopReason = result.optString("stopReason")
            val (outcome, failure) = decodeStop(lastStopReason)
            val receipt = result.toString()
            queue.confirmRuntimeAccepted(started.id, started.revision, started.id, receipt)
            queue.completeRuntimeTurn(taskKey, started.id, outcome, receipt, failure)
            note = when (outcome) {
                RuntimeTurnState.Completed -> "轮次已结束"
                RuntimeTurnState.Interrupted -> "本轮已取消"
                RuntimeTurnState.Failed -> "本轮以「$lastStopReason」结束，请核对结果"
                else -> "轮次结束原因未识别：$lastStopReason"
            }
            try {
                client.pendingPermissions().filter { it.getJSONObject("params").optString("sessionId") == sessionId }.forEach {
                    client.answerPermission(it.get("id"), sessionId, null)
                }
                pendingApprovals.clear()
            } catch (_: Exception) {
                pendingApprovals.clear(); ready = false; client.close()
                note = "轮次已结束，但旧审批清理未确认，请核对连接"
            }
            notify(when (outcome) {
                RuntimeTurnState.Completed -> "本轮处理结束"
                RuntimeTurnState.Interrupted -> "本轮已取消"
                else -> "本轮需要核对"
            })
        } catch (e: TimeoutCancellationException) {
            // prompt 超时后 AcpClient 不释放该会话的发送权：状态未知且必须人工核对后重建连接。
            markUnknownPreserving(started, "prompt 超时，原生轮次状态未知；未自动重发")
            ready = false; note = "轮次超时未确认，请核对原生会话后重建连接"
            notify("投递结果未确认")
            throw e
        } catch (e: CancellationException) {
            markUnknownPreserving(started, "投递等待被取消，原生轮次状态未知；未自动重发")
            note = "投递结果未确认，请核对原生会话"
            notify("投递结果未确认")
            throw e
        } catch (e: Exception) {
            markUnknownPreserving(started, "投递结果未确认：${e.message}；不会自动重发")
            ready = false; note = "投递结果未确认，请核对原生会话"
            notify("投递结果未确认")
            throw e
        } finally { busy = false; cancelling = false }
    }

    private suspend fun markUnknownPreserving(started: QueuedInstruction, detail: String) {
        // 即便调用方协程正在取消，也要把持久状态落到 Unknown，避免重启后被当作可重发。
        withContext(NonCancellable) {
            val current = queue.entries.firstOrNull { it.id == started.id }
            if (current?.status == InstructionStatus.Delivering)
                queue.markUnknown(current.id, current.revision, detail)
        }
    }

    private fun notify(title: String) { if (!disposed) runCatching { onNotification(title) } }

    /** stopReason → 队列轮次结论。end_turn 之外的一切非取消原因（含 max_tokens、refused、
     * max_turn_requests、auth_required 及未来新增的未识别值）都按失败保留回执，不猜成功。 */
    private fun decodeStop(stopReason: String): Pair<RuntimeTurnState, String> = when (stopReason) {
        "end_turn" -> RuntimeTurnState.Completed to ""
        "cancelled" -> RuntimeTurnState.Interrupted to ""
        else -> RuntimeTurnState.Failed to "运行器停止原因：$stopReason"
    }

    /** 请求取消在途轮次。刻意不加 mutation 锁：必须能在 sendNext 持锁等待时打断它。
     * 只发送 session/cancel 并等待 prompt 以原生 stopReason 返回收尾；不在本地标记 Interrupted。 */
    suspend fun cancelTurn() {
        if (disposed || !busy || cancelling) return
        cancelling = true
        client.cancel(sessionId)
        // AcpClient.cancel 会把该会话未决审批代答为 cancelled，本地展示同步清空。
        pendingApprovals.clear()
        note = "已请求取消，等待原生停止原因"
    }

    /** 以原生选项答复审批（optionId=null 表示显式取消该审批，二者都不自动触发）。 */
    suspend fun answerPermission(requestId: Any, optionId: String?) {
        val key = idKey(requestId)
        check(pendingApprovals.containsKey(key)) { "审批已处理或已失效" }
        check(!cancelling) { "会话正在取消，不能继续批准" }
        client.answerPermission(requestId, sessionId, optionId)
        pendingApprovals.remove(key)
        note = "已提交审批答复，等待运行器继续"
    }

    override fun close() {
        disposed = true; ready = false
        client.close(); scope.cancel(); pendingApprovals.clear()
    }

    private fun idKey(id: Any) = JSONArray().put(id).toString()

}
