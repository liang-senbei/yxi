package app.yxi.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 商城 + 自动续费。契约 `logto_yxi/design/wallet-mail.md` §7（2026-09-05 老板定，服务端已上线）。
 *
 * ⚠️ **价格照抄服务端**（`purchase.json` 唯一出处），客户端不算、不写死。
 * ⚠️ **买东西会扣钱，`requestId` 必填且重试要用同一个**（同抽奖）：同 requestId 回同一张码 + `replay:true`。
 * ⚠️ 余额不足是 `400 insufficient_balance` 带 `needCents` —— 要读出来告诉用户差多少，所以走 [Account.apiRaw]。
 * ⚠️ 买到的是一张**不记名码**（谁拿到谁能兑，可以送人）；同时也经站内信附件送达。
 */
object Shop {
    data class Item(val id: String, val name: String, val tier: String, val days: Int, val priceCents: Long)
    data class Catalog(val items: List<Item>, val balanceCents: Long)
    data class Order(val orderId: String, val itemId: String, val priceCents: Long, val code: String, val at: String)

    /** 买的结果：[code] 就是那张码；[replay] = 这次是重试命中了幂等，**没有再扣钱**。 */
    data class Bought(val orderId: String, val code: String, val tier: String, val days: Int, val priceCents: Long, val balanceCents: Long, val replay: Boolean)

    /** 买失败的两种要分开说：余额不足（差多少）/ 别的 */
    sealed interface BuyError {
        data class Insufficient(val needCents: Long, val balanceCents: Long) : BuyError
        data class Other(val msg: String) : BuyError
    }

    suspend fun catalog(ctx: Context): Catalog? = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, "/api/shop") ?: return@withContext null
        runCatching {
            val a = o.optJSONArray("items")
            Catalog(
                (0 until (a?.length() ?: 0)).mapNotNull { i ->
                    a?.optJSONObject(i)?.let { Item(it.optString("id"), it.optString("name"), it.optString("tier"), it.optInt("days"), it.optLong("priceCents")) }
                },
                o.optLong("balanceCents"),
            )
        }.getOrNull()
    }

    suspend fun buy(ctx: Context, itemId: String, requestId: String): Result<Bought> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("itemId", itemId).put("requestId", requestId).toString()
        val (c, resp) = Account.apiRaw(ctx, "/api/shop/buy", "POST", body)
            ?: return@withContext Result.failure(BuyException(BuyError.Other("没登录")))
        val o = runCatching { JSONObject(resp) }.getOrNull()
        if (c !in 200..299) {
            val e = if (o?.optString("error") == "insufficient_balance")
                BuyError.Insufficient(o.optLong("needCents"), o.optLong("balanceCents"))
            else BuyError.Other(o?.optString("message")?.takeIf { it.isNotBlank() } ?: o?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $c")
            return@withContext Result.failure(BuyException(e))
        }
        if (o == null) return@withContext Result.failure(BuyException(BuyError.Other("读不懂服务器的回复")))
        val b = Bought(
            o.optString("orderId"), o.optString("code"), o.optString("tier"), o.optInt("days"),
            o.optLong("priceCents"), o.optLong("balanceCents"), o.optBoolean("replay"),
        )
        // ⚠️ 就地把余额刷进 me，别等下一次 /api/me（跟签到、领取一样的规矩）
        Account.patchMe { m -> m.copy(balanceCents = b.balanceCents) }
        Result.success(b)
    }

    class BuyException(val err: BuyError) : Exception()

    suspend fun orders(ctx: Context): List<Order>? = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, "/api/shop/orders") ?: return@withContext null
        val a = o.optJSONArray("items") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Order(it.optString("orderId"), it.optString("itemId"), it.optLong("priceCents"), it.optString("code"), it.optString("at")) }
        }
    }

    /** 开 / 关自动续费。成功回 null，失败回原因。成功后就地更新 me.autoRenew。 */
    suspend fun setAutoRenew(ctx: Context, on: Boolean): String? = withContext(Dispatchers.IO) {
        val (c, resp) = Account.apiRaw(ctx, "/api/me/wallet", "PATCH", JSONObject().put("autoRenew", on).toString())
            ?: return@withContext "没登录"
        if (c !in 200..299) return@withContext "HTTP $c"
        val w = runCatching { JSONObject(resp) }.getOrNull()
        Account.patchMe { m ->
            m.copy(
                autoRenew = w?.optBoolean("autoRenew", on) ?: on,
                autoRenewPriceCents = w?.let { if (it.isNull("autoRenewPriceCents")) m.autoRenewPriceCents else it.optLong("autoRenewPriceCents") } ?: m.autoRenewPriceCents,
            )
        }
        null
    }
}
