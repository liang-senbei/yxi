package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.agent.SupportApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal class SupportEditor(private val store: SupportDrafts, val id: String) {
    val draft get() = store.entries.single { it.id == id }
    var text by mutableStateOf(draft.text)
    var category by mutableStateOf(draft.category)
    var version by mutableStateOf(draft.version)
    var device by mutableStateOf(draft.device)
    var error by mutableStateOf("")
        private set
    val dirty get() = text != draft.text || category != draft.category || version != draft.version || device != draft.device
    fun save(): Boolean = try {
        if (dirty) store.edit(id, draft.revision, text, category, version, device)
        error = ""; true
    } catch (e: Exception) { error = "草稿未保存，输入仍保留：${e.message}"; false }
}

internal class SupportWorkspace(file: File) {
    val drafts = SupportDrafts(file)
    val editors = mutableMapOf<String, SupportEditor>()
    val running = mutableStateListOf<String>()
    fun editor(owner: String, ticketId: String? = null): SupportEditor = existing(drafts.open(owner, ticketId).id, owner)
    fun existing(id: String, owner: String): SupportEditor {
        check(drafts.entries.single { it.id == id }.owner == owner)
        return editors.getOrPut(id) { SupportEditor(drafts, id) }
    }
    suspend fun submit(owner: String, editor: SupportEditor, api: SupportApi) {
        check(editor.draft.owner == owner)
        check(editor.save()) { editor.error }
        val sending = drafts.begin(editor.id, editor.draft.revision)
        running += sending.id
        withContext(NonCancellable) {
            try {
                val receipt = if (sending.ticketId == null) "工单 ${api.create(sending.category, sending.text, sending.version, sending.device).id} 已创建"
                    else "工单 ${api.reply(sending.ticketId, sending.text).id} 的追问已接收"
                drafts.confirmed(sending.id, sending.revision, receipt)
            } catch (e: Exception) {
                drafts.failed(sending.id, sending.revision, e as? SupportApi.Failure ?: SupportApi.Failure("结果尚未确认，请核对工单列表", true))
            } finally { running.remove(sending.id) }
        }
    }
}
