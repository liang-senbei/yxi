package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun CodexActivityCard(message: CodexMessage, onOpen: (String) -> Unit, initiallyExpanded: Boolean = false) {
    var expanded by remember(message.id) { mutableStateOf(initiallyExpanded) }
    val status = when (message.status) {
        "inProgress" -> "进行中"
        "completed" -> "已完成"
        "failed" -> "失败"
        "declined" -> "已拒绝"
        else -> message.status
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.author + if (message.paths.isEmpty()) "" else " · ${message.paths.size} 个文件",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(status, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "详情") }
            }
            if (expanded) {
                message.paths.forEach { path -> TextButton({ onOpen(path) }) { Text(path) } }
                SelectionContainer {
                    Text(message.text, Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
