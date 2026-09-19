package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private data class HubMessage(val id: String, val sender: String, val recipient: String, val body: String,
    val stage: String, val time: String, val replyTo: String, val hasBody: Boolean, val recipientInstance: String, val senderKind: String)

@Composable
internal fun HubEventCards(raw: String, query: String, requestReply: ((String, String, String) -> Unit)? = null, search: (String) -> Unit) {
    val parsed = remember(raw) {
        val messages = linkedMapOf<String, HubMessage>()
        var skipped = 0
        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            runCatching {
                val event = JSONObject(line)
                require(event.getInt("version") == 1)
                val id = event.getString("id"); require(id.isNotBlank())
                val old = messages[id]
                val hasBody = event.getString("stage") == "attempting"
                messages[id] = HubMessage(id, event.getString("sender"), event.getString("recipient"),
                    if (hasBody) event.optString("text") else old?.body.orEmpty(), event.getString("stage"),
                    event.optString("time"), event.optString("replyTo").ifBlank { old?.replyTo.orEmpty() }, hasBody || old?.hasBody == true,
                    event.optString("recipientInstance").ifBlank { old?.recipientInstance.orEmpty() },
                    event.optString("senderKind").ifBlank { old?.senderKind ?: "agent" })
            }.onFailure { skipped++ }
        }
        messages.values.toList().asReversed() to skipped
    }
    val messages = parsed.first.filter { message -> query.isBlank() ||
        listOf(message.id, message.sender, message.recipient, message.body, message.replyTo).any { it.contains(query.trim(), ignoreCase = true) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${messages.size} 条消息" + if (parsed.second > 0) " · ${parsed.second} 行不完整或无法识别" else "", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item { Text(if (raw.isBlank()) "暂无协作消息" else "没有匹配的完整事件", color = Tokens.current.textMuted) }
            items(messages, key = { it.id }) { message ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${message.sender} → ${message.recipient}", style = MaterialTheme.typography.titleSmall)
                        if (message.senderKind == "user") Text("用户发送", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                        Text(when (message.stage) {
                            "attempting" -> "投递已开始 · 结果待确认"
                            "terminal-written" -> "终端已写入 · 尚无处理回执"
                            "write-unknown" -> "文字写入结果待确认"
                            "submit-unknown" -> "回车提交结果待确认"
                            else -> "未知阶段：${message.stage}"
                        }, style = MaterialTheme.typography.labelMedium, color = Tokens.current.textMuted)
                        Text(message.time, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                        SelectionContainer { Text(if (message.hasBody) message.body else "正文不在本次读取范围内", style = MaterialTheme.typography.bodySmall) }
                        if (message.replyTo.isNotBlank()) TextButton({ search(message.replyTo) }) { Text("查看原消息与回复") }
                        TextButton({ search(message.id) }) { Text("查看此消息的关联") }
                        if (requestReply != null && message.recipientInstance.isNotBlank() && message.senderKind != "user") TextButton({ requestReply(message.id, message.recipient, message.recipientInstance) }) { Text("请接收 Agent 回复…") }
                        SelectionContainer { Text(message.id, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted) }
                    }
                }
            }
        }
    }
}
