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
 * 本控制器只透传原始事件（onRawEvent），不消费 current_mode_update。
 * 接回已有会话（[restoring]，本机对齐 P3）时先不能发送，[restore] 完成才就绪。自动排队（Code 风格的排队条）由属主按偏好打开，
 * 上一轮正常结束才接着发下一条；失败、取消、断开时暂停。经典不打开它，行为不变。 */
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
    restoring: Boolean = false,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val mutation = Mutex()
    private val eventsReady = CompletableDeferred<Unit>()
    private val agentText = StringBuilder()
    private var activeMessageId = ""
    val messages = mutableStateListOf<AcpMessage>()
    val pendingApprovals = mutableStateMapOf<String, JSONObject>()
    var ready by mutableStateOf(!restoring); private set
    var busy by mutableStateOf(false); private set
    var cancelling by mutableStateOf(false); private set
    var changingMode by mutableStateOf(false); private set
    var modes by mutableStateOf(client.modes(sessionId)); private set
    var models by mutableStateOf(client.models(sessionId)); private set
    var configOptions by mutableStateOf(client.configOptions(sessionId)); private set
    var lastStopReason by mutableStateOf(""); private set
    var note by mutableStateOf(if (restoring) "正在接回会话…" else "已连接 ACP 会话"); private set
    /** 自动排队：上一轮正常结束（end_turn）就发队首的下一条，同服务器工作台。 */
    var autoDispatch by mutableStateOf(false); private set
    private var disposed = false
    private var restorable = restoring
    private var preferenceApplied = false
    // 接回时的回放：同一作者连续的片段并成一段；段落在第一片就占位（保住和工具卡的先后），换段或回放结束时才写回全文
    private var replaying = false
    private var replayId = ""
    private var replayAuthor = ""
    private var replayMessageId = ""
    private val replayText = StringBuilder()
    private var replayCount = 0

    init {
        require(taskKey.isNotBlank() && sessionId.isNotBlank())
        scope.launch {
            try {
                client.consumeEvents(onReady = { eventsReady.complete(Unit) }) { event -> onRawEvent(event); receive(event) }
                ready = false; autoDispatch = false
                if (!disposed) note = "运行器连接已结束，未确认指令不会自动重发"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ready = false; autoDispatch = false; client.close(); note = "运行器状态未确认：${e.message}" }
            finally { eventsReady.completeExceptionally(IllegalStateException("ACP 事件处理器已退出")) }
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
            // 回放里的模式和配置是历史值，接回完成时再按回包读当前值
            "current_mode_update" -> if (!replaying) modes = client.modes(sessionId)
            "config_option_update" -> if (!replaying) {
                configOptions = client.configOptions(sessionId)
                onConfigurationChanged(configOptions)
            }
            "user_message_chunk" -> if (replaying) replayChunk("User", update)
            "agent_message_chunk" -> {
                if (replaying) { replayChunk("Assistant", update); return }
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
                // 回放里新出现的工具卡给前面的文字段落收尾，后面的文字另起一段，先后才对
                if (replaying && previous == null) flushReplay()
                val title = update.optString("title").takeIf { it.isNotBlank() }
                    ?: previous?.author.takeUnless { it.isNullOrBlank() }
                    ?: update.optString("kind").takeIf { it.isNotBlank() } ?: "工具"
                val text = toolText(update).ifBlank { previous?.text.orEmpty() }
                val status = update.optString("status").takeIf { it.isNotBlank() } ?: previous?.status.orEmpty()
                putMessage(AcpMessage(toolCallId, title, text, "tool", status))
            }
            // agent_thought_chunk / plan / available_commands_update 和实时轮次里的 user_message_chunk
            // 不进入会话视图：用户消息在发送时自己加，其余见限制说明。
        }
    }

    private fun putMessage(message: AcpMessage) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) messages.add(message) else messages[index] = message
    }

    private fun replayChunk(author: String, update: JSONObject) {
        val text = update.optJSONObject("content")?.optString("text").orEmpty()
        if (text.isEmpty()) return
        val messageId = update.optString("messageId")
        if (replayId.isNotEmpty() && (author != replayAuthor || messageId.isNotEmpty() && replayMessageId.isNotEmpty() && messageId != replayMessageId)) flushReplay()
        replayText.append(text)
        check(replayText.length <= 4_000_000) { "运行器回复过长，请核对原生会话" }
        if (replayId.isEmpty()) {
            replayId = "replay-${++replayCount}"; replayAuthor = author; replayMessageId = messageId
            messages.add(AcpMessage(replayId, author, text))
        } else if (replayMessageId.isEmpty()) replayMessageId = messageId
    }

    private fun flushReplay() {
        if (replayId.isEmpty()) return
        putMessage(AcpMessage(replayId, replayAuthor, replayText.toString()))
        replayId = ""; replayAuthor = ""; replayMessageId = ""; replayText.setLength(0)
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
        eventsReady.await()
        check(ready && !disposed && !busy && pendingApprovals.isEmpty()) { "当前会话暂不能切换模式" }
        changingMode = true
        try {
            client.setMode(sessionId, modeId)
            client.synchronizeEvents()
            modes = client.modes(sessionId)
            note = "会话模式已由运行器确认"
        } catch (e: Exception) {
            ready = false; autoDispatch = false; note = "模式切换未确认，请核对原生会话后再发送"
            throw e
        } finally { changingMode = false }
    }
    suspend fun changeModel(modelId: String) = mutation.withLock {
        eventsReady.await()
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
                ready = false; autoDispatch = false; note = "运行器已确认切换，但本地状态同步失败，请核对后再发送"
            } else { ready = false; autoDispatch = false; note = "模型切换未确认，请核对原生会话后再发送" }
            throw e
        } finally { changingMode = false }
    }
    suspend fun changeConfig(configId: String, value: String) = mutation.withLock {
        eventsReady.await()
        check(ready && !disposed && !busy && pendingApprovals.isEmpty()) { "当前会话暂不能修改配置" }
        changingMode = true
        try {
            client.setConfigOption(sessionId, configId, value)
            client.synchronizeEvents()
            configOptions = client.configOptions(sessionId)
            onConfigurationChanged(configOptions)
            note = "会话配置已由运行器确认"
        } catch (e: Exception) {
            ready = false; autoDispatch = false; note = "配置切换未确认，请核对原生会话后再发送"; throw e
        } finally { changingMode = false }
    }

    /** 接回已有会话（本机对齐 P3）：运行器声明了 loadSession 就 session/load，先回放整段对话再回包；只声明了 resume 就
     * session/resume，不回放。接回完成前不能发送；回包里的模式、模型和配置是当前值。只能调一次，失败由属主关掉后重连。 */
    suspend fun restore(directory: String) = mutation.withLock {
        check(restorable && !disposed) { "会话已接回或已关闭" }
        restorable = false
        eventsReady.await()
        val load = client.supportsLoadSession()
        check(load || client.supportsResume()) { "此运行器不支持继续已有会话，可以新建对话" }
        // resume 按规范不回放；真回放了也照样收下
        replaying = true
        try { if (load) client.loadSession(sessionId, directory) else client.resumeSession(sessionId, directory) }
        finally { flushReplay(); replaying = false }
        modes = client.modes(sessionId); models = client.models(sessionId); configOptions = client.configOptions(sessionId)
        if (configOptions.length() > 0) onConfigurationChanged(configOptions)
        models?.optString("currentModelId")?.takeIf { it.isNotBlank() }?.let(onModelChanged)
        ready = true
        note = if (load || messages.isNotEmpty()) "已接回会话" else "已接回会话；此运行器不回放历史，之前的内容请在原运行器查看"
    }

    // JVM 签名避开属性 autoDispatch(private set) 的 setter（同为 setAutoDispatch(Z)V），Kotlin 调用侧不变（同 CodexTaskController）
    @JvmName("setAutoDispatchFlag")
    fun setAutoDispatch(enabled: Boolean) {
        autoDispatch = enabled && ready && !disposed
        if (autoDispatch) scheduleNext()
    }
    /** 排队偏好每个连接只套用一次：之后用户在排队条上开关，或失败、取消、断开后暂停，都不再被偏好盖回去。没就绪时不算套用。 */
    fun applyQueuePreference(enabled: Boolean) {
        if (preferenceApplied || !ready || disposed) return
        preferenceApplied = true
        setAutoDispatch(enabled)
    }
    /** 队列变了（入队、调顺序、核对）：自动排队开着又空闲，就发队首。 */
    fun queueChanged() { scheduleNext() }
    private fun scheduleNext() {
        if (!autoDispatch || !ready || disposed || busy || pendingApprovals.isNotEmpty()) return
        if (firstPending()?.status != InstructionStatus.Local) return
        scope.launch {
            try { sendNext(automatic = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { autoDispatch = false; note = e.message.orEmpty() }
        }
    }

    /** 提交下一条本地指令并等待原生轮次结束。prompt 响应携带 stopReason，是唯一可接受的
     * 轮次回执；超时/断连一律记 Unknown，不重发。 */
    fun dispatchScheduled(id: String): Job = scope.launch {
        try { sendNext(id) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { note = e.message ?: "定时指令未确认" }
    }
    suspend fun sendNext(expectedId: String? = null, automatic: Boolean = false) = mutation.withLock {
        // 自动排队排在锁后面的那次：拿到锁时已经暂停或断开，就安静退出，不覆盖说明
        if (automatic && !(autoDispatch && ready && !disposed)) { autoDispatch = false; return@withLock }
        eventsReady.await()
        check(ready && !disposed) { "请先连接并核对运行器状态" }
        check(!busy) { "当前轮次尚未结束" }
        check(pendingApprovals.isEmpty()) { "运行器正在等待审批" }
        val item = firstPending() ?: return@withLock
        check(expectedId == null || item.id == expectedId) { "定时指令不在队首，本次未投递" }
        check(item.status == InstructionStatus.Local) { "前一条指令尚未确认，不能重发或跳过" }
        check(item.attachments.isEmpty()) { "ACP 附件输入尚未接入" }
        val started = queue.beginDelivery(item.id, item.revision)
        agentText.setLength(0)
        activeMessageId = "assistant-${started.id}"
        messages.add(AcpMessage("user-${started.id}", "User", started.text))
        busy = true
        lastStopReason = ""
        note = "正在等待运行器回复"
        var proceed = false
        try {
            val result = client.prompt(sessionId, started.text, promptTimeoutMillis)
            client.synchronizeEvents()
            val stopping = cancelling   // 先记下：finally 会清掉
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
            // 自动排队只在正常结束、又没在停止时接着发；否则暂停，等用户核对
            if (autoDispatch) {
                proceed = outcome == RuntimeTurnState.Completed && !stopping && ready
                if (!proceed) { autoDispatch = false; note += "；自动排队已暂停" }
            }
            notify(when (outcome) {
                RuntimeTurnState.Completed -> "本轮处理结束"
                RuntimeTurnState.Interrupted -> "本轮已取消"
                else -> "本轮需要核对"
            })
        } catch (e: TimeoutCancellationException) {
            autoDispatch = false
            // prompt 超时后 AcpClient 不释放该会话的发送权：状态未知且必须人工核对后重建连接。
            markUnknownPreserving(started, "prompt 超时，原生轮次状态未知；未自动重发")
            ready = false; note = "轮次超时未确认，请核对原生会话后重建连接"
            notify("投递结果未确认")
            throw e
        } catch (e: CancellationException) {
            autoDispatch = false
            markUnknownPreserving(started, "投递等待被取消，原生轮次状态未知；未自动重发")
            note = "投递结果未确认，请核对原生会话"
            notify("投递结果未确认")
            throw e
        } catch (e: Exception) {
            autoDispatch = false
            markUnknownPreserving(started, "投递结果未确认：${e.message}；不会自动重发")
            ready = false; note = "投递结果未确认，请核对原生会话"
            notify("投递结果未确认")
            throw e
        } finally { busy = false; cancelling = false }
        if (proceed) scheduleNext()
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
        cancelling = true; autoDispatch = false   // 停止也暂停自动排队（同服务器工作台的中断）
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
        disposed = true; ready = false; autoDispatch = false
        eventsReady.completeExceptionally(IllegalStateException("ACP 控制器已关闭"))
        client.close(); scope.cancel(); pendingApprovals.clear()
    }

    private fun idKey(id: Any) = JSONArray().put(id).toString()

}
