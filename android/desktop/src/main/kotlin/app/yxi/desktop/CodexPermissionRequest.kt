package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun CodexPermissionRequest(params: JSONObject, ready: Boolean, answer: (JSONObject) -> Unit) {
    val requested = params.optJSONObject("permissions")
    fun knownKeys(value: JSONObject?, allowed: Set<String>) = value == null || value.keys().asSequence().all { it in allowed }
    val supported = requested != null && knownKeys(requested, setOf("network", "fileSystem")) &&
        knownKeys(requested.optJSONObject("network"), setOf("enabled")) &&
        knownKeys(requested.optJSONObject("fileSystem"), setOf("entries", "globScanMaxDepth", "read", "write"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("以下额外权限仅限当前轮次，不会保存为会话级授权。", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text(requested?.toString(2) ?: "权限内容缺失", Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall)
        }
        if (!supported) Text("包含尚未支持的权限字段，请拒绝或中断后调整任务。", color = Tokens.current.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ answer(JSONObject().put("permissions", JSONObject(requested!!.toString())).put("scope", "turn")) },
                enabled = ready && supported) { Text("仅本轮允许所列权限") }
            OutlinedButton({ answer(JSONObject().put("permissions", JSONObject()).put("scope", "turn")) }, enabled = ready) { Text("不授予") }
        }
    }
}
