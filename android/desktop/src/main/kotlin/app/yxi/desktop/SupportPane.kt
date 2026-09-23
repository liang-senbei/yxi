package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.SupportApi
import kotlinx.coroutines.launch

private val supportLabels = linkedMapOf("account" to "账号问题", "bug" to "Bug反馈", "payment" to "充值问题", "other" to "其他问题")
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SupportPane(owner: String, api: SupportApi, workspace: SupportWorkspace, onBack: () -> Unit, onUnread: (Int) -> Unit) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var rows by remember(owner) { mutableStateOf<List<SupportApi.Ticket>>(emptyList()) }
    var cursor by remember(owner) { mutableStateOf<String?>(null) }
    var selected by remember(owner) { mutableStateOf<SupportApi.Ticket?>(null) }
    var editor by remember(owner) { mutableStateOf<SupportEditor?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf("") }
    var loaded by remember(owner) { mutableStateOf(false) }
    var reconcile by remember(owner) { mutableStateOf<SupportDraft?>(null) }
    suspend fun load(more: Boolean = false) {
        val page = api.list(if (more) cursor else null)
        check(!more || page.next == null || page.next != cursor) { "分页结果未更新，请刷新" }
        rows = (if (more) rows + page.items else page.items).distinctBy { it.id }
        cursor = page.next; loaded = true; page.unread?.let(onUnread)
        selected = selected?.let { old -> rows.firstOrNull { it.id == old.id } }
    }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    LaunchedEffect(owner) { work { load() } }
    Column(Modifier.fillMaxSize().background(t.surface0).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onBack) { Text("返回账户") }
            Text("工单中心", style = MaterialTheme.typography.titleLarge, color = t.textPrimary, modifier = Modifier.weight(1f))
            TextButton({ work { load() } }, enabled = !busy) { Text("刷新") }
            Button({ work { selected = null; editor = workspace.editor(owner) } }, enabled = !busy) { Text("新建工单") }
        }
        if (error.isNotBlank()) Text(error, color = t.danger)
        if (workspace.drafts.error.isNotBlank()) Text(workspace.drafts.error, color = t.danger)
        val drafts = workspace.drafts.entries.filter { it.owner == owner && it.state != SupportSendState.Confirmed }
        if (drafts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ work { editor = workspace.existing(drafts.last().id, owner); selected = null } }) { Text("继续草稿 / 核对提交 · ${drafts.size}") }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 820.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (wide || (selected == null && editor == null)) LazyColumn(if (wide) Modifier.width(280.dp) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loaded && rows.isEmpty()) item { Text("还没有工单", color = t.textMuted) }
                    items(rows, key = { it.id }) { ticket ->
                        Column(Modifier.fillMaxWidth().background(if (selected?.id == ticket.id) t.selected else t.surface2, RoundedCornerShape(10.dp)).clickable(enabled = !busy) {
                            selected = ticket; editor = null
                            if (ticket.unread) work {
                                api.read(ticket.id)?.let(onUnread)
                                val read = ticket.copy(unread = false)
                                rows = rows.map { if (it.id == ticket.id) read else it }; selected = read
                            }
                        }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(ticket.text, maxLines = 2, overflow = TextOverflow.Ellipsis, color = t.textPrimary)
                            Text((supportLabels[ticket.category] ?: ticket.category) + " · " + supportStatus(ticket.status) + if (ticket.unread) " · 新回复" else "", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                        }
                    }
                    item { if (cursor != null) TextButton({ work { load(true) } }, enabled = !busy) { Text("加载更早的工单") } }
                    items(drafts, key = { "draft-${it.id}" }) { draft -> TextButton({ work { editor = workspace.existing(draft.id, owner); selected = null } }) {
                        Text((if (draft.state == SupportSendState.Unknown) "待核对" else "草稿") + " · " + draft.text.ifBlank { "尚未填写" }.take(28), maxLines = 2)
                    } }
                }
                if (wide || selected != null || editor != null) Column(Modifier.weight(1f).fillMaxHeight().background(t.surface2, RoundedCornerShape(12.dp)).padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!wide) TextButton({ selected = null; editor = null }) { Text("返回列表") }
                    val edit = editor
                    if (edit != null) {
                        val draft = edit.draft
                        val editable = draft.state == SupportSendState.Editing && !busy
                        Text(if (draft.ticketId == null) "告诉我们遇到的问题" else "补充工单 ${draft.ticketId}", style = MaterialTheme.typography.headlineSmall, color = t.textPrimary)
                        if (draft.ticketId == null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                supportLabels.forEach { (key, label) -> QuietChoice(selected = edit.category == key, onClick = { edit.category = key; edit.save() }, enabled = editable, label = { Text(label) }) }
                            }
                        }
                        OutlinedTextField(edit.text, { edit.text = it; edit.save() }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), readOnly = !editable, label = { Text("问题说明") })
                        Text("${edit.text.codePointCount(0, edit.text.length)} / 2000 字", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                        if (draft.ticketId == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(edit.version, { edit.version = it; edit.save() }, readOnly = !editable, label = { Text("版本号（可选）") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(edit.device, { edit.device = it; edit.save() }, readOnly = !editable, label = { Text("设备说明（可选）") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                        }
                        Text("仅提交你填写的内容，不自动附加主机、密钥或对话记录。草稿保存在本机。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        if (edit.error.isNotBlank()) Text(edit.error, color = t.danger)
                        if (draft.detail.isNotBlank()) Text(draft.detail, color = if (draft.state == SupportSendState.Confirmed) t.success else t.textMuted)
                        when (draft.state) {
                            SupportSendState.Editing -> Button({ work { workspace.submit(owner, edit, api); if (edit.draft.state == SupportSendState.Confirmed) load() } }, enabled = editable && edit.text.isNotBlank()) { Text(if (draft.ticketId == null) "提交工单" else "发送追问") }
                            SupportSendState.Sending -> Text(if (draft.id in workspace.running) "正在提交…" else "结果保存未完成，请重启后核对记录", color = t.textMuted)
                            SupportSendState.Unknown -> TextButton({ reconcile = draft }) { Text("核对提交结果") }
                            SupportSendState.Confirmed -> TextButton({ editor = null }) { Text("返回工单记录") }
                        }
                    } else selected?.let { ticket ->
                        Text("工单 ${ticket.id}", style = MaterialTheme.typography.headlineSmall, color = t.textPrimary)
                        Text(supportStatus(ticket.status) + " · " + ticket.createdAt, color = t.textMuted)
                        SelectionContainer { Text(ticket.text, color = t.textPrimary) }
                        HorizontalDivider(color = t.border)
                        ticket.replies.forEach { reply -> Column(Modifier.fillMaxWidth().background(t.surface1, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when(reply.by) { "official" -> "Yxi 官方"; "user" -> "我"; else -> "来源：${reply.by}" } + " · " + reply.at, style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                            SelectionContainer { Text(reply.text, color = t.textPrimary) }
                        } }
                        if (ticket.status == "closed") Text("追加说明会重新打开这张工单。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        Button({ work { editor = workspace.editor(owner, ticket.id) } }, enabled = !busy) { Text("追加说明") }
                    } ?: Text("选择工单查看回复，或创建新的工单。", color = t.textMuted)
                }
            }
        }
    }
    reconcile?.let { draft -> WorkbenchDialog(onDismissRequest = { reconcile = null }, title = { Text("先核对工单记录") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("请刷新列表，检查这段内容是否已提交。找到对应记录后可结束此草稿；仅在确认没有提交时恢复编辑。不会自动重发。")
        Text(draft.text.take(200), style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
        if (error.isNotBlank()) Text(error, color = t.danger)
    } }, confirmButton = { Column {
        TextButton({ work { workspace.drafts.reconcile(draft.id, draft.revision, "用户已在工单记录中核对"); reconcile = null } }) { Text("已找到提交记录") }
        TextButton({ work { workspace.drafts.reconcile(draft.id, draft.revision, null); reconcile = null } }) { Text("确认未提交，恢复编辑") }
        TextButton({ reconcile = null }) { Text("继续核对") }
    } }) }
}
private fun supportStatus(value: String) = when (value) { "open" -> "待处理"; "replied" -> "已回复"; "closed" -> "已关闭"; else -> "状态：$value" }
