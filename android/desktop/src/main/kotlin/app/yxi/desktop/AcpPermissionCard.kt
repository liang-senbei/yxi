package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal data class AcpPermissionChoice(val id: String, val name: String, val scope: String)

internal fun acpPermissionChoices(params: JSONObject): List<AcpPermissionChoice> {
    val options = params.getJSONArray("options")
    require(options.length() in 1..64) { "运行器审批选项无效" }
    val choices = (0 until options.length()).map { index ->
        val option = options.getJSONObject(index)
        val scope = when (option.getString("kind")) {
            "allow_once" -> "仅本次允许"
            "allow_always" -> "持续允许"
            "reject_once" -> "仅本次拒绝"
            "reject_always" -> "持续拒绝"
            else -> error("运行器返回了尚不支持的审批类型")
        }
        val id = option.getString("optionId")
        require(id.isNotBlank()) { "运行器审批选项缺少标识" }
        AcpPermissionChoice(id, option.optString("name").ifBlank { scope }, scope)
    }
    require(choices.map { it.id }.distinct().size == choices.size) { "运行器审批选项标识重复" }
    return choices
}

/** Selection IDs are native; the caller owns submission, busy state, and cancellation acknowledgement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AcpPermissionCard(params: JSONObject, enabled: Boolean, answer: (String?) -> Unit) {
    val choices = runCatching { acpPermissionChoices(params) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(params.optJSONObject("toolCall")?.optString("title")?.takeIf { it.isNotBlank() } ?: "运行器请求操作权限",
                style = MaterialTheme.typography.titleSmall)
            choices.fold(onSuccess = { options ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        OutlinedButton({ answer(option.id) }, enabled = enabled, shape = RoundedCornerShape(8.dp), modifier = Modifier.widthIn(min = 160.dp, max = 300.dp)) {
                            Column {
                                Text(option.name)
                                if (option.name != option.scope) Text(option.scope, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }, onFailure = { Text(it.message ?: "审批选项无法识别", color = MaterialTheme.colorScheme.error) })
            TextButton({ answer(null) }, enabled = enabled) { Text("取消此次审批") }
        }
    }
}

/** Code 风格的 ACP 审批（M3d-2）：选项照运行器给的；「仅本次拒绝」放左边的拒绝位，「仅本次允许」是主按钮，其余（持续允许 / 持续拒绝 / 取消此次审批）是次按钮。 */
@Composable internal fun CodeAcpApproval(params: JSONObject, enabled: Boolean, answer: (String?) -> Unit) {
    val title = params.optJSONObject("toolCall")?.optString("title")?.takeIf { it.isNotBlank() } ?: "运行器请求操作权限"
    val choices = runCatching { acpPermissionChoices(params) }
    val cancel = CodeApprovalAction("取消此次审批", enabled = enabled) { answer(null) }
    choices.fold(onSuccess = { options ->
        val deny = options.firstOrNull { it.scope == "仅本次拒绝" }
        val allow = options.firstOrNull { it.scope == "仅本次允许" }
        val rest = options.filter { it != deny && it != allow }.map { CodeApprovalAction(it.name, enabled = enabled) { answer(it.id) } }
        CodeApprovalCard(title, deny?.let { d -> CodeApprovalAction(d.name, enabled = enabled) { answer(d.id) } },
            listOf(cancel) + rest + listOfNotNull(allow?.let { a -> CodeApprovalAction(a.name, primary = true, enabled = enabled) { answer(a.id) } }),
            Modifier.padding(bottom = 6.4.dp)) {
            params.optJSONObject("toolCall")?.optJSONArray("content")?.let { content ->
                (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optJSONObject("content")?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                    .joinToString("\n").takeIf { it.isNotBlank() }?.let { CodeCommandBlock(it) }
            }
        }
    }, onFailure = { error ->
        CodeApprovalCard(title, null, listOf(cancel), Modifier.padding(bottom = 6.4.dp)) {
            Text(error.message ?: "审批选项无法识别", color = Tokens.current.danger, fontSize = 14.sp, lineHeight = 20.sp)
        }
    })
}

