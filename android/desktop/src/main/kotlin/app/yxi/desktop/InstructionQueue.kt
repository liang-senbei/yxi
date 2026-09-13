package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class InstructionStatus { Local, Delivering, Unknown, Accepted, Cancelled, Resolved }
internal data class InstructionAttachment(val name: String, val remotePath: String)
internal data class QueuedInstruction(
    val id: String, val taskKey: String, val text: String,
    val attachments: List<InstructionAttachment> = emptyList(),
    val status: InstructionStatus = InstructionStatus.Local,
    val revision: Long = 0, val detail: String = "",
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

    init {
        try {
            val loaded = disk.read()?.let(::decode).orEmpty()
            val fromBackup = disk.recovered
            val recovered = loaded.map { if (it.status == InstructionStatus.Delivering || (fromBackup && it.status == InstructionStatus.Local))
                it.copy(status = InstructionStatus.Unknown, revision = it.revision + 1, detail = "应用退出时投递尚未确认，请核对任务记录") else it }
            if (recovered != loaded) disk.write(encode(recovered))
            entries = recovered
            if (fromBackup) error = "指令记录已从备份恢复，请核对任务状态"
        } catch (e: Exception) { readable = false; error = "无法读取指令记录，已保留原文件：${e.message}" }
    }

    @Synchronized private fun commit(next: List<QueuedInstruction>) {
        check(readable) { error }
        try { disk.write(encode(next)); entries = next; error = "" }
        catch (e: Exception) { error = "指令未保存：${e.message}"; throw e }
    }

    @Synchronized fun enqueue(taskKey: String, text: String, attachments: List<InstructionAttachment> = emptyList(), id: String = UUID.randomUUID().toString()): QueuedInstruction {
        val item = QueuedInstruction(id, taskKey, text, attachments.toList())
        entries.firstOrNull { it.id == id }?.let {
            check(it.taskKey == taskKey && it.text == text && it.attachments == attachments) { "同一指令标识对应不同内容" }
            return it
        }
        commit(entries + item)
        return item
    }

    @Synchronized private fun change(id: String, revision: Long, allowed: Set<InstructionStatus>, transform: (QueuedInstruction) -> QueuedInstruction): QueuedInstruction {
        val current = entries.single { it.id == id }
        check(current.revision == revision && current.status in allowed) { "指令状态已变化，请刷新后重试" }
        val next = transform(current).copy(revision = current.revision + 1)
        commit(entries.map { if (it.id == id) next else it })
        return next
    }
    fun edit(id: String, revision: Long, text: String) = change(id, revision, setOf(InstructionStatus.Local)) { it.copy(text = text) }
    fun cancel(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Local)) { it.copy(status = InstructionStatus.Cancelled) }
    fun restoreCancelled(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Cancelled)) {
        it.copy(status = InstructionStatus.Local, detail = "已撤销本地撤回，等待发送")
    }
    fun beginDelivery(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Local)) { current ->
        check(entries.firstOrNull { it.taskKey == current.taskKey && it.status !in setOf(InstructionStatus.Cancelled, InstructionStatus.Accepted, InstructionStatus.Resolved) }?.id == id) { "请先处理前面的指令" }
        current.copy(status = InstructionStatus.Delivering)
    }
    fun markUnknown(id: String, revision: Long, detail: String) = change(id, revision, setOf(InstructionStatus.Delivering)) { it.copy(status = InstructionStatus.Unknown, detail = detail) }
    fun notDelivered(id: String, revision: Long, reason: String) = change(id, revision, setOf(InstructionStatus.Delivering)) { it.copy(status = InstructionStatus.Local, detail = reason) }
    fun resolveManually(id: String, revision: Long) = change(id, revision, setOf(InstructionStatus.Unknown)) { it.copy(status = InstructionStatus.Resolved, detail = "用户已人工核对；未自动确认运行器接收") }
    fun confirmAccepted(id: String, revision: Long, receipt: String) = change(id, revision, setOf(InstructionStatus.Delivering, InstructionStatus.Unknown)) {
        require(receipt.isNotBlank()) { "缺少运行器接收凭据" }
        it.copy(status = InstructionStatus.Accepted, detail = receipt)
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

    companion object {
        private fun encode(items: List<QueuedInstruction>) = JSONObject().put("version", 1).put("items", JSONArray(items.map { item ->
            JSONObject().put("id", item.id).put("taskKey", item.taskKey).put("text", item.text).put("status", item.status.name)
                .put("revision", item.revision).put("detail", item.detail).put("attachments", JSONArray(item.attachments.map { JSONObject().put("name", it.name).put("remotePath", it.remotePath) }))
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
                    InstructionStatus.valueOf(item.getString("status")), item.getLong("revision"), item.getString("detail"))
                    .also { q ->
                        require(q.id.isNotBlank() && q.taskKey.isNotBlank() && q.revision >= 0)
                        require(q.text.isNotBlank() || q.attachments.isNotEmpty())
                        require(q.attachments.all { it.name.isNotBlank() && it.remotePath.startsWith('/') && '\u0000' !in it.remotePath })
                    }
            }
            require(result.map { it.id }.distinct().size == result.size)
            return result
        }
    }
}
