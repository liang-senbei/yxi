package app.yxi.desktop

import app.yxi.agent.AccountApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 03ed140/9292e84 商城结账与购买身份的最少契约核对：假 transport、临时文件，
 * **零真实购买、零扣余额、零兑码**。钉死五件事：金额按分、pending 先落盘、
 * 同 owner 未确认禁止新 ID、重试复用同 ID、明确 receipt 后清记录。
 */
class ShopPurchaseTest {
    private val item = ShopItem("p30", "PRO 30天", "pro", 30, 3000L)

    private fun tempStore(): Triple<ShopPurchaseStore, java.io.File, java.io.File> {
        val dir = Files.createTempDirectory("yxi-shop").toFile()
        val file = java.io.File(dir.absolutePath, "purchases.json")
        return Triple(ShopPurchaseStore(file), file, dir)
    }

    private fun delete(vararg dirs: java.io.File?) = dirs.forEach { it?.deleteRecursively() }

    /** begin 在返回前就把请求身份落盘；同 owner 未确认禁止再开新 ID，别的 owner 不受影响。 */
    @Test fun `begin persists before returning and blocks a second pending for the same owner`() {
        val (store, file, dir) = tempStore()
        try {
            val first = store.begin("u1", item)
            assertTrue(first.requestId.isNotBlank())
            assertTrue(file.exists() && file.readText().contains(first.requestId),
                "pending 必须先落盘：网络发出前身份已在磁盘上")
            val again = assertFailsWith<IllegalStateException> { store.begin("u1", item) }
            assertTrue(again.message!!.contains("请先核对上一笔购买结果"), again.message)
            assertEquals(first.requestId, store.forOwner("u1")!!.requestId, "被拒后原有 ID 原样保留")
            val other = store.begin("u2", item)
            assertTrue(other.requestId != first.requestId, "不同 owner 互不影响")
        } finally { delete(dir) }
    }

    /** 重启（新实例）后仍取到同一请求 ID 供重试；明确 resolved 才清，且只清自己 owner 的。 */
    @Test fun `pending identity survives a restart and resolved clears exactly its owner`() {
        val (store, file, dir) = tempStore()
        try {
            val first = store.begin("u1", item)
            val rebooted = ShopPurchaseStore(file)
            assertEquals(first.requestId, rebooted.forOwner("u1")!!.requestId, "重试必须复用同一请求 ID")
            val foreign = assertFailsWith<IllegalStateException> {
                rebooted.resolved(PendingPurchase("u1", item.id, "别的ID", 3000L))
            }
            assertTrue(foreign.message!!.contains("购买记录已变化"), foreign.message)
            rebooted.resolved(rebooted.forOwner("u1")!!)
            assertEquals(null, rebooted.forOwner("u1"), "明确 receipt 后清记录")
            assertTrue(ShopPurchaseStore(file).pending.isEmpty(), "磁盘同步清空")
        } finally { delete(dir) }
    }

    /** 目录与购买结果都按分解析；buy 上送的就是 pending 里的同一 requestId。 */
    @Test fun `shop parses cents and sends the pending request id`() = runBlocking {
        var seen: Triple<String, String, String?>? = null
        val shop = DesktopShop { path, method, body ->
            seen = Triple(path, method, body)
            if (path == "/api/shop") 200 to """{"items":[
                {"id":"p30","name":"PRO 30天","tier":"pro","days":30,"priceCents":3000}],
                "balanceCents":-250}"""
            else 200 to """{"orderId":"ord-1","code":"SHOP-CODE","priceCents":3000,
                "balanceCents":250,"replay":true}"""
        }
        val catalog = shop.catalog()
        assertEquals(3000L, catalog.items.single().cents, "商品金额按分")
        assertEquals(-250L, catalog.balanceCents, "余额按分，可为负")
        val pending = PendingPurchase("u1", "p30", "req-7", 3000L)
        val receipt = shop.buy(pending)
        assertEquals("/api/shop/buy", seen!!.first)
        assertEquals("POST", seen!!.second)
        val sent = JSONObject(seen!!.third!!)
        assertEquals("p30", sent.getString("itemId"))
        assertEquals("req-7", sent.getString("requestId"), "上送的就是落盘的那个请求 ID")
        assertEquals("ord-1", receipt.orderId)
        assertEquals(3000L, receipt.cents)
        assertEquals(250L, receipt.balanceCents)
        assertTrue(receipt.replay, "replay 旗标透传（界面据此说『没有重复扣款』）")
    }

    /** 余额不足给出缺口金额（分→元）；其他错误走服务端 msg 优先的 httpErr。 */
    @Test fun `insufficient balance names the shortfall and other errors pass server message`() = runBlocking {
        val insufficient = DesktopShop { _, _, _ -> 402 to """{"error":"insufficient_balance","needCents":1500}""" }
        val e = assertFailsWith<IllegalStateException> { insufficient.buy(PendingPurchase("u1", "p30", "r", 3000L)) }
        assertTrue(e.message!!.contains("余额不足"), e.message)
        assertTrue(e.message!!.contains(AccountApi.yuan(1500)), e.message)
        val serverMsg = DesktopShop { _, _, _ -> 500 to """{"msg":"商城稍后再试"}""" }
        val e2 = assertFailsWith<IllegalStateException> { serverMsg.buy(PendingPurchase("u1", "p30", "r", 3000L)) }
        assertTrue(e2.message!!.contains("商城稍后再试"), e2.message)
    }
}
