package app.yxi.desktop

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Persist before the first history mutation. A restart or a lost reply must not resume delivery. */
internal class RewindDeliveryGate(file: File) {
    data class Target(val sessionId: String, val anchorUuid: String, val messageUuid: String)
    data class Ticket(val taskKey: String, val runtimeId: String, val operationId: String, val target: Target? = null)
    private val disk = DurableFile(file) { decode(it) }
    private var readable = true
    private var tickets: List<Ticket> = try {
        // Never restore an older empty backup: it can predate an in-flight rewind.
        // In particular, do not rewrite the primary while reading; otherwise the
        // second application restart could mistake a restored empty file for safety.
        if (file.isFile) decode(file.readText()) else {
            check(!file.exists() && !File(file.parentFile, file.name + ".bak").exists())
            emptyList()
        }
    } catch (_: Exception) { readable = false; emptyList() }

    @Synchronized fun blocked(taskKey: String): Boolean = !readable || tickets.any { it.taskKey == taskKey }
    @Synchronized fun pending(taskKey: String): Ticket? = tickets.firstOrNull { it.taskKey == taskKey }

    /** Caller holds the connection delivery mutex while obtaining this ticket. */
    @Synchronized fun begin(taskKey: String, runtimeId: String, target: Target? = null): Ticket {
        check(readable) { "回退记录无法读取，发送已暂停，请先恢复记录" }
        require(taskKey.isNotBlank() && runtimeId.isNotBlank())
        check(!blocked(taskKey)) { "该会话的回退尚未确认，不能重复执行" }
        val ticket = Ticket(taskKey, runtimeId, UUID.randomUUID().toString(), target)
        commit(tickets + ticket)
        return ticket
    }

    /** Only call after the intended transcript AND running session have been verified. */
    @Synchronized fun finishVerified(ticket: Ticket) {
        check(readable && tickets.contains(ticket)) { "回退记录已变化，发送保持暂停" }
        commit(tickets.filterNot { it == ticket })
    }

    private fun commit(next: List<Ticket>) {
        disk.write(JSONArray().apply { next.forEach { t -> put(JSONObject()
            .put("task", t.taskKey).put("runtime", t.runtimeId).put("operation", t.operationId)
            .apply { t.target?.let { put("target", JSONObject().put("session", it.sessionId)
                .put("anchor", it.anchorUuid).put("message", it.messageUuid)) } }) } }.toString())
        tickets = next
    }

    private fun decode(text: String): List<Ticket> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            val target = it.optJSONObject("target")?.let { node ->
                Target(node.getString("session"), node.getString("anchor"), node.getString("message")).also { t ->
                    listOf(t.sessionId, t.anchorUuid, t.messageUuid).forEach(UUID::fromString)
                }
            }
            Ticket(it.getString("task"), it.getString("runtime"), it.getString("operation"), target).also { t ->
                require(t.taskKey.isNotBlank() && t.runtimeId.isNotBlank())
                UUID.fromString(t.operationId)
            }
        } }.also { require(it.map { t -> t.taskKey }.distinct().size == it.size) }
    }
}

internal object RewindDelivery {
    val gate by lazy { RewindDeliveryGate(File(Store.dir, "rewind-delivery.json")) }
}
