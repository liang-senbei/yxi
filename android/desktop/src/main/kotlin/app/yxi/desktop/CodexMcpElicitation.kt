package app.yxi.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.json.JSONObject

internal object CodexMcpElicitation {
    fun canAcceptEmptyForm(params: JSONObject): Boolean {
        val schema = params.optJSONObject("requestedSchema") ?: return false
        return params.optString("mode") == "form" && schema.optString("type") == "object" &&
            schema.optJSONObject("properties")?.length() == 0 && (schema.optJSONArray("required")?.length() ?: 0) == 0
    }
    fun response(params: JSONObject, action: String): JSONObject {
        require(action in setOf("accept", "decline", "cancel"))
        require(action != "accept" || canAcceptEmptyForm(params)) { "此插件需要填写表单，不能用空答复批准" }
        return JSONObject().put("action", action).apply { if (action == "accept") put("content", JSONObject()) }
    }
}

@Composable internal fun CodexMcpElicitationButtons(params: JSONObject, enabled: Boolean, reply: (JSONObject) -> Unit) {
    Text(params.optString("message"))
    if (!CodexMcpElicitation.canAcceptEmptyForm(params)) Text("此插件要求填写表单或完成授权，当前表单接入尚未完成。")
    Row {
        TextButton({ reply(CodexMcpElicitation.response(params, "accept")) }, enabled = enabled && CodexMcpElicitation.canAcceptEmptyForm(params)) { Text("仅允许本次") }
        TextButton({ reply(CodexMcpElicitation.response(params, "decline")) }, enabled = enabled) { Text("拒绝") }
        TextButton({ reply(CodexMcpElicitation.response(params, "cancel")) }, enabled = enabled) { Text("取消请求") }
    }
}
