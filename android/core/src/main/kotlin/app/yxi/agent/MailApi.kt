package app.yxi.agent

import org.json.JSONObject
import java.net.URLEncoder

/** Account-independent mail contract. The platform supplies an authenticated
 * transport; this class never reads credentials or retries a mutation. */
class MailApi(private val request: suspend (String, String, String?) -> Pair<Int, String>) {
    data class Page(val items: List<AccountApi.Mail>, val next: String?, val unread: Int?, val unclaimed: Int?)
    private suspend fun json(path: String, method: String = "GET", body: String? = null): JSONObject {
        val (code, raw) = request(path, method, body)
        check(code in 200..299) { when {
            code == 409 -> "邮件中还有未领取的附件，请先领取"
            code == 404 -> "邮件已不存在，请刷新信箱"
            code == 0 && method != "GET" -> "请求结果尚未确认，请刷新信箱核对"
            else -> AccountApi.httpErr(code, raw)
        } }
        return try { JSONObject(raw) } catch (e: Exception) { error(if (method == "GET") "无法读取信箱回复，请重试" else "服务回复无法确认，请刷新信箱核对") }
    }
    suspend fun list(before: String? = null): Page = parsePage(json("/api/mail?limit=30" + (before?.let { "&before=" + encode(it) } ?: "")))
    suspend fun read(id: String): JSONObject = json("/api/mail/${encode(id)}/read", "POST", "{}").also {
        check(it.optBoolean("ok") || it.optInt("unread", -1) >= 0) { "已读状态未确认，请刷新信箱核对" }
    }
    suspend fun claim(id: String): JSONObject = json("/api/mail/${encode(id)}/claim", "POST", "{}").also {
        check(it.has("replay") || it.optBoolean("ok")) { "领取结果未确认，请刷新信箱核对" }
    }
    suspend fun delete(id: String): JSONObject {
        val result = json("/api/mail/${encode(id)}/delete", "POST", "{}")
        check(result.optBoolean("ok")) { "删除结果未确认，请刷新信箱核对" }
        return result
    }
    companion object {
        private fun encode(value: String): String { require(value.isNotBlank()); return URLEncoder.encode(value, "UTF-8").replace("+", "%20") }
        private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key).takeIf { it.isNotEmpty() }
        fun parsePage(root: JSONObject): Page {
            val rows = root.getJSONArray("items")
            val items = (0 until rows.length()).map { i ->
                val mail = rows.getJSONObject(i)
                val attachments = mail.optJSONArray("attachments")
                AccountApi.Mail(
                    id = mail.get("id").let { require(it is String || it is Number); it.toString().also { id -> require(id.isNotBlank()) } }, kind = mail.optional("kind").orEmpty(), title = mail.getString("title"), body = mail.getString("body"),
                    createdAt = mail.optional("createdAt").orEmpty(), readAt = mail.optional("readAt"), expiresAt = mail.optional("expiresAt"),
                    fromName = mail.optJSONObject("from")?.optional("name").orEmpty(), fromAvatar = mail.optJSONObject("from")?.optional("avatar").orEmpty(),
                    attachments = (0 until (attachments?.length() ?: 0)).map { j -> attachments!!.getJSONObject(j).let { a ->
                        AccountApi.Attach(a.getString("kind"), a.optLong("amount"), a.optional("name").orEmpty(), a.optional("code").orEmpty())
                    } }, claimedAt = mail.optional("claimedAt"),
                )
            }
            require(items.map { it.id }.distinct().size == items.size) { "邮件列表包含重复记录" }
            fun count(key: String) = if (root.isNull(key)) null else root.getInt(key).takeIf { it >= 0 }
            val next = if (root.isNull("nextCursor")) null else root.get("nextCursor").let {
                require(it is String || it is Number); it.toString().takeIf { value -> value.isNotBlank() }
            }
            return Page(items, next, count("unread"), count("unclaimed"))
        }
    }
}
