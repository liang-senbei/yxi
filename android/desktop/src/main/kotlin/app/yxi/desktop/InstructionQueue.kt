package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class InstructionStatus { Local, Delivering, Unknown, Sent, Accepted, Cancelled, Resolved }
internal enum class RuntimeTurnState { None, InProgress, Completed, Failed, Interrupted }
internal data class InstructionAttachment(val name: String, val remotePath: String)
internal data class QueuedInstruction(
    val id: String, val taskKey: String, val text: String,
    val attachments: List<InstructionAttachment> = emptyList(),
    val status: InstructionStatus = InstructionStatus.Local,
    val revision: Long = 0, val detail: String = "",
    val deliveryObservation: String = "", val deliveryObservedAt: Long = 0,
    val assignmentGroup: String = "", val assignmentHost: String = "", val sourceTask: String = "",
    val runtimeTurnId: String = "", val runtimeTurnState: RuntimeTurnState = RuntimeTurnState.None,
    val runtimeCompletion: String = "",
    val updatedAtMillis: Long = 0, val contentPurged: Boolean = false, val contentDigest: String = "",
)

/** Durable outbox, not a claim about a runner's remote queue. Network adapters must
 * persist beginDelivery before performing IO and supply an authoritative receipt
 * before confirming acceptance. Unknown deliveries are deliberately not retryable. */
