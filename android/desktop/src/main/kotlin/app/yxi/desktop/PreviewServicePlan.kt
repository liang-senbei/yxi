package app.yxi.desktop

import app.yxi.ssh.Shell
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class PreviewServicePlan(val project: String, val directory: String, val command: String, val port: Int) {
    init {
        require(Regex("[a-f0-9]{64}").matches(project))
        require(directory.startsWith('/') && directory.none { it < ' ' || it == '\u007f' })
        require(command.isNotBlank() && command.length <= 16000 && '\u0000' !in command)
        require(port in 1..65535)
    }
    val signature get() = MessageDigest.getInstance("SHA-256").digest(listOf(project, directory, command, port.toString()).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    fun startCommand() = request("start")
    fun statusCommand() = request("status")
    fun stopCommand(runtimeId: String, expectedConfig: String = signature): String {
        require(Regex("[0-9]+:\\$[0-9]+:[0-9]+").matches(runtimeId))
        require(Regex("[a-f0-9]{64}").matches(expectedConfig))
        return request("stop", runtimeId, expectedConfig)
    }
    private fun request(action: String, runtime: String = "", expectedConfig: String = signature): String {
        val json = JSONObject().put("action", action).put("project", project).put("directory", directory).put("command", command)
            .put("port", port).put("config", signature).put("runtime", runtime).put("expectedConfig", expectedConfig)
        val encoded = Base64.getEncoder().encodeToString(json.toString().toByteArray())
        return "python3 -c ${Shell.q(script)} ${Shell.q(encoded)}"
    }
    companion object {
        private val script by lazy { PreviewServicePlan::class.java.getResource("/app/yxi/desktop/preview-service.py")!!.readText() }
        fun result(raw: String): JSONObject {
            val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_PREVIEW__:") } ?: error("开发服务状态未确认，请检查连接及服务端Python3")
            return JSONObject(line.removePrefix("__YXI_PREVIEW__:")).also {
                val state = it.getString("state")
                require(state in setOf("missing", "missing-tool", "missing-directory", "port-busy", "starting", "running", "exited", "unowned", "configuration-conflict", "changed", "stopped", "unknown", "error"))
                if (state in setOf("starting", "running", "exited", "configuration-conflict")) {
                    require(Regex("[0-9]+:\\$[0-9]+:[0-9]+").matches(it.getString("runtime")))
                    require(Regex("[a-f0-9]{64}").matches(it.getString("config")))
                }
            }
        }
    }
}
