package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.ChatItem
import app.yxi.agent.Transcript

private data class ConversationMatch(val key: String, val label: String, val excerpt: String)

@Composable
internal fun ConversationSearchDialog(items: List<ChatItem>, close: () -> Unit, locate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = if (query.isBlank()) emptyList() else items.mapNotNull { item ->
        val content = when(item) {
            is ChatItem.UserText -> "你" to item.text
            is ChatItem.AssistantText -> "Agent" to item.markdown
            is ChatItem.ToolCall -> "工具 · ${item.name}" to (item.input.toString() + "\n" + item.result.orEmpty())
            is ChatItem.Thinking -> "思考记录" to item.text
            is ChatItem.Queued -> "排队消息" to item.text
            is ChatItem.ApiError -> "错误提示" to item.text
            is ChatItem.Injected -> "补充内容" to Transcript.clean(item.text)
            else -> null
        } ?: return@mapNotNull null
        val at = content.second.indexOf(query, ignoreCase = true)
        if (at < 0) null else {
            val start = (at - 70).coerceAtLeast(0)
            ConversationMatch(item.key, content.first, (if (start > 0) "…" else "") + content.second.substring(start).take(360))
        }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text("搜索当前对话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索消息或工具记录") })
            Text("搜索当前已加载的内容 · ${matches.size} 条匹配", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (matches.isEmpty()) item { Text(if(query.isBlank()) "输入关键词开始搜索" else "当前已加载内容中没有匹配项") }
                items(matches, key = { it.key }) { match ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(match.label, style = MaterialTheme.typography.labelMedium)
                        Text(match.excerpt, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        TextButton({ locate(match.key) }) { Text("定位到所在位置") }
                    } }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}
