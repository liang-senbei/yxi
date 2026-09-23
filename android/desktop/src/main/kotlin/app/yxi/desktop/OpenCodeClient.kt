package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** Native OpenCode HTTP protocol. The endpoint must be an owned local server or SSH forward.
 * Mutations are never retried: a lost response is an unknown outcome, not a rejection.
 * No auth/config write endpoints are exposed; credentials are supplied by the server owner.
 */
internal class OpenCodeClient(port: Int, password: String, private val directory: String) {
    private val base = URI("http://127.0.0.1:$port")
    private val authorization: String
    init {
        require(port in 1..65535 && password.isNotBlank())
        require(directory.isNotBlank() && directory.none { it < ' ' })
        authorization = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))
    }

    suspend fun health(): JSONObject = JSONObject(request("GET", "/global/health"))
    suspend fun providers(): JSONObject = JSONObject(request("GET", "/provider"))
    suspend fun sessions(): JSONArray = JSONArray(request("GET", "/session"))
    suspend fun status(): JSONObject = JSONObject(request("GET", "/session/status"))
    suspend fun session(id: String): JSONObject = JSONObject(request("GET", "/session/${segment(id)}"))
    suspend fun messages(id: String, limit: Int = 50): JSONArray {
        require(limit in 1..200)
        return JSONArray(request("GET", "/session/${segment(id)}/message", query = "&limit=$limit"))
    }
    suspend fun create(title: String): JSONObject {
        require(title.length <= 500 && title.none { it < ' ' })
        return JSONObject(request("POST", "/session", JSONObject().put("title", title))).also {
            check(it.optString("id").isNotBlank()) { "OpenCode 创建结果缺少会话 ID，请核对原生历史" }
        }
    }
    suspend fun send(id: String, text: String, provider: String, model: String) {
        require(text.isNotBlank() && text.length <= 100_000)
        require(provider.isNotBlank() && model.isNotBlank())
        request("POST", "/session/${segment(id)}/prompt_async", JSONObject()
            .put("model", JSONObject().put("providerID", provider).put("modelID", model))
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
    }
    suspend fun abort(id: String): Boolean = request("POST", "/session/${segment(id)}/abort").trim().let {
        check(it == "true" || it == "false") { "OpenCode 停止结果无法识别" }; it == "true"
    }

    private fun segment(value: String): String {
        require(value.isNotBlank() && value.length <= 256 && value.none { it < ' ' })
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
    private suspend fun request(method: String, path: String, body: JSONObject? = null, query: String = ""): String = withContext(Dispatchers.IO) {
        val connection = base.resolve(path + "?directory=" + URLEncoder.encode(directory, "UTF-8") + query)
            .toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "OpenCode HTTP $code；${if (method == "POST") "操作结果需要核对，未自动重试" else "读取失败"}" }
            connection.inputStream.use {
                val bytes = it.readNBytes(8 * 1024 * 1024 + 1)
                check(bytes.size <= 8 * 1024 * 1024) { "OpenCode 响应超过大小限制" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
}
