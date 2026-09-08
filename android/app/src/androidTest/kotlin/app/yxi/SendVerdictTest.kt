package app.yxi

import app.yxi.agent.SessionProbe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「到底发出去了没」的判断（#323）。
 *
 * ⚠️ 这一条错一次就是**一条话凭空消失**：判成功 → 草稿清掉、气泡画上，而话哪儿都不在。
 */
class SendVerdictTest {

    @Test fun 输入框空了_算发出去了() {
        assertEquals(true, SessionProbe.sent(inputEmpty = true, alive = true))
        // 连接刚断但屏已经确认空了 —— 那是真提交了，不该反悔
        assertEquals(true, SessionProbe.sent(inputEmpty = true, alive = false))
    }

    @Test fun 还卡在输入框里_没发出去() {
        assertEquals(false, SessionProbe.sent(inputEmpty = false, alive = true))
        assertEquals(false, SessionProbe.sent(inputEmpty = false, alive = false))
    }

    /** 连接还活着时的空屏 = 真的判断不了，别谎报失败（#276 那条老规矩） */
    @Test fun 连接还在_读不到屏_不谎报失败() {
        assertEquals(true, SessionProbe.sent(inputEmpty = null, alive = true))
    }

    /**
     * ⚠️ **这条是 #323 的核心**：`exec` 在死连接上返回空串，空串解析出来就是 null。
     * 原来 null 一律返回 true —— 断线时发话**必报成功**，话静默丢掉。
     */
    @Test fun 连接断了_读不到屏_算没发出去() {
        assertEquals(false, SessionProbe.sent(inputEmpty = null, alive = false))
    }
}
