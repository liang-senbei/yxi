package app.yxi.desktop

import app.yxi.ssh.Shell
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class PreviewServicePlan(val project: String, val directory: String, val command: String, val port: Int) {
    init {
        require(Regex("[a-f0-9]{64}").matches(project)) { "项目标识无效" }
        require(directory.startsWith('/') && directory.none { it < ' ' || it == '\u007f' }) { "请输入服务器上的绝对工作目录，不含控制字符" }
        require(command.isNotBlank() && command.length <= 16000 && '\u0000' !in command) { "启动命令不能为空，最多16000字符且不含空字节" }
        require(port in 1..65535) { "监听端口需为1至65535" }
    }
    val signature get() = MessageDigest.getInstance("SHA-256").digest(listOf(project, directory, command, port.toString()).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    fun startCommand() = request(JSONObject().put("action", "start").put("project", project).put("directory", directory).put("command", command).put("port", port).put("config", signature))
    fun statusCommand(path: String = "/") = request(JSONObject().put("action", "status").put("project", project).put("config", signature).put("probePath", normalizeReadinessPath(path)))
    fun stopCommand(runtimeId: String, expectedConfig: String = signature): String {
        require(Regex("[0-9]+:\\$[0-9]+:[0-9]+").matches(runtimeId))
        require(Regex("[a-f0-9]{64}").matches(expectedConfig))
        return stopCommand(project, runtimeId, expectedConfig)
    }
    companion object {
        private val script by lazy { PreviewServicePlan::class.java.getResource("/app/yxi/desktop/preview-service.py")!!.readText() }
        private fun request(json: JSONObject): String {
            val encoded = Base64.getEncoder().encodeToString(json.toString().toByteArray())
            return "python3 -c ${Shell.q(script)} ${Shell.q(encoded)}"
        }
        fun statusCommand(project: String, path: String = "/"): String {
            require(Regex("[a-f0-9]{64}").matches(project))
            return request(JSONObject().put("action", "status").put("project", project).put("probePath", normalizeReadinessPath(path)))
        }
        fun stopCommand(project: String, runtime: String, config: String): String {
            require(Regex("[a-f0-9]{64}").matches(project) && Regex("[a-f0-9]{64}").matches(config))
            require(Regex("[0-9]+:\\$[0-9]+:[0-9]+").matches(runtime))
            return request(JSONObject().put("action", "stop").put("project", project).put("runtime", runtime).put("expectedConfig", config))
        }
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
