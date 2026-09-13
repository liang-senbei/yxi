package app.yxi.desktop

import app.yxi.ssh.Shell
import org.json.JSONObject
import java.util.Base64

internal object PluginOperationPlan {
    fun command(request: JSONObject): String {
        require(request.optString("action") in setOf("prepare", "set", "status"))
        require(Regex("[a-f0-9]{32}").matches(request.optString("operation")))
        val script = PluginOperationPlan::class.java.getResource("/app/yxi/desktop/plugin-operation.py")!!.readText()
        return "python3 -c ${Shell.q(script)} ${Shell.q(Base64.getEncoder().encodeToString(request.toString().toByteArray()))}"
    }
    fun result(raw: String): JSONObject {
        val value = raw.lineSequence().lastOrNull { it.startsWith("__YXI_PLUGIN_OP__:") } ?: error("插件操作结果未确认，请查询原操作，不要重新提交")
        return JSONObject(value.removePrefix("__YXI_PLUGIN_OP__:")).also {
            require(it.getString("state") in setOf("unsupported-config-home", "invalid", "busy", "missing", "unknown", "invalid-directory", "unsupported-linked-config", "operation-conflict", "prepared", "changed", "configured", "unconfirmed"))
        }
    }
}
