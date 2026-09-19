package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.AccountApi
import app.yxi.agent.MailApi
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun mailRetentionHint(mail: AccountApi.Mail, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
    val until = mail.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    val days = kotlin.math.ceil((until - now) / 86_400_000.0).toLong().coerceAtLeast(0)
    val code = mail.attachments.any { it.kind == "code" }
    val message = (if (days == 0L) "邮件已到保留期限" else "邮件还保留 $days 天") + when {
        code && mail.claimable -> "；请领取附件并保存兑换码。"
        code -> "；请提前保存兑换码，邮件清理后不再显示。"
        mail.claimable -> "；还有附件待领取。"
        mail.claimedAt != null -> "；清理邮件不会撤销已经领取的权益。"
        else -> "。"
    }
    return message to (days <= 3 && (code || mail.claimable))
}

@Composable
internal fun MailPane(owner: String, api: MailApi, onBack: () -> Unit, onCounters: (JSONObject) -> Unit) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var rows by remember(owner) { mutableStateOf<List<AccountApi.Mail>>(emptyList()) }
    var cursor by remember(owner) { mutableStateOf<String?>(null) }
    var selected by remember(owner) { mutableStateOf<AccountApi.Mail?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var loaded by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf("") }
    var notice by remember(owner) { mutableStateOf("") }
    var deleting by remember(owner) { mutableStateOf<AccountApi.Mail?>(null) }
    var filter by remember(owner) { mutableStateOf("全部") }
    var unreadTotal by remember(owner) { mutableStateOf<Int?>(null) }
    fun counters(value: JSONObject) {
        if (value.has("unread") && !value.isNull("unread")) unreadTotal = value.optInt("unread").coerceAtLeast(0)
        onCounters(value)
    }
    val shown = rows.filter { mail -> when (filter) { "未读" -> mail.unread || mail.id == selected?.id; "已读" -> !mail.unread; else -> true } }
    suspend fun load(more: Boolean) {
        val page = api.list(if (more) cursor else null)
        check(!more || page.next == null || page.next != cursor) { "下一页游标没有更新，请刷新后重试" }
        rows = (if (more) rows + page.items else page.items).distinctBy { it.id }
        cursor = page.next
        loaded = true
        counters(JSONObject().apply { page.unread?.let { put("unread", it) }; page.unclaimed?.let { put("unclaimed", it) } })
        selected = selected?.let { old -> rows.firstOrNull { it.id == old.id } }
    }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""; notice = ""
        scope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "请求失败，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(owner) { work { load(false) } }
    Column(Modifier.fillMaxSize().background(t.surface0).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, enabled = !busy) { Text("返回账户") }
            Text("信箱", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), color = t.textPrimary)
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            TextButton({ work { load(false) } }, enabled = !busy) { Text("刷新") }
        }
        if (error.isNotBlank()) Text(error, color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (notice.isNotBlank()) Text(notice, color = t.success, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "未读", "已读").forEach { value ->
                FilterChip(filter == value, { filter = value; selected = null }, enabled = !busy,
                    label = { Text(value + if (value == "未读" && unreadTotal != null) " $unreadTotal" else "") })
            }
        }
        if (filter != "全部" && cursor != null) Text("筛选当前已加载的邮件；可继续加载更早的邮件。未读数量为服务器总数。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 780.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (wide || selected == null) Column(if (wide) Modifier.width(280.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    if (loaded && shown.isEmpty()) Text(if (rows.isEmpty()) "信箱里还没有邮件" else if (cursor != null) "已加载邮件中没有匹配项" else "没有匹配的邮件", color = t.textMuted, modifier = Modifier.padding(16.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.id }) { mail ->
                            Column(Modifier.fillMaxWidth().background(if (selected?.id == mail.id) t.selected else t.surface2, RoundedCornerShape(10.dp))
                                .border(0.5.dp, t.border, RoundedCornerShape(10.dp)).clickable(enabled = !busy) {
                                    selected = mail
                                    if (mail.unread) work {
                                        counters(api.read(mail.id))
                                        val read = mail.copy(readAt = java.time.Instant.now().toString())
                                        rows = rows.map { if (it.id == mail.id) read else it }; selected = read
                                    }
                                }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(mail.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (mail.unread) FontWeight.SemiBold else FontWeight.Normal, color = t.textPrimary)
                                Text(mail.fromName.ifBlank { "Yxi" }, style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                                if (mail.unread || mail.claimable) Text(listOfNotNull(if (mail.unread) "未读" else null, if (mail.claimable) "有附件待领取" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = t.accent)
                                mailRetentionHint(mail)?.takeIf { it.second }?.let { Text(it.first, style = MaterialTheme.typography.labelSmall, color = t.warning) }
                            }
                        }
                        item { if (cursor != null) TextButton({ work { load(true) } }, enabled = !busy) { Text("加载更早的邮件") } }
                    }
                }
                if (wide || selected != null) Column(Modifier.weight(1f).fillMaxHeight().background(t.surface2, RoundedCornerShape(12.dp)).padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val mail = selected
                    if (mail == null) Text("选择一封邮件查看内容", color = t.textMuted)
                    else {
                        if (!wide) TextButton({ selected = null }, enabled = !busy) { Text("返回列表") }
                        Text(mail.title, style = MaterialTheme.typography.headlineSmall, color = t.textPrimary)
                        Text(mail.fromName.ifBlank { "Yxi" } + " · " + mail.createdAt, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        HorizontalDivider(color = t.border)
                        SelectionContainer { Text(mail.body, color = t.textPrimary, style = MaterialTheme.typography.bodyLarge) }
                        mail.expiresAt?.let { Text("保留至 $it", style = MaterialTheme.typography.bodySmall, color = t.textMuted) }
                        mailRetentionHint(mail)?.let { Text(it.first, style = MaterialTheme.typography.bodySmall, color = if (it.second) t.warning else t.textMuted) }
                        if (mail.attachments.isNotEmpty()) Text("随信附件", style = MaterialTheme.typography.titleSmall, color = t.textPrimary)
                        mail.attachments.forEach { attachment ->
                            Column(Modifier.fillMaxWidth().background(t.surface1, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(attachment.name.ifBlank { "附件" }, color = t.textPrimary)
                                SelectionContainer { Text(when (attachment.kind) {
                                    "code" -> attachment.code
                                    "balance_cents" -> "¥" + java.math.BigDecimal.valueOf(attachment.amount, 2).toPlainString()
                                    "membership_days" -> "${attachment.amount} 天"
                                    "tickets" -> "${attachment.amount} 张曦光"
                                    else -> "数量：${attachment.amount}"
                                }, style = MaterialTheme.typography.bodySmall, color = t.textMuted) }
                                if (attachment.kind == "code") TextButton({
                                    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(attachment.code), null) }
                                        .onSuccess { notice = "兑换码已复制" }.onFailure { error = "复制失败，可选中文字手动复制" }
                                }) { Text("复制兑换码") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (mail.claimable) Button({ work {
                                val result = api.claim(mail.id); counters(result)
                                val claimed = mail.copy(claimedAt = java.time.Instant.now().toString(), readAt = mail.readAt ?: java.time.Instant.now().toString())
                                rows = rows.map { if (it.id == mail.id) claimed else it }; selected = claimed
                                notice = if (result.optBoolean("replay")) "此前已领取，本次没有重复发放" else "领取已确认"
                            } }, enabled = !busy) { Text("领取附件") }
                            else if (mail.claimedAt != null) Text("已领取", color = t.textMuted, modifier = Modifier.padding(vertical = 12.dp))
                            TextButton({ error = ""; deleting = mail }, enabled = !busy && !mail.claimable) { Text("删除邮件") }
                        }
                        if (mail.claimable) Text("领取后可删除邮件；兑换码需要自行保存和兑换。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                }
            }
        }
    }
    deleting?.let { mail -> WorkbenchDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除这封邮件？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${mail.title}\n请先保存需要的内容和兑换码。")
        if (error.isNotBlank()) Text(error, color = t.danger, style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = {
        TextButton({ work { counters(api.delete(mail.id)); rows = rows.filterNot { it.id == mail.id }; selected = null; deleting = null } }, enabled = !busy) { Text("删除") }
    }, dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text("保留") } }) }
}
