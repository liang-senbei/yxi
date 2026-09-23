package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.SecureRandom

/** The owner persists session/model identity before constructing this controller. */
internal class OpenCodeTaskController(
    val taskKey: String, val sessionId: String, private val directory: String,
    private val providerId: String, private val modelId: String,
    private val client: OpenCodeClient, private val queue: InstructionQueue,
) {
    private val mutation = Mutex()
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var note by mutableStateOf(""); private set
    var messages by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var permissions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var questions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var nativeBusy by mutableStateOf(false); private set

    fun enqueue(text: String): QueuedInstruction {
        require(text.isNotBlank() && text.length <= 100_000)
        val time = (System.currentTimeMillis() * 4096).toString(16).padStart(12, '0').takeLast(12)
        val random = ByteArray(7).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return queue.enqueue(taskKey, text, id = "msg_$time$random")
    }

    suspend fun refresh() = mutation.withLock { refreshLocked() }
    private suspend fun refreshLocked() {
        ready = false
        val session = client.session(sessionId)
        check(session.getString("id") == sessionId && session.getString("directory") == directory) { "OpenCode 会话归属或工作目录已变化" }
        val history = client.messages(sessionId, 200)
        val fresh = (0 until history.length()).map { history.getJSONObject(it) }
        check(fresh.all { it.getJSONObject("info").getString("sessionID") == sessionId }) { "OpenCode 返回了其他会话的消息" }
        val pendingPermissions = client.permissions(sessionId)
        val pendingQuestions = client.questions(sessionId)
        val status = client.status().optJSONObject(sessionId)?.optString("type")
        nativeBusy = status != null && status != "idle"
        messages = fresh; permissions = pendingPermissions; questions = pendingQuestions
        for (item in queue.entries.filter { it.taskKey == taskKey && !it.contentPurged }) {
            val user = fresh.singleOrNull { it.getJSONObject("info").optString("id") == item.id && it.getJSONObject("info").optString("role") == "user" }
            if (user != null && item.status in setOf(InstructionStatus.Delivering, InstructionStatus.Unknown)) {
                val model = user.getJSONObject("info").getJSONObject("model")
                val parts = user.getJSONArray("parts")
                val texts = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.optString("type") == "text" && !it.optBoolean("synthetic") }
                check(texts.size == 1 && texts.single().getString("text") == item.text &&
                    model.getString("providerID") == providerId && model.getString("modelID") == modelId) { "原生消息与待发送内容不一致，请人工核对" }
                queue.confirmRuntimeAccepted(item.id, item.revision, item.id, "已读回 OpenCode 原生用户消息")
            }
            if (user != null && !nativeBusy && permissions.isEmpty() && questions.isEmpty()) {
                val last = fresh.filter { it.getJSONObject("info").optString("role") == "assistant" && it.getJSONObject("info").optString("parentID") == item.id }
                    .maxByOrNull { it.getJSONObject("info").getJSONObject("time").optLong("created") }?.getJSONObject("info")
                if (last != null && last.getJSONObject("time").optLong("completed") > 0) {
                    val failure = last.optJSONObject("error")
                    val outcome = when {
                        failure?.optString("name") == "MessageAbortedError" -> RuntimeTurnState.Interrupted
                        failure != null -> RuntimeTurnState.Failed
                        last.optString("finish") in setOf("stop", "length", "content-filter") -> RuntimeTurnState.Completed
                        else -> null
                    }
                    if (outcome != null) queue.completeRuntimeTurn(taskKey, item.id, outcome, "OpenCode 原生助手消息完成记录", failure?.optString("name").orEmpty())
                }
            }
        }
        ready = true
        note = when {
            permissions.isNotEmpty() -> "等待审批"
            questions.isNotEmpty() -> "等待回答"
            nativeBusy -> "正在运行"
            queue.entries.any { it.taskKey == taskKey && it.status == InstructionStatus.Unknown } -> "投递结果未确认，请核对历史；未自动重发"
            queue.entries.any { it.taskKey == taskKey && it.runtimeTurnState == RuntimeTurnState.InProgress } -> "尚未取得完成记录"
            else -> "就绪"
        }
    }

    suspend fun sendNext() = mutation.withLock {
        busy = true
        try {
            refreshLocked()
            check(!nativeBusy && permissions.isEmpty() && questions.isEmpty()) { "请先处理当前轮次" }
            val item = queue.entries.firstOrNull { it.taskKey == taskKey && it.status == InstructionStatus.Local } ?: return@withLock
            check(item.attachments.isEmpty()) { "OpenCode 附件输入尚未接入" }
            check(client.availableModels().any { it.providerId == providerId && it.modelId == modelId }) { "所选模型当前不可用，请核对供应商连接" }
            val sending = queue.beginDelivery(item.id, item.revision)
            try {
                client.send(sessionId, sending.text, providerId, modelId, sending.id)
                // 204 acknowledges submission, not a persisted message or completed model turn.
                queue.markUnknown(sending.id, sending.revision, "已提交，等待原生消息回读确认")
            } catch (e: Exception) {
                queue.markUnknown(sending.id, sending.revision, "投递中断，先核对原生历史；未自动重发")
                ready = false; note = "投递结果未确认，请核对历史"
                throw e
            }
            refreshLocked()
        } finally { busy = false }
    }

    suspend fun replyPermission(id: String, reply: OpenCodePermissionReply) = mutation.withLock { client.replyPermission(sessionId, id, reply); refreshLocked() }
    suspend fun replyQuestion(id: String, answers: List<List<String>>) = mutation.withLock { client.replyQuestion(sessionId, id, answers); refreshLocked() }
    suspend fun rejectQuestion(id: String) = mutation.withLock { client.rejectQuestion(sessionId, id); refreshLocked() }
    suspend fun abort() = mutation.withLock {
        check(client.abort(sessionId)) { "OpenCode 未确认停止" }
        refreshLocked()
    }
}
