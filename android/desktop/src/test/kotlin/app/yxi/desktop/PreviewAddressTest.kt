package app.yxi.desktop

import kotlin.test.*

class PreviewAddressTest {
    @Test fun `remote address keeps encoded path and logical port`() {
        val target = PreviewAddress.parse("http://localhost:3000/a%2Fb?q=x%20y#part")
        assertEquals(3000, target.remotePort)
        assertEquals("http://127.0.0.1:54321/a%2Fb?q=x%20y#part", target.forwarded(54321))
        assertEquals("http://localhost:3000/changed", target.logical("http://127.0.0.1:54321/changed", 54321))
    }
    @Test fun `remote IPv6 is not silently rewritten to IPv4`() {
        val target = PreviewAddress.parse("http://[::1]:8080/")
        assertEquals("::1", target.remoteHost)
        assertEquals("http://[::1]:8080/next", target.logical("http://127.0.0.1:50000/next", 50000))
    }
    @Test fun `ports and credentials are validated before opening`() {
        assertEquals(3000, PreviewAddress.parse("3000").remotePort)
        listOf("0", "65536", "file:///secret", "https://user:password@example.com").forEach { assertFails { PreviewAddress.parse(it) } }
        assertNull(PreviewAddress.parse("https://example.com").remotePort)
    }
    @Test fun `fixture mode cannot access external websites or local files`() {
        assertTrue(PreviewAddress.allowed("http://127.0.0.1:3000", fixture = true))
        assertFalse(PreviewAddress.allowed("https://127.0.0.1.example.com", fixture = true))
        assertFalse(PreviewAddress.allowed("https://example.com", fixture = true))
        assertFalse(PreviewAddress.allowed("file:///etc/passwd", fixture = true, resource = true))
        assertFalse(PreviewAddress.allowed("javascript:alert(1)"))
        assertTrue(PreviewAddress.allowed("https://example.com"))
    }
}
