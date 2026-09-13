package app.yxi.desktop

import kotlin.test.*

class ReadyPreviewAddressTest {
    @Test fun `verified address preserves encoded route query and fragment`() {
        assertEquals("http://127.0.0.1:3000/a%2Fb?q=x%2By#here%20there", readyPreviewAddress(3000, "http://localhost:3000/a%2Fb?q=x%2By#here%20there", "127.0.0.1"))
    }
    @Test fun `IPv6 is explicit instead of resolving an ambiguous localhost`() {
        assertEquals("http://[::1]:3000/", readyPreviewAddress(3000, null, "::1"))
        assertEquals("http://[::1]:3000/app", readyPreviewAddress(3000, "http://localhost:3000/app", "::1"))
        assertNull(readyPreviewAddress(3000, "http://127.0.0.1:3000/", "::1"))
    }
    @Test fun `unrelated endpoints cannot be auto opened by service readiness`() {
        assertNull(readyPreviewAddress(3000, "https://example.com/", "127.0.0.1"))
        assertNull(readyPreviewAddress(3000, "http://localhost:4000/", "127.0.0.1"))
        assertNull(readyPreviewAddress(3000, null, "example.com"))
    }
}
