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

/** Code 风格的 Claude 审批（M3d-2，规格表 §3.3）：标题「允许 Claude 使用 X？」；有命令就把说明（如果有）和命令放进命令块，没有就放原始输入；「拒绝」在左，「允许本次」是主按钮。 */
@Composable internal fun CodeClaudeApproval(request: JSONObject, enabled: Boolean, onAllow: () -> Unit, onDeny: () -> Unit) {
    val input = request.optJSONObject("input") ?: JSONObject()
    val command = input.optString("command")
    CodeApprovalCard("允许 Claude 使用 ${request.optString("tool_name").ifBlank { "工具" }}？", CodeApprovalAction("拒绝", enabled = enabled, onClick = onDeny),
        listOf(CodeApprovalAction("允许本次", primary = true, enabled = enabled, onClick = onAllow)), Modifier.padding(bottom = 6.4.dp)) {
        input.optString("description").takeIf { it.isNotBlank() }?.let { CodeApprovalText(it) }
        CodeCommandBlock(command.ifBlank { input.toString(2) })
    }
}