internal class InstructionQueue(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    var entries by mutableStateOf<List<QueuedInstruction>>(emptyList())
        private set
    var error by mutableStateOf("")
        private set
    private var readable = true
    private var pendingBackupCleanup = false

    init {
        try {
            val loaded = disk.read()?.let(::decode).orEmpty()
            val fromBackup = disk.recovered
            val recovered = loaded.map { if (it.status == InstructionStatus.Delivering || (fromBackup && it.status == InstructionStatus.Local))
                it.copy(status = InstructionStatus.Unknown, revision = it.revision + 1, detail = "应用退出时投递尚未确认，请核对任务记录") else it }
            if (recovered != loaded) disk.write(encode(recovered))
            entries = recovered
            pendingBackupCleanup = recovered.any { it.contentPurged }
            if (fromBackup) error = "指令记录已从备份恢复，请核对任务状态"
        } catch (e: Exception) { readable = false; error = "无法读取指令记录，已保留原文件：${e.message}" }
    }

    @Synchronized private fun commit(next: List<QueuedInstruction>) {
        check(readable) { error }
        try { disk.write(encode(next)); entries = next; error = "" }
        catch (e: Exception) { error = "指令未保存：${e.message}"; throw e }
    }

    @Synchronized fun enqueue(taskKey: String, text: String, attachments: List<InstructionAttachment> = emptyList(), id: String = UUID.randomUUID().toString(), assignmentGroup: String = "", assignmentHost: String = "", sourceTask: String = ""): QueuedInstruction {
        val item = QueuedInstruction(id, taskKey, text, attachments.toList(), assignmentGroup = assignmentGroup, assignmentHost = assignmentHost, sourceTask = sourceTask, updatedAtMillis = System.currentTimeMillis())
        entries.firstOrNull { it.id == id }?.let {
            val sameContent = if (it.contentPurged) it.contentDigest == fingerprint(text, attachments) else it.text == text && it.attachments == attachments
            check(it.taskKey == taskKey && sameContent && it.assignmentGroup == assignmentGroup && it.assignmentHost == assignmentHost && it.sourceTask == sourceTask) { "同一指令标识对应不同内容" }
            return it
        }
        commit(entries + item)
        return item
    }

    @Synchronized private fun change(id: String, revision: Long, allowed: Set<InstructionStatus>, transform: (QueuedInstruction) -> QueuedInstruction): QueuedInstruction {
        val current = entries.single { it.id == id }
        check(current.revision == revision && current.status in allowed) { "指令状态已变化，请刷新后重试" }
        check(!current.contentPurged) { "正文已按保留设置清理，不能恢复或重新投递" }
        val next = transform(current).copy(revision = current.revision + 1, updatedAtMillis = System.currentTimeMillis())
        commit(entries.map { if (it.id == id) next else it })
        return next
    }
    fun edit(id: String, revision: Long, text: String) = change(id, revision, setOf(InstructionStatus.Local)) { it.copy(text = text) }
    fun cancel(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Local)) { it.copy(status = InstructionStatus.Cancelled) }
    fun restoreCancelled(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Cancelled)) {
        it.copy(status = InstructionStatus.Local, detail = "已撤销本地撤回，等待发送")
    }
    fun beginDelivery(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Local)) { current ->
        check(entries.none { it.taskKey == current.taskKey && it.runtimeTurnState == RuntimeTurnState.InProgress }) { "上一轮尚未结束，指令继续等待" }
        check(entries.firstOrNull { it.taskKey == current.taskKey && it.status !in setOf(InstructionStatus.Cancelled, InstructionStatus.Sent, InstructionStatus.Accepted, InstructionStatus.Resolved) }?.id == id) { "请先处理前面的指令" }
        current.copy(status = InstructionStatus.Delivering)
    }
    fun markUnknown(id: String, revision: Long, detail: String) = change(id, revision, setOf(InstructionStatus.Delivering)) { it.copy(status = InstructionStatus.Unknown, detail = detail) }
    fun confirmTerminalWrite(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Delivering, InstructionStatus.Unknown)) {
        it.copy(status = InstructionStatus.Sent, detail = "已投递到终端；回复以实际对话为准")
    }
    fun notDelivered(id: String, revision: Long, reason: String) = change(id, revision, setOf(InstructionStatus.Delivering)) { it.copy(status = InstructionStatus.Local, detail = reason) }
    fun resolveManually(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Unknown)) { it.copy(status = InstructionStatus.Resolved, detail = "用户已人工核对；未自动确认运行器接收") }
    fun recordDeliveryObservation(id: String, revision: Long, observation: String) = change(id, revision, setOf(InstructionStatus.Unknown)) {
        it.copy(deliveryObservation = observation, deliveryObservedAt = System.currentTimeMillis())
    }
    fun confirmAccepted(id: String, revision: Long, receipt: String) = change(id, revision, setOf(InstructionStatus.Delivering, InstructionStatus.Unknown)) {
        require(receipt.isNotBlank()) { "缺少运行器接收凭据" }
        it.copy(status = InstructionStatus.Accepted, detail = receipt)
    }

    /** Call only with a correlated runtime turn response, never a terminal-write acknowledgement. */
    fun confirmRuntimeAccepted(id: String, revision: Long, turnId: String, receipt: String) =
        change(id, revision, setOf(InstructionStatus.Delivering, InstructionStatus.Unknown)) {
            require(turnId.isNotBlank() && receipt.isNotBlank()) { "缺少运行器轮次 ID 或接收凭据" }
            it.copy(status = InstructionStatus.Accepted, detail = receipt, runtimeTurnId = turnId,
                runtimeTurnState = RuntimeTurnState.InProgress)
        }

    /** Explicit steering may join a known in-flight turn, but never jump over an uncertain delivery. */
    fun beginSteering(id: String, revision: Long, turnId: String) = change(id, revision, setOf(InstructionStatus.Local)) { current ->
        check(entries.any { it.taskKey == current.taskKey && it.runtimeTurnId == turnId && it.runtimeTurnState == RuntimeTurnState.InProgress }) { "目标轮次已变化，不能引导" }
        check(entries.firstOrNull { it.taskKey == current.taskKey && it.status !in setOf(InstructionStatus.Cancelled, InstructionStatus.Sent, InstructionStatus.Accepted, InstructionStatus.Resolved) }?.id == id) { "请先处理前面的指令" }
        current.copy(status = InstructionStatus.Delivering)
    }

    @Synchronized fun completeRuntimeTurn(taskKey: String, turnId: String, outcome: RuntimeTurnState, receipt: String, failure: String = ""): Boolean {
        require(outcome in setOf(RuntimeTurnState.Completed, RuntimeTurnState.Failed, RuntimeTurnState.Interrupted))
        require(turnId.isNotBlank() && receipt.isNotBlank())
        val matching = entries.filter { it.taskKey == taskKey && it.runtimeTurnId == turnId &&
            it.status == InstructionStatus.Accepted && it.runtimeTurnState == RuntimeTurnState.InProgress }.map { it.id }.toSet()
        if (matching.isEmpty()) return false
        commit(entries.map { if (it.id in matching) it.copy(runtimeTurnState = outcome,
            detail = when (outcome) { RuntimeTurnState.Failed -> failure.ifBlank { "运行器报告本轮失败" }; RuntimeTurnState.Interrupted -> "本轮已中断"; else -> it.detail },
            runtimeCompletion = receipt, revision = it.revision + 1, updatedAtMillis = System.currentTimeMillis()) else it })
        return true
    }
    @Synchronized fun moveBefore(id: String, revision: Long, beforeId: String) {
        val current = entries.single { it.id == id }
        val target = entries.single { it.id == beforeId }
        check(current.revision == revision && current.status == InstructionStatus.Local && target.status == InstructionStatus.Local && current.taskKey == target.taskKey && id != beforeId) { "只能调整同一任务的本地待发送指令" }
        val next = entries.filterNot { it.id == id }.toMutableList()
        next.add(next.indexOfFirst { it.id == beforeId }, current.copy(revision = current.revision + 1))
        commit(next)
    }

    @Synchronized fun moveAfter(id: String, revision: Long, afterId: String) {
        val current = entries.single { it.id == id }
        val target = entries.single { it.id == afterId }
        check(current.revision == revision && current.status == InstructionStatus.Local && target.status == InstructionStatus.Local && current.taskKey == target.taskKey && id != afterId) { "只能调整同一任务的本地待发送指令" }
        val next = entries.filterNot { it.id == id }.toMutableList()
        next.add(next.indexOfFirst { it.id == afterId } + 1, current.copy(revision = current.revision + 1))
        commit(next)
    }

    @Synchronized fun pruneCompleted(days: Int, now: Long = System.currentTimeMillis()): Int {
        require(days in setOf(0, 3, 7, 30))
        if (days == 0) return 0
        val cutoff = now - days * 86_400_000L
        val next = entries.map { item ->
            if (!item.contentPurged && terminal(item) && item.updatedAtMillis in 1L..cutoff) item.copy(
                text = "", attachments = emptyList(), detail = "", deliveryObservation = "", runtimeCompletion = "",
                contentPurged = true, contentDigest = fingerprint(item.text, item.attachments), revision = item.revision + 1)
            else item
        }
        val removed = next.count { it.contentPurged } - entries.count { it.contentPurged }
        if (removed > 0) {
            commit(next)
            pendingBackupCleanup = true
        }
        if (pendingBackupCleanup) {
            try { disk.write(encode(entries)); pendingBackupCleanup = false } // Rotate the ordinary backup too.
            catch (e: Exception) { error = "主记录已清理，但常规备份更新失败：${e.message}"; throw e }
        }
        return removed
    }

    companion object {
        private fun terminal(item: QueuedInstruction) = item.status in setOf(InstructionStatus.Cancelled, InstructionStatus.Resolved) ||
            (item.status == InstructionStatus.Accepted && item.runtimeTurnState in setOf(RuntimeTurnState.Completed, RuntimeTurnState.Failed, RuntimeTurnState.Interrupted))
        private fun fingerprint(text: String, attachments: List<InstructionAttachment>) = contentHash(JSONArray().put(text)
            .put(JSONArray(attachments.map { JSONArray().put(it.name).put(it.remotePath) })).toString().toByteArray())
        private fun encode(items: List<QueuedInstruction>) = JSONObject().put("version", 1).put("items", JSONArray(items.map { item ->
            JSONObject().put("id", item.id).put("taskKey", item.taskKey).put("text", item.text).put("status", item.status.name)
                .put("revision", item.revision).put("detail", item.detail)
                .put("updatedAtMillis", item.updatedAtMillis).put("contentPurged", item.contentPurged).put("contentDigest", item.contentDigest)
                .put("deliveryObservation", item.deliveryObservation).put("deliveryObservedAt", item.deliveryObservedAt)
                .put("assignmentGroup", item.assignmentGroup).put("assignmentHost", item.assignmentHost).put("sourceTask", item.sourceTask)
                .put("runtimeTurnId", item.runtimeTurnId).put("runtimeTurnState", item.runtimeTurnState.name).put("runtimeCompletion", item.runtimeCompletion)
                .put("attachments", JSONArray(item.attachments.map { JSONObject().put("name", it.name).put("remotePath", it.remotePath) }))
        })).toString(2)
        private fun decode(raw: String): List<QueuedInstruction> {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val items = root.getJSONArray("items")
            val result = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val attachments = item.getJSONArray("attachments")
                QueuedInstruction(item.getString("id"), item.getString("taskKey"), item.getString("text"),
                    (0 until attachments.length()).map { attachments.getJSONObject(it).let { a -> InstructionAttachment(a.getString("name"), a.getString("remotePath")) } },
                    InstructionStatus.valueOf(item.getString("status")), item.getLong("revision"), item.getString("detail"),
                    item.optString("deliveryObservation", ""), item.optLong("deliveryObservedAt", 0),
                    item.optString("assignmentGroup", ""), item.optString("assignmentHost", ""), item.optString("sourceTask", ""),
                    item.optString("runtimeTurnId", ""), RuntimeTurnState.valueOf(item.optString("runtimeTurnState", "None")), item.optString("runtimeCompletion", ""),
                    item.optLong("updatedAtMillis", 0), item.optBoolean("contentPurged", false), item.optString("contentDigest", ""))
                    .also { q ->
                        require(q.id.isNotBlank() && q.taskKey.isNotBlank() && q.revision >= 0)
                        require(q.runtimeTurnState == RuntimeTurnState.None || (q.runtimeTurnId.isNotBlank() && q.status == InstructionStatus.Accepted))
                        require(q.updatedAtMillis >= 0)
                        if (q.contentPurged) require(terminal(q) && q.text.isEmpty() && q.attachments.isEmpty() && Regex("[a-f0-9]{64}").matches(q.contentDigest))
                        else require(q.text.isNotBlank() || q.attachments.isNotEmpty())
                        require(q.attachments.all { it.name.isNotBlank() && it.remotePath.startsWith('/') && '\u0000' !in it.remotePath })
                    }
            }
            require(result.map { it.id }.distinct().size == result.size)
            return result
        }
    }
}

