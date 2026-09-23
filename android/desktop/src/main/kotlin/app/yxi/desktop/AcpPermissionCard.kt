package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
@Composable internal fun AcpPermissionCard(params: JSONObject, enabled: Boolean, answer: (String?) -> Unit) {
    val choices = runCatching { acpPermissionChoices(params) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(params.optJSONObject("toolCall")?.optString("title")?.takeIf { it.isNotBlank() } ?: "运行器请求操作权限",
                style = MaterialTheme.typography.titleSmall)
            choices.fold(onSuccess = { options ->
                options.forEach { option ->
                    OutlinedButton({ answer(option.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.name)
                            if (option.name != option.scope) Text(option.scope, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }, onFailure = { Text(it.message ?: "审批选项无法识别", color = MaterialTheme.colorScheme.error) })
            TextButton({ answer(null) }, enabled = enabled) { Text("取消此次审批") }
        }
    }
}
