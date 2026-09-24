package app.yxi.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun ClaudePermissionCard(
    request: JSONObject,
    enabled: Boolean,
    responding: Boolean,
    onAllowOnce: () -> Unit,
    onDeny: () -> Unit,
) {
    val scroll = rememberScrollState()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("允许 Claude 使用 ${request.getString("tool_name")}？")
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                SelectionContainer(Modifier.fillMaxWidth().padding(end = 14.dp).verticalScroll(scroll)) {
                    Text(request.getJSONObject("input").toString(2),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onAllowOnce, enabled = enabled && !responding) { Text("允许本次") }
                TextButton(onDeny, enabled = enabled && !responding) { Text("拒绝") }
            }
        }
    }
}
