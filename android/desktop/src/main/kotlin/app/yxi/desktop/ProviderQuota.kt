package app.yxi.desktop

import app.yxi.agent.Lines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Quotas belong to the selected provider credential, never to a host's token-cost log. */
internal object ProviderQuota {
    data class Window(val usedPercent: Double, val resetsAt: Long?)
    data class Snapshot(val fiveHour: Window?, val weekly: Window?, val fetchedAt: Long)
    fun endpoint(baseUrl: String): URI? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
        val host = uri.host?.lowercase()
        if (host !in setOf("open.bigmodel.cn", "api.z.ai")) return null
        return URI("https://$host/api/monitor/usage/quota/limit")
    }
    fun parse(raw: String): Snapshot {
        val root = JSONObject(raw)
        check(!root.has("success") || root.optBoolean("success")) { "供应商未返回额度数据" }
        val limits = root.optJSONObject("data")?.optJSONArray("limits") ?: error("供应商未返回额度窗口")
        var fiveHour: Window? = null; var weekly: Window? = null
        for (i in 0 until limits.length()) {
            val item = limits.optJSONObject(i) ?: continue
            if (item.optString("type").uppercase() !in setOf("TOKENS_LIMIT", "CREDIT_LIMIT")) continue
            if (!item.has("percentage") || item.isNull("percentage")) continue
            val percent = item.optDouble("percentage", Double.NaN)
            if (!percent.isFinite() || percent !in 0.0..100.0) continue
            val window = Window(percent, item.optLong("nextResetTime", 0).takeIf { it > 0 })
            // Do not infer a weekly bucket by sorting reset times: it can reset before the 5h bucket.
            when (item.optInt("unit", -1)) {
                3 -> if (fiveHour == null) fiveHour = window
                6 -> if (weekly == null) weekly = window
            }
        }
        check(fiveHour != null || weekly != null) { "尚无法识别该供应商的额度周期" }
        return Snapshot(fiveHour, weekly, System.currentTimeMillis())
    }
    suspend fun fetch(line: Lines.Line): Snapshot = withContext(Dispatchers.IO) {
        val url = endpoint(line.baseUrl) ?: error("该供应商尚未接入额度查询")
        val key = line.apiKey.ifBlank { line.token }
        check(key.isNotBlank()) { "请先为此供应商配置密钥" }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15))
            .header("Authorization", key).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            check(response.statusCode() == 200) { "额度查询失败（HTTP ${response.statusCode()}）" }
            val bytes = body.readNBytes(256 * 1024 + 1)
            check(bytes.size <= 256 * 1024) { "额度响应过大" }
            parse(bytes.toString(Charsets.UTF_8))
        }
    }
}
