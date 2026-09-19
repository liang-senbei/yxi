package app.yxi.desktop

import app.yxi.agent.AccountApi
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * b2dc537 兑换码结果文案的解析核对（W07）：会员 / 余额券 / replay / revoked 四种结果 +
 * 服务端 msg 优先。只测纯函数 [redemptionMessage]，假 JSON、零网络、零真实兑换。
 * 「重复/撤销不能提示重复到账」在这里钉死。
 */
class RedeemCodeTest {
    private fun msg(o: String) = redemptionMessage(JSONObject(o))

    /** 会员结果可识别才显示成功；空回复必须留待核对。 */
    @Test fun `membership result`() {
        assertEquals("兑换成功，会员权益已更新。", msg("""{"kind":"membership"}"""))
        assertFailsWith<IllegalStateException> { msg("{}") }
    }

    /** 余额券：金额/余额取自 amountCents/balanceCents，格式交给 AccountApi.yuan（本地不写死）。 */
    @Test fun `balance voucher shows amount and new balance`() {
        assertEquals(
            "余额到账 ${AccountApi.yuan(2500)}，当前余额 ${AccountApi.yuan(9900)}",
            msg("""{"kind":"balance","amountCents":2500,"balanceCents":9900}"""))
    }

    /** 服务端 msg 优先：给了 msg 就原样显示，不再本地拼金额。 */
    @Test fun `server message wins over local wording`() {
        assertEquals("运营活动赠送 10 元",
            msg("""{"msg":"运营活动赠送 10 元","kind":"balance","amountCents":1,"balanceCents":2}"""))
    }

    /** replay：明说「没有重复增加」，绝不出现「到账」/「兑换成功」——不能提示重复到账。 */
    @Test fun `replay never claims credit`() {
        val m = msg("""{"replay":true}""")
        assertTrue(m.contains("已经兑换过"), m)
        assertTrue(m.contains("没有重复增加"), m)
        assertFalse(m.contains("到账"), "replay 不得提示到账：$m")
        assertFalse(m.contains("兑换成功"), "replay 不得提示成功：$m")
    }

    /** revoked：撤销文案，同样不得出现到账/成功。 */
    @Test fun `revoked never claims credit`() {
        val m = msg("""{"revoked":true}""")
        assertTrue(m.contains("撤销"), m)
        assertFalse(m.contains("到账"), m)
        assertFalse(m.contains("兑换成功"), m)
    }

    /** 撤销优先于重复：revoked 与 replay 同时在时按撤销说，不说「已经兑换过」就完事。 */
    @Test fun `revoked takes precedence over replay`() {
        val m = msg("""{"revoked":true,"replay":true}""")
        assertTrue(m.contains("撤销"), m)
        assertFalse(m.contains("已经兑换过"), m)
    }

    /** 服务端 msg 同样覆盖 revoked/replay：撤销/重复时服务端自己的说明优先。 */
    @Test fun `server message covers replay and revoked`() {
        assertEquals("该码已被风控冻结", msg("""{"msg":"该码已被风控冻结","revoked":true}"""))
        assertEquals("重复提交", msg("""{"msg":"重复提交","replay":true}"""))
    }
}
