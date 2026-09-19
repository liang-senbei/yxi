package app.yxi.desktop

import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * b338f04/7474fe6 插件人工核对（review）客户端 ledger 定向检查：假 JSON、临时文件，
 * 零真实插件操作、零 SSH。钉死：reviewed 持久化并解除 unresolved 阻塞、旧 unknown
 * 回执不得降级 reviewed/rejected、review 守卫（跨主机/非 unknown/进行中一律不动）、
 * 计划层接受 review/reviewed 且拒绝未知状态。
 */
class PluginReviewLedgerTest {
    private fun tempLedger(): Pair<PluginOperations, java.io.File> {
        val file = Files.createTempDirectory("yxi-plugin-review").resolve("operations.json").toFile()
        return PluginOperations(file) to file
    }

    private fun conn() = Conn(Host("h-主机A", "主机A", "127.0.0.1", 1), NoHostKeys)

    /** 人工核对后：状态落盘 reviewed、主机解除 unresolved 阻塞、可再发起新操作；重启后仍是 reviewed。 */
    @Test fun `reviewed persists clears unresolved and reopens the host`() {
        val (ledger, file) = tempLedger()
        val op = "b".repeat(32)
        ledger.begin("host-a", JSONObject().put("operation", op).put("action", "set"))
        val reopened = PluginOperations(file)                       // sending → unknown（重启映射）
        assertEquals("unknown", reopened.latest("host-a")!!.status)
        assertTrue(reopened.unresolved("host-a"))
        assertFailsWith<IllegalStateException> { reopened.begin("host-a", JSONObject().put("operation", "c".repeat(32))) }
        reopened.finish(op, "reviewed")                             // 服务器回 reviewed
        assertEquals("reviewed", reopened.latest("host-a")!!.status)
        assertFalse(reopened.unresolved("host-a"), "reviewed 不再视为未决")
        reopened.begin("host-a", JSONObject().put("operation", "c".repeat(32)))   // 主机解锁
        val again = PluginOperations(file)
        assertEquals("reviewed", again.history("host-a").last().status, "reviewed 必须持久化")
    }

    /** 迟到的 unknown 回执（原操作查询失败等）不得把 reviewed/rejected 打回待确认。 */
    @Test fun `late unknown receipt cannot downgrade reviewed or rejected`() {
        val (ledger, file) = tempLedger()
        ledger.begin("host-a", JSONObject().put("operation", "d".repeat(32)))
        ledger.finish("d".repeat(32), "reviewed")
        ledger.begin("host-a", JSONObject().put("operation", "e".repeat(32)))
        ledger.finish("e".repeat(32), "changed")                    // rejected
        ledger.finish("d".repeat(32), "unknown")
        ledger.finish("e".repeat(32), "unknown")
        val statuses = PluginOperations(file).history("host-a").associate { it.id to it.status }
        assertEquals("reviewed", statuses["d".repeat(32)])
        assertEquals("rejected", statuses["e".repeat(32)])
    }

    /** review 守卫：跨主机拒绝；非 unknown、进行中一律静默不动（不发任何请求）。 */
    @Test fun `review guard refuses foreign host and non-unknown or running entries`() {
        val (ledger, _) = tempLedger()
        val c = conn()
        val host = projectKey(c.host, "/")
        val op = "f".repeat(32)
        ledger.begin(host, JSONObject().put("operation", op))
        assertFailsWith<IllegalStateException> {
            ledger.review(c, PluginOperationEntry("host-b", op, JSONObject().put("operation", op).toString(), "unknown"))
        }
        val onHost = PluginOperationEntry(host, op, JSONObject().put("operation", op).toString(), "reviewed")
        ledger.review(c, onHost)                                    // 非 unknown：静默返回
        val running = PluginOperationEntry(host, op, JSONObject().put("operation", op).toString(), "unknown")
        ledger.running.add(op)
        ledger.review(c, running)                                   // 进行中：静默返回
        assertTrue(ledger.running.contains(op), "守卫不应改变进行中状态")
    }

    /** 计划层：review 动作可下发、reviewed 回执可解析；未知状态与非法动作照旧拒绝。 */
    @Test fun `plan carries review action and accepts reviewed receipt only`() {
        val review = PluginOperationPlan.command(JSONObject().put("action", "review").put("operation", "a".repeat(32)))
        assertTrue("review" in review)
        assertFailsWith<IllegalArgumentException> {
            PluginOperationPlan.command(JSONObject().put("action", "revoke").put("operation", "a".repeat(32)))
        }
        assertEquals("reviewed", PluginOperationPlan.result("""__YXI_PLUGIN_OP__:{"state":"reviewed","manuallyReviewed":true}""").getString("state"))
        assertFailsWith<IllegalArgumentException> { PluginOperationPlan.result("""__YXI_PLUGIN_OP__:{"state":"confirmed-by-human"}""") }
    }
}
