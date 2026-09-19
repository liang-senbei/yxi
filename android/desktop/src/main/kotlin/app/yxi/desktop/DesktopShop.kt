package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.agent.AccountApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ShopItem(val id: String, val name: String, val tier: String, val days: Int, val cents: Long)
internal data class ShopCatalog(val items: List<ShopItem>, val balanceCents: Long)
internal data class ShopReceipt(val orderId: String, val code: String, val cents: Long, val balanceCents: Long, val replay: Boolean)
internal data class PendingPurchase(val owner: String, val itemId: String, val requestId: String, val quotedCents: Long)

/** Store request identity, never voucher codes or credentials. An uncertain purchase keeps its ID. */
internal class ShopPurchaseStore(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    private var readable = true
    var pending by mutableStateOf<List<PendingPurchase>>(emptyList()); private set
    var error by mutableStateOf(""); private set
    init {
        try { pending = disk.read()?.let(::decode).orEmpty() }
        catch (e: Exception) { readable = false; error = "购买记录无法读取，原文件已保留：${e.message}" }
    }
    fun forOwner(owner: String) = pending.firstOrNull { it.owner == owner }
    @Synchronized fun begin(owner: String, item: ShopItem): PendingPurchase {
        check(readable) { error }
        check(forOwner(owner) == null) { "请先核对上一笔购买结果" }
        val request = PendingPurchase(owner, item.id, UUID.randomUUID().toString(), item.cents)
        save(pending + request)
        return request
    }
    @Synchronized fun resolved(request: PendingPurchase) {
        check(forOwner(request.owner) == request) { "购买记录已变化" }
        save(pending.filterNot { it.owner == request.owner })
    }
    private fun save(next: List<PendingPurchase>) {
        check(readable) { error }
        try {
            disk.write(JSONObject().put("version", 1).put("pending", JSONArray(next.map {
                JSONObject().put("owner", it.owner).put("itemId", it.itemId).put("requestId", it.requestId).put("quotedCents", it.quotedCents)
            })).toString(2))
            pending = next; error = ""
        } catch (e: Exception) { error = "购买记录未保存：${e.message}"; throw e }
    }
    private fun decode(text: String): List<PendingPurchase> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("pending")
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            PendingPurchase(it.getString("owner"), it.getString("itemId"), it.getString("requestId"), it.getLong("quotedCents"))
        }.also { require(it.owner.isNotBlank() && it.itemId.isNotBlank() && it.requestId.isNotBlank() && it.quotedCents >= 0) } }
            .also { require(it.map { row -> row.owner }.distinct().size == it.size) }
    }
}

internal class DesktopShop(private val request: suspend (String, String, String?) -> Pair<Int, String>) {
    suspend fun catalog(): ShopCatalog {
        val (status, raw) = request("/api/shop", "GET", null)
        check(status in 200..299) { AccountApi.httpErr(status, raw) }
        val body = JSONObject(raw)
        val rows = body.getJSONArray("items")
        val items = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            ShopItem(it.getString("id"), it.getString("name"), it.getString("tier"), it.getInt("days"), it.getLong("priceCents"))
        }.also { require(it.id.isNotBlank() && it.name.isNotBlank() && it.cents >= 0 && it.days >= 0) } }
        require(items.map { it.id }.distinct().size == items.size) { "商品目录有重复编号" }
        return ShopCatalog(items, body.getLong("balanceCents"))
    }
    suspend fun buy(pending: PendingPurchase): ShopReceipt {
        val (status, raw) = request("/api/shop/buy", "POST", JSONObject()
            .put("itemId", pending.itemId).put("requestId", pending.requestId).toString())
        if (status !in 200..299) {
            val body = runCatching { JSONObject(raw) }.getOrNull()
            error(if (body?.optString("error") == "insufficient_balance")
                "余额不足，还差 ${AccountApi.yuan(body.getLong("needCents"))}；请先兑换余额券。"
            else AccountApi.httpErr(status, raw))
        }
        val body = JSONObject(raw)
        return ShopReceipt(body.getString("orderId"), body.getString("code"), body.getLong("priceCents"),
            body.getLong("balanceCents"), body.optBoolean("replay")).also {
            check(it.orderId.isNotBlank() && it.code.isNotBlank() && it.cents >= 0) { "购买结果不完整，请保留原请求核对" }
        }
    }
}
