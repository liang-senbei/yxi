package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.agent.SupportApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class SupportSendState { Editing, Sending, Unknown, Confirmed }
internal data class SupportDraft(val id: String, val owner: String, val ticketId: String?, val category: String = "",
    val text: String = "", val version: String = "", val device: String = "", val revision: Long = 0,
    val state: SupportSendState = SupportSendState.Editing, val detail: String = "")

/** Local drafts and uncertain submission records, isolated by account and ticket.
 * A successful begin() is required before network IO. Never infer a retry from
 * a restarted process or from an older backup. */
internal class SupportDrafts(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    var entries by mutableStateOf<List<SupportDraft>>(emptyList())
        private set
    var error by mutableStateOf("")
        private set
    private var readable = true
    init {
        try {
            val loaded = disk.read()?.let(::decode).orEmpty()
            val backup = disk.recovered
            val recovered = loaded.map { if (it.state == SupportSendState.Sending || (backup && it.state == SupportSendState.Editing))
                it.copy(state = SupportSendState.Unknown, revision = it.revision + 1, detail = "提交结果尚未确认，请先查看工单列表") else it }
            if (loaded != recovered) disk.write(encode(recovered))
            entries = recovered
            if (backup) error = "工单草稿已从备份恢复，请核对提交记录"
        } catch (e: Exception) { readable = false; error = "工单草稿无法读取，原文件已保留：${e.message}" }
    }
    @Synchronized private fun commit(next: List<SupportDraft>) {
        check(readable) { error }
        try { disk.write(encode(next)); entries = next; error = "" }
        catch (e: Exception) { error = "工单草稿未保存：${e.message}"; throw e }
    }
    @Synchronized fun open(owner: String, ticketId: String? = null): SupportDraft {
        require(owner.isNotBlank() && (ticketId == null || ticketId.isNotBlank()))
        entries.lastOrNull { it.owner == owner && it.ticketId == ticketId && it.state != SupportSendState.Confirmed }?.let { return it }
        val draft = SupportDraft(UUID.randomUUID().toString(), owner, ticketId)
        commit(entries + draft)
        return draft
    }
    @Synchronized private fun change(id: String, revision: Long, allowed: Set<SupportSendState>, block: (SupportDraft) -> SupportDraft): SupportDraft {
        val current = entries.single { it.id == id }
        check(current.revision == revision && current.state in allowed) { "工单草稿状态已变化，请重新查看" }
        val next = block(current).copy(revision = current.revision + 1)
        commit(entries.map { if (it.id == id) next else it })
        return next
    }
    fun edit(id: String, revision: Long, text: String, category: String, version: String, device: String) =
        change(id, revision, setOf(SupportSendState.Editing)) { it.copy(text = text, category = category, version = version, device = device, detail = "") }
    fun begin(id: String, revision: Long) = change(id, revision, setOf(SupportSendState.Editing)) {
        SupportApi.validateText(it.text)
        if (it.ticketId == null) require(it.category in SupportApi.CATEGORIES) { "请选择工单分类" }
        require(it.version.codePointCount(0, it.version.length) <= 64 && it.device.codePointCount(0, it.device.length) <= 64)
        it.copy(state = SupportSendState.Sending, detail = "")
    }
    fun confirmed(id: String, revision: Long, receipt: String) = change(id, revision, setOf(SupportSendState.Sending)) {
        require(receipt.isNotBlank()); it.copy(state = SupportSendState.Confirmed, detail = receipt)
    }
    fun failed(id: String, revision: Long, failure: SupportApi.Failure) = change(id, revision, setOf(SupportSendState.Sending)) {
        it.copy(state = if (failure.uncertain) SupportSendState.Unknown else SupportSendState.Editing, detail = failure.message.orEmpty())
    }
    /** Explicit user reconciliation; callers must not invoke this automatically. */
    fun reconcile(id: String, revision: Long, foundReceipt: String?) = change(id, revision, setOf(SupportSendState.Unknown)) {
        require(foundReceipt == null || foundReceipt.isNotBlank())
        it.copy(state = if (foundReceipt == null) SupportSendState.Editing else SupportSendState.Confirmed,
            detail = foundReceipt ?: "用户已核对尚未提交，可继续编辑")
    }
    companion object {
        private fun encode(items: List<SupportDraft>) = JSONObject().put("version", 1).put("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("owner", it.owner).put("ticketId", it.ticketId ?: JSONObject.NULL)
                .put("category", it.category).put("text", it.text).put("version", it.version).put("device", it.device)
                .put("revision", it.revision).put("state", it.state.name).put("detail", it.detail)
        })).toString(2)
        private fun decode(raw: String): List<SupportDraft> {
            val root = JSONObject(raw); require(root.getInt("version") == 1)
            val array = root.getJSONArray("items")
            val items = (0 until array.length()).map { array.getJSONObject(it).let { item ->
                SupportDraft(item.getString("id"), item.getString("owner"), if (item.isNull("ticketId")) null else item.getString("ticketId"),
                    item.getString("category"), item.getString("text"), item.getString("version"), item.getString("device"),
                    item.getLong("revision"), SupportSendState.valueOf(item.getString("state")), item.getString("detail"))
                    .also { require(it.id.isNotBlank() && it.owner.isNotBlank() && it.revision >= 0 && (it.ticketId == null || it.ticketId.isNotBlank())) }
            } }
            require(items.map { it.id }.distinct().size == items.size)
            return items
        }
    }
}
