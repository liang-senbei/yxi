package app.yxi.desktop

import app.yxi.ssh.Shell
import kotlinx.coroutines.*
import org.json.JSONObject

/** Model discovery is performed on the selected host, using that host's network and proxies. */
internal object ProviderModels {
    data class Model(val id: String, val owner: String? = null)
    internal val script: String get() = ProviderModels::class.java.getResource("/app/yxi/desktop/provider-models.py")!!.readText()

    suspend fun fetch(conn: Conn, baseUrl: String, apiKey: String, modelsUrl: String = ""): List<Model> {
        require(baseUrl.isNotBlank()) { "请先填写请求地址" }
        require(apiKey.isNotBlank()) { "请先填写 API Key 或 Auth token" }
        check(conn.ssh.isConnected) { "服务器已断开，请重新连接后获取模型" }
        val input = JSONObject().put("baseUrl", baseUrl.trim()).put("apiKey", apiKey.trim())
            .put("modelsUrl", modelsUrl.trim()).toString().toByteArray(Charsets.UTF_8)
        require(input.size <= 65536) { "模型列表请求配置过长" }
        val channel = withContext(NonCancellable) { conn.ssh.openExecStream("timeout 65s python3 -c ${Shell.q(script)} 2>/dev/null") }
        val output = try {
            coroutineScope {
                val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { channel.close() }
                }
                try {
                    val writer = launch(Dispatchers.IO) {
                        check(channel.write(input) && channel.finishInput()) { "无法向服务器提交模型列表请求" }
                    }
                    val result = withContext(Dispatchers.IO) { readRewindOutput(channel.output, 3 * 1024 * 1024) }
                    writer.join()
                    result
                } finally { closer.cancel() }
            }
        } finally { input.fill(0); channel.close() }
        val reply = runCatching { JSONObject(output) }.getOrElse { error("服务器未返回有效模型列表；请检查 Python 3 和网络连接后重试") }
        if (!reply.optBoolean("ok")) error(when (reply.optString("error")) {
            "missing-key" -> "请先填写 API Key 或 Auth token"
            "key-format" -> "密钥格式无效，不能包含换行或控制字符"
            "url" -> "请输入完整 HTTP/HTTPS 地址，不能包含账号密码或片段"
            "auth" -> "密钥无效或没有读取模型列表的权限"
            "not-found" -> "未找到模型列表接口，请填写模型列表地址，或确认供应商是否开放此接口"
            "timeout" -> "获取模型列表超时，请检查所选服务器到供应商的网络"
            "network" -> "所选服务器无法连接供应商，请检查地址、网络或证书"
            "redirect" -> "模型列表地址发生重定向，请填写最终地址后重试"
            "too-large" -> "模型列表超过读取上限"
            "format" -> "供应商返回的内容不是支持的模型列表格式"
            "http" -> "模型列表接口返回 HTTP ${reply.optInt("status")}"
            else -> "获取模型列表失败，请检查配置后重试"
        })
        val rows = reply.getJSONArray("models")
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            Model(it.getString("id"), it.optString("owner").takeIf { value -> value.isNotBlank() && value != "null" })
        } }
    }
}
