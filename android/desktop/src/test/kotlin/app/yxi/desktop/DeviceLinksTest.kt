package app.yxi.desktop

import kotlin.test.*

class DeviceLinksTest {
    @Test fun `each server keeps distinct persisted reverse and local ports`() {
        val a = LinkProtocol.allocate("a", emptyList())
        val b = LinkProtocol.allocate("b", listOf(a))
        val c = LinkProtocol.allocate("c", listOf(a, b))
        assertEquals(2222, a.reversePort); assertEquals(5901, a.localPort)
        assertEquals(2223, b.reversePort); assertEquals(5902, b.localPort)
        assertEquals(2224, c.reversePort); assertEquals(5903, c.localPort)
        assertEquals(b, LinkProtocol.allocate("b", listOf(a, b, c)))
    }
    @Test fun `Tailscale online devices are not reported as active connections`() {
        val peers = LinkProtocol.peers("""{"BackendState":"Running","Peer":{
          "a":{"HostName":"online-only","Online":true,"Active":false,"TailscaleIPs":["100.64.0.1"]},
          "b":{"HostName":"active","Online":true,"Active":true,"CurAddr":"203.0.113.1:1234","TailscaleIPs":["100.64.0.2"]},
          "c":{"HostName":"offline","Online":false,"Active":false}}}""")
        assertEquals("active", peers.first().name)
        assertFalse(peers.single { it.id == "a" }.active)
        assertEquals("直连", peers.first().route)
        assertFails { LinkProtocol.peers("""{"BackendState":"NeedsLogin"}""") }
    }
}
