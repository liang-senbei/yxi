package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject

internal data class CodexMessage(val id: String, val author: String, val text: String,
    val kind: String = "message", val status: String = "", val paths: List<String> = emptyList())
internal data class CodexModelOption(val model: String, val label: String, val efforts: List<String>, val defaultEffort: String)
internal data class CodexGoalState(val objective: String, val status: String, val seconds: Long, val tokens: Long, val budget: Long?)

/** One structured thread per controller. The owner must persist the threadId before exposing it.
 * User input stays in InstructionQueue; only correlated RPC replies can mark it accepted.
 */
internal class CodexTaskController(
    val taskKey: String,
    val threadId: String,
    private val client: CodexAppServer,
    private val queue: InstructionQueue,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val mutation = Mutex()
    private val terminalEvents = linkedMapOf<String, JSONObject>()
    val pendingRequests = mutableStateMapOf<String, JSONObject>()
    private val answerDrafts = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>()
    val answerDraftCount get() = answerDrafts.values.count { answers -> answers.values.any { it.isNotBlank() } }
    fun answersFor(requestId: Any) = answerDrafts.getOrPut(idKey(requestId)) { mutableStateMapOf() }
    private fun clearAnswers(requestId: Any) { answerDrafts.remove(idKey(requestId))?.clear() }
    val recentEvents = mutableStateListOf<JSONObject>()
    val messages = mutableStateListOf<CodexMessage>()
    var models by mutableStateOf<List<CodexModelOption>>(emptyList()); private set
    var selectedModel by mutableStateOf<String?>(null); private set
    var selectedEffort by mutableStateOf<String?>(null); private set
    var modelsLoading by mutableStateOf(false); private set
    var modelError by mutableStateOf(""); private set
    var reportedModel by mutableStateOf(""); private set
    var reportedProvider by mutableStateOf(""); private set
    var modelNotice by mutableStateOf(""); private set
    private var modelReportRevision = 0L
    var goal by mutableStateOf<CodexGoalState?>(null); private set
    var goalLoading by mutableStateOf(false); private set
    var goalError by mutableStateOf(""); private set
    private var goalRevision = 0L
    var ready by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var activeTurnId by mutableStateOf<String?>(null); private set
    var autoDispatch by mutableStateOf(false); private set
    var note by mutableStateOf("正在核对运行器任务状态"); private set
    private var disposed = false
    private var eventRevision = 0L

    init {
        require(taskKey.isNotBlank() && threadId.isNotBlank())
        scope.launch {
            try {
                client.events.collect { message ->
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val method = message.optString("method")
                    if (method == "serverRequest/resolved") {
                        params.opt("requestId")?.let { pendingRequests.remove(idKey(it)); clearAnswers(it) }
                    }
                    if (params.optString("threadId") != threadId) return@collect
                    eventRevision++
                    recentEvents.add(message)
                    if (recentEvents.size > 200) recentEvents.removeAt(0)
                    if (message.has("id") && !message.isNull("id")) {
                        pendingRequests[idKey(message.get("id"))] = message
                        note = "运行器正在等待处理请求"
                    }
                    when (method) {
                        "thread/goal/updated" -> { goalRevision++; receiveGoal(params.optJSONObject("goal")) }
                        "thread/goal/cleared" -> { goalRevision++; goal = null; goalError = "" }
                        "model/rerouted" -> {
                            modelReportRevision++
                            reportedModel = params.getString("toModel")
                            modelNotice = "运行器将本轮模型从 ${params.optString("fromModel")} 调整为 $reportedModel"
                        }
                        "item/started", "item/completed" -> params.optJSONObject("item")?.let { recordItem(it) }
                        "item/agentMessage/delta" -> {
                            val id = params.getString("itemId")
                            val previous = messages.firstOrNull { it.id == id }
                            putMessage(CodexMessage(id, "Codex", previous?.text.orEmpty() + params.getString("delta")))
                        }
                        "item/commandExecution/outputDelta", "item/fileChange/outputDelta" -> {
                            val id = params.getString("itemId")
                            val previous = messages.firstOrNull { it.id == id }
                            if (previous != null) putMessage(previous.copy(text = limitedOutput(previous.text + params.optString("delta"))))
                        }
                        "turn/started" -> activeTurnId = params.getJSONObject("turn").getString("id")
                        "turn/completed" -> {
                            val turn = params.getJSONObject("turn")
                            finish(turn, message.toString())
                            scheduleNext()
                        }
                        "thread/closed" -> { ready = false; autoDispatch = false; note = "运行器任务已关闭，请重新连接核对" }
                    }
                }
                ready = false; autoDispatch = false
                if (!disposed) note = "运行器连接已结束，未确认指令不会自动重发"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ready = false; autoDispatch = false; client.close(); note = "运行器状态未确认：${e.message}" }
        }
    }

    suspend fun reconcile(createdThread: JSONObject? = null) = mutation.withLock {
        ready = false
        val revision = eventRevision
        val modelRevision = modelReportRevision
        // Fresh thread/start already returns the authoritative empty thread. CLI 0.153.4
        // rejects includeTurns immediately after start (list_turns is not supported yet).
        // Do not turn that unsupported history request into a failed creation.
        if (createdThread != null) {
            check(createdThread.getString("id") == threadId && createdThread.getJSONArray("turns").length() == 0 &&
                createdThread.getJSONObject("status").getString("type") == "idle") { "新建任务初始状态未确认" }
            check(queue.entries.none { it.taskKey == taskKey }) { "已有指令的任务必须读取历史核对" }
        }
        val response = if (createdThread == null) client.readThread(threadId)
            else JSONObject().put("result", JSONObject().put("thread", createdThread))
        val thread = response.getJSONObject("result").getJSONObject("thread")
        check(thread.getString("id") == threadId) { "运行器返回了不同任务" }
        if (modelRevision == modelReportRevision) {
            reportedModel = thread.optString("model").takeUnless { it == "null" }.orEmpty()
            reportedProvider = thread.optString("modelProvider").takeUnless { it == "null" }.orEmpty()
        }
        val turns = thread.getJSONArray("turns")
        val streamedIds = messages.map { it.id }.toSet()
        val historical = mutableListOf<CodexMessage>()
        var running: String? = null
        for (index in 0 until turns.length()) {
            val turn = turns.getJSONObject(index)
            val items = turn.optJSONArray("items")
            if (items != null) for (itemIndex in 0 until items.length()) {
                decodeMessage(items.getJSONObject(itemIndex))?.let { historical.add(it) }
            }
            if (turn.optString("status") == "inProgress") running = turn.getString("id")
            else finish(turn, response.toString())
        }
        val live = messages.toList()
        messages.clear()
        messages.addAll(historical.map { old ->
            if (revision != eventRevision && old.id in streamedIds) live.first { it.id == old.id } else old
        })
        messages.addAll(live.filter { item -> historical.none { it.id == item.id } })
        // Never replace a more recent streaming state with an older read response.
        if (revision == eventRevision) activeTurnId = running
        ready = true
        note = if (activeTurnId == null) "任务已就绪" else "当前轮次仍在进行"
    }

    // JVM 签名避开属性 autoDispatch(private set) 的 setter（同为 setAutoDispatch(Z)V），Kotlin 调用侧不变
    @JvmName("setAutoDispatchFlag")
    fun setAutoDispatch(enabled: Boolean) {
        autoDispatch = enabled && ready && !disposed
        if (autoDispatch) scheduleNext()
    }

    private fun firstPending() = queue.entries.firstOrNull { it.taskKey == taskKey &&
        it.status !in setOf(InstructionStatus.Accepted, InstructionStatus.Cancelled, InstructionStatus.Resolved) }

    private fun scheduleNext() {
        if (!autoDispatch || !ready || disposed || sending || activeTurnId != null || pendingRequests.isNotEmpty()) return
        if (firstPending()?.status != InstructionStatus.Local) return
        scope.launch {
            try { sendNext() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { autoDispatch = false; note = e.message.orEmpty() }
        }
    }

    fun queueChanged() { scheduleNext() }

    suspend fun refreshGoal() {
        if (!ready || disposed || goalLoading) return
        goalLoading = true
        val revision = goalRevision
        try {
            val response = client.readGoal(threadId).getJSONObject("result")
            if (revision == goalRevision) receiveGoal(response.optJSONObject("goal"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { goalError = "目标状态暂不可用：${e.message}" }
        finally { goalLoading = false }
    }

    private fun receiveGoal(value: JSONObject?) {
        try {
            val next = value?.let {
                check(it.getString("threadId") == threadId) { "目标不属于当前任务" }
                CodexGoalState(it.getString("objective"), it.getString("status"),
                    it.getLong("timeUsedSeconds"), it.getLong("tokensUsed"),
                    if (it.isNull("tokenBudget")) null else it.getLong("tokenBudget"))
            }
            goal = next; goalError = ""
        } catch (e: Exception) { goalError = "目标状态无法读取：${e.message}" }
    }

    suspend fun refreshModels() {
        if (modelsLoading) return
        modelsLoading = true; modelError = ""
        try {
            val next = mutableListOf<CodexModelOption>()
            val seen = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val result = client.listModels(cursor).getJSONObject("result")
                val data = result.getJSONArray("data")
                for (index in 0 until data.length()) {
                    val item = data.getJSONObject(index)
                    if (item.optBoolean("hidden")) continue
                    val efforts = item.optJSONArray("supportedReasoningEfforts")
                    next.add(CodexModelOption(item.getString("model"), item.getString("displayName"),
                        if (efforts == null) emptyList() else (0 until efforts.length()).map { efforts.getJSONObject(it).getString("reasoningEffort") },
                        item.optString("defaultReasoningEffort")))
                }
                cursor = result.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
                check(cursor == null || seen.add(cursor)) { "模型列表分页异常" }
            } while (cursor != null)
            models = next.distinctBy { it.model }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { modelError = "模型列表读取失败：${e.message}" }
        finally { modelsLoading = false }
    }

    fun chooseModel(model: String?) {
        val option = model?.let { id -> models.firstOrNull { it.model == id } ?: error("模型不在服务器列表中") }
        selectedModel = option?.model
        selectedEffort = option?.let { entry -> entry.defaultEffort.takeIf { it in entry.efforts } }
    }

    fun chooseEffort(effort: String) {
        check(models.firstOrNull { it.model == selectedModel }?.efforts?.contains(effort) == true) { "该模型不支持此思考强度" }
        selectedEffort = effort
    }

    suspend fun sendNext(expectedId: String? = null) = mutation.withLock {
        check(ready && !disposed) { "请先连接并核对运行器状态" }
        check(activeTurnId == null && pendingRequests.isEmpty()) { "当前轮次或审批尚未结束" }
        val item = firstPending() ?: return@withLock
        check(expectedId == null || item.id == expectedId) { "队列顺序已改变，请重新选择" }
        check(item.status == InstructionStatus.Local) { "前一条指令尚未确认，不能自动重发或跳过" }
        submit(item, null)
    }

    suspend fun steerNext() = mutation.withLock {
        check(ready && !disposed && pendingRequests.isEmpty()) { "运行器尚未就绪或存在待处理审批" }
        val turnId = activeTurnId ?: error("没有可引导的活动轮次")
        val item = firstPending() ?: error("没有本地待发送指令")
        submit(item, turnId)
    }

    private suspend fun submit(item: QueuedInstruction, steeringTurn: String?) {
        CodexAppServer.userInput(item.text, item.attachments) // Validate before changing durable delivery state.
        val model = selectedModel
        val effort = selectedEffort
        val started = if (steeringTurn == null) queue.beginDelivery(item.id, item.revision)
            else queue.beginSteering(item.id, item.revision, steeringTurn)
        sending = true
        try {
            val response = if (steeringTurn == null) client.startTurn(threadId, item.text, item.attachments, model, effort)
                else client.steer(threadId, steeringTurn, item.text, item.attachments)
            val result = response.getJSONObject("result")
            val turnId = if (steeringTurn == null) result.getJSONObject("turn").getString("id") else result.getString("turnId")
            check(steeringTurn == null || steeringTurn == turnId) { "引导响应不属于目标轮次" }
            queue.confirmRuntimeAccepted(started.id, started.revision, turnId, response.toString())
            activeTurnId = turnId
            terminalEvents[turnId]?.let { finish(it, it.toString()) }
            if (steeringTurn == null) finish(result.getJSONObject("turn"), response.toString())
            if (activeTurnId != null) note = "运行器已接收"
        } catch (e: Exception) {
            autoDispatch = false
            // Even an RPC failure is conservatively retained until the controller can reconcile it.
            withContext(NonCancellable) {
                val current = queue.entries.firstOrNull { it.id == started.id }
                if (current?.status == InstructionStatus.Delivering)
                    queue.markUnknown(current.id, current.revision, "运行器响应未确认：${e.message}；不会自动重发")
            }
            note = "指令状态待确认，请核对后再继续"
            throw e
        } finally { sending = false }
        scheduleNext()
    }

    private fun finish(turn: JSONObject, receipt: String) {
        val outcome = when (turn.optString("status")) {
            "completed" -> RuntimeTurnState.Completed
            "failed" -> RuntimeTurnState.Failed
            "interrupted" -> RuntimeTurnState.Interrupted
            else -> return
        }
        val id = turn.getString("id")
        terminalEvents[id] = JSONObject().put("id", id).put("status", turn.getString("status"))
        if (terminalEvents.size > 32) terminalEvents.remove(terminalEvents.keys.first())
        queue.completeRuntimeTurn(taskKey, id, outcome, receipt)
        if (activeTurnId == id) activeTurnId = null
        if (outcome != RuntimeTurnState.Completed) {
            autoDispatch = false
            note = if (outcome == RuntimeTurnState.Failed) "本轮失败，自动派发已暂停" else "本轮已中断，自动派发已暂停"
        } else note = "轮次已结束"
    }

    suspend fun interrupt() {
        autoDispatch = false
        val id = activeTurnId ?: return
        client.interrupt(threadId, id)
        note = "已请求中断，等待运行器结束事件"
    }

    suspend fun answerRequest(id: Any, result: JSONObject) {
        check(ready && pendingRequests.containsKey(idKey(id))) { "请求已失效" }
        client.respond(id, result)
        pendingRequests.remove(idKey(id))
        clearAnswers(id)
        note = "已提交答复，等待运行器继续"
    }

    override fun close() {
        disposed = true; autoDispatch = false; ready = false
        client.close(); scope.cancel(); pendingRequests.clear()
        answerDrafts.values.forEach { it.clear() }; answerDrafts.clear()
    }
    private fun decodeMessage(item: JSONObject): CodexMessage? {
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        return when (item.optString("type")) {
            "agentMessage" -> CodexMessage(id, "Codex", item.optString("text"))
            "userMessage" -> {
                val content = item.optJSONArray("content")
                val text = if (content == null) "" else (0 until content.length()).mapNotNull { index ->
                    content.optJSONObject(index)?.let { part -> when (part.optString("type")) {
                        "text" -> part.optString("text")
                        "localImage" -> "[图片] ${part.optString("path")}"
                        "image" -> "[图片]"
                        else -> null
                    } }
                }.joinToString("\n")
                CodexMessage(id, "你", text)
            }
            "commandExecution" -> {
                val output = item.optString("aggregatedOutput").takeUnless { it == "null" }.orEmpty()
                val exit = if (item.has("exitCode") && !item.isNull("exitCode")) "\n退出码：${item.getInt("exitCode")}" else ""
                CodexMessage(id, "执行命令", item.optString("command") + "\n目录：" + item.optString("cwd") +
                    "\n" + limitedOutput(output) + exit, "command", item.optString("status"))
            }
            "fileChange" -> {
                val changes = item.optJSONArray("changes")
                val files = if (changes == null) emptyList() else (0 until changes.length()).mapNotNull { changes.optJSONObject(it) }
                CodexMessage(id, "文件改动", files.joinToString("\n\n") { it.optString("path") + "\n" + it.optString("diff") },
                    "files", item.optString("status"), files.map { it.optString("path") }.filter { it.isNotBlank() })
            }
            else -> null
        }
    }
    private fun recordItem(item: JSONObject) { decodeMessage(item)?.let(::putMessage) }
    private fun putMessage(message: CodexMessage) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) messages.add(message) else messages[index] = message
    }
    private fun limitedOutput(text: String) = if (text.length <= 65536) text else "[输出较长，仅显示末尾]\n" + text.takeLast(65536)
    private fun idKey(id: Any) = JSONObject().put("id", id).toString()
}
