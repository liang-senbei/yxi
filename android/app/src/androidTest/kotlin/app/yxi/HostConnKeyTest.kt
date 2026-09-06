package app.yxi

import app.yxi.ssh.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [Host.connKey]：连接按它记忆。改了凭据 / 地址要变（重连），改了别名 / 指纹 / 铃铛不能变（不重连）。
 * 老板 2026-09-06：「改了密钥进会话还是认证失败」—— 就是因为原来按 id 记。
 */
class HostConnKeyTest {
    private val h = Host("id1", "a", "1.2.3.4", 22, "root", useKey = true)

    @Test fun 改凭据_地址_要变() {
        assertNotEquals(h.connKey, h.copy(useKey = false).connKey)
        assertNotEquals(h.connKey, h.copy(sealedPassword = "sealed").connKey)
        assertNotEquals(h.copy(sealedPassword = "a").connKey, h.copy(sealedPassword = "b").connKey)
        assertNotEquals(h.connKey, h.copy(hostname = "5.6.7.8").connKey)
        assertNotEquals(h.connKey, h.copy(port = 2222).connKey)
        assertNotEquals(h.connKey, h.copy(username = "ubuntu").connKey)
        assertNotEquals(h.connKey, h.copy(tailscaleIp = "100.1.1.1", useTailscale = true).connKey)   // 切内网 = 换地址
    }

    @Test fun 改别名_指纹_铃铛_不变() {
        assertEquals(h.connKey, h.copy(alias = "b").connKey)
        assertEquals(h.connKey, h.copy(hostKey = "AAAA").connKey)          // 首次连上才写回，进了键会连完立刻再连
        assertEquals(h.connKey, h.copy(watch = true).connKey)
        assertEquals(h.connKey, h.copy(tailscaleIp = "100.1.1.1", useTailscale = false).connKey)   // 填了内网 IP 但没开
    }
}
