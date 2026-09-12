package app.yxi.agent

import java.net.URLEncoder
import org.json.JSONObject

/** User support endpoints only. No admin close endpoint and no implicit device,
 * host, credential, or conversation collection. Mutations are never retried. */
class SupportApi(private val request: suspend (String, String, String?) -> Pair<Int, String>) {
    data class Reply(val by: String, val text: String, val at: String)
    data class Ticket(val id: String, val category: String, val text: String, val status: String,
        val createdAt: String, val updatedAt: String, val replies: List<Reply>, val unread: Boolean)
    data class Page(val items: List<Ticket>, val next: String?, val unread: Int?)
    data class Created(val id: String, val createdAt: String)
    class Failure(message: String, val uncertain: Boolean) : IllegalStateException(message)

    private suspend fun <T> call(path: String, method: String = "GET", body: JSONObject? = null, decode: (JSONObject) -> T): T {
        val mutation = method != "GET"
        val (code, raw) = try { request(path, method, body?.toString()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw Failure(if (mutation) "提交结果尚未确认，请先刷新工单核对" else "暂时无法读取工单", mutation) }
        if (code !in 200..299) {
            val uncertain = mutation && (code == 0 || code == 408 || code >= 500)
            throw Failure(when {
                uncertain -> "提交结果尚未确认，请先刷新工单核对；不会自动重发"
                code == 429 -> "未关闭工单已达上限，请在已有工单中补充说明"
                code == 404 -> "工单已不存在，请刷新列表"
                else -> AccountApi.httpErr(code, raw)
            }, uncertain)
        }
        return try { decode(JSONObject(raw)) }
        catch (e: Exception) { throw Failure(if (mutation) "服务回复无法确认，请先核对工单列表" else "无法读取工单回复，请重试", mutation) }
    }
    suspend fun list(before: String? = null): Page = call(BASE + "?limit=30" + (before?.let { "&before=${encode(it)}" } ?: "")) { root ->
        val rows = root.getJSONArray("items")
        val items = (0 until rows.length()).map { parseTicket(rows.getJSONObject(it)) }
        require(items.map { it.id }.distinct().size == items.size)
        Page(items, optionalId(root, "nextCursor"), if (root.isNull("unread")) null else root.getInt("unread").takeIf { it >= 0 })
    }
    suspend fun create(category: String, text: String, version: String = "", device: String = ""): Created {
        require(category in CATEGORIES) { "请选择有效的工单分类" }
        validateText(text)
        require(version.codePointCount(0, version.length) <= 64 && device.codePointCount(0, device.length) <= 64) { "版本和设备说明最多64字" }
        val body = JSONObject().put("category", category).put("text", text).put("version", version).put("device", device)
        return call(BASE, "POST", body) { Created(optionalId(it, "id") ?: error("Missing ticket id"), it.getString("createdAt")) }
    }
    suspend fun reply(id: String, text: String): Ticket {
        validateText(text)
        return call("$BASE/${encode(id)}/reply", "POST", JSONObject().put("text", text)) {
            parseTicket(it).also { ticket -> require(ticket.id == id) { "Reply belongs to another ticket" } }
        }
    }
    suspend fun read(id: String): Int? = call("$BASE/${encode(id)}/read", "POST", JSONObject()) {
        require(it.getBoolean("ok"))
        if (it.isNull("unread")) null else it.getInt("unread").takeIf { value -> value >= 0 }
    }
    companion object {
        private const val BASE = "/api/support/tickets"
        val CATEGORIES = setOf("account", "bug", "payment", "other")
        fun validateText(text: String) {
            require(text.isNotBlank() && text.codePointCount(0, text.length) <= 2000) { "内容需为1至2000字，不会自动截断" }
            require(text.none { it < ' ' && it != '\n' && it != '\t' && it != '\r' }) { "内容包含不可提交的控制字符" }
        }
        private fun encode(value: String): String { require(value.isNotBlank()); return URLEncoder.encode(value, "UTF-8").replace("+", "%20") }
        private fun optionalId(root: JSONObject, key: String): String? = if (root.isNull(key)) null else root.get(key).let {
            require(it is String || it is Number); it.toString().takeIf { value -> value.isNotBlank() }
        }
        private fun optional(root: JSONObject, key: String) = if (root.isNull(key)) "" else root.getString(key)
        fun parseTicket(root: JSONObject): Ticket {
            val replies = root.getJSONArray("replies")
            return Ticket(optionalId(root, "id") ?: error("Missing ticket id"), root.getString("category"), root.getString("text"), root.getString("status"),
                optional(root, "createdAt"), optional(root, "updatedAt"),
                (0 until replies.length()).map { replies.getJSONObject(it).let { r -> Reply(r.getString("by"), r.getString("text"), optional(r, "at")) } }, root.getBoolean("unread"))
        }
    }
}
