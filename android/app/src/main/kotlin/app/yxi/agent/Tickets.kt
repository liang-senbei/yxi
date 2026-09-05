package app.yxi.agent

import android.content.Context
import app.yxi.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * **工单中心** —— 用 App 的人写「哪里不好用」，我们在后台看、回复；有回复没看时「我的」里工单格亮红点。
 *
 * 2026-09-05 老板拍板，从「SSH 落到用户自己服务器的 `~/.yxi/tickets.jsonl`」搬进会员服务（hk13）：
 * 以前只有用户本人看得见（写在他自己的机器上），我们收不到、也回不了，等于没提。
 * 接口契约由 cc-logto_yxi 定稿（`logto_yxi/design/` 下工单那份）。路径是 **`/api/support/tickets`**，
 * 不叫 `/api/tickets` —— 这个代码库里 `tickets` 已经是曦光（`wish.tickets` / `tickets_ledger`）。
 *
 * ⚠️⚠️ **隐私红线**：工单只带用户自己写的文字 + App 版本号 + 机型。**绝不**自动附带主机列表、密钥、会话内容 ——
 * 「配置只存本地」是这个 App 的承诺，工单不能成为把它们传上来的后门。[add] 的 version / device 由界面显式填，
 * 这个文件里不从别处抓任何东西。
 *
 * ⚠️ 拿不到（没登录 / 网络不通 / 接口没上线）一律 null，界面说「取不到」，**不说「没有」**（同 [Wish] / [Account.mail]）。
 */
object Tickets {
    private const val BASE = "/api/support/tickets"

    /** 提单时选的分类（老板 2026-09-05 定的四个）。[key] 是接口字段值；显示名过 [t]。 */
    enum class Category(val key: String, private val zh: String) {
        Account("account", "账号问题"),
        Bug("bug", "Bug 反馈"),
        Payment("payment", "充值问题"),
        Other("other", "其他问题");

        // ⚠️ get() 而不是构造参数：enum 常量在类初始化时求值一次，切语言不会跟着变（同 MainActivity 的 Tab）
        val label: String get() = t(zh)

        companion object {
            /** 服务端以后加了新分类、老客户端认不出来 → 归「其他」，别崩 */
            fun of(key: String): Category = entries.firstOrNull { it.key == key } ?: Other
        }
    }

    /** 一条回复。[official] = 我们回的；否则是用户自己追问的。 */
    data class Reply(val official: Boolean, val text: String, val at: String)

    data class Ticket(
        val id: String,
        val category: Category,
        val text: String,
        /** `open`（待处理）/ `replied`（已回复）/ `closed`（已关闭）。用户追问会把 replied 翻回 open。 */
        val status: String,
        val createdAt: String,
        val updatedAt: String,
        val replies: List<Reply>,
        /** 有我没看过的官方回复（服务端按这条工单的 last_read_at 算） */
        val unread: Boolean,
    )

    /**
     * 提一条。@return null = 成功，否则是给人看的失败原因。
     * ⚠️ 用 [Account.apiRaw] 不用 apiPost：服务端对**未关闭工单超过 10 张**回 429 `too_many_open`，
     * 那不是网络问题，得告诉人「先关几条」—— 说成「网络不通」就是骗人（STYLE.md §0）。
     */
    suspend fun add(ctx: Context, category: Category, text: String, version: String, device: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("category", category.key)
                .put("text", text.take(2000))
                .put("version", version)
                .put("device", device)
                .toString()
            val (code, _) = Account.apiRaw(ctx, BASE, "POST", body) ?: return@withContext t("没登录")
            when (code) {
                in 200..299 -> null
                429 -> t("没关闭的工单太多了 —— 先把解决了的关掉几条再提")
                else -> t("没发出去 —— 网络不通，或者登录过期了")
            }
        }

    /**
     * 我的工单，**新的在前**。游标同站内信：`before` 取更旧的，传上一页最后一条的 id。
     * @return null = 拿不到；`Pair(这一页, 下一页游标)`，游标 null = 没有更早的了。
     */
    suspend fun load(ctx: Context, before: String? = null): Pair<List<Ticket>, String?>? = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, "$BASE?limit=30" + (before?.let { "&before=$it" } ?: "")) ?: return@withContext null
        runCatching {
            val list = parseList(o.optJSONArray("items"))
            // ⚠️ 没有下一页是 JSON null；optString 读 null 会给字符串 "null"（#230），先 isNull 判一道
            val next = if (o.isNull("nextCursor")) null else o.optString("nextCursor").takeIf { it.isNotEmpty() }
            // 列表顺带给的未读数就地回灌到 me，不等下一次 /api/me（同邮件）
            Account.setUnreadTickets(o.optInt("unread", -1))
            list to next
        }.getOrNull()
    }

    /** 看过这条的回复了。服务端幂等。 */
    suspend fun markRead(ctx: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val o = Account.apiPost(ctx, "$BASE/$id/read", "{}") ?: return@withContext false
        Account.setUnreadTickets(o.optInt("unread", -1))
        true
    }

    /** 追问一句（服务端会把 replied 翻回 open）。@return null = 成功，否则失败原因。 */
    suspend fun reply(ctx: Context, id: String, text: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text.take(2000)).toString()
        if (Account.apiPost(ctx, "$BASE/$id/reply", body) == null) t("没发出去 —— 网络不通，或者登录过期了") else null
    }

    internal fun parseList(a: JSONArray?): List<Ticket> =
        (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i)?.let(::parse) }

    /** 读一条。字段缺了给空值，不抛 —— 服务端多给字段随时可能发生，少给才是问题。 */
    internal fun parse(o: JSONObject): Ticket {
        val rs = o.optJSONArray("replies")
        return Ticket(
            id = o.optString("id"),
            category = Category.of(o.optString("category")),
            text = o.optString("text"),
            status = o.optString("status").ifEmpty { "open" },
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt"),
            replies = (0 until (rs?.length() ?: 0)).mapNotNull { i -> rs?.optJSONObject(i) }.map { r ->
                Reply(official = r.optString("by") == "official", text = r.optString("text"), at = r.optString("at"))
            },
            unread = o.optBoolean("unread"),
        )
    }
}
