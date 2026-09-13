package app.yxi.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AuthCallbackSocketTest {
    private fun fixture(block: (ServerSocket, java.util.concurrent.Future<Triple<String, String, String>>) -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        try {
            val result = pool.submit<Triple<String, String, String>> { MeAuth.awaitCallback(server, "expected", 3000, 150) }
            block(server, result)
        } finally { server.close(); pool.shutdownNow() }
    }
    private fun request(server: ServerSocket, target: String): String = Socket("127.0.0.1", server.localPort).use {
        it.soTimeout = 2000
        it.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
        it.getInputStream().bufferedReader().readText()
    }
    @Test fun `unrelated and stale callbacks do not terminate the valid login`() = fixture { server, result ->
        assertTrue(request(server, "/favicon.ico").startsWith("HTTP/1.1 400"))
        assertTrue(request(server, "/callback?state=wrong&code=ignored").startsWith("HTTP/1.1 400"))
        assertTrue(request(server, "/callback?state=expected&state=wrong&code=ignored").startsWith("HTTP/1.1 400"))
        assertFalse(result.isDone)
        assertTrue(request(server, "/callback?state=expected&code=valid").contains("授权已收到"))
        assertEquals(Triple("valid", "expected", ""), result.get(2, TimeUnit.SECONDS))
    }
    @Test fun `idle connection expires without blocking the next callback`() = fixture { server, result ->
        Socket("127.0.0.1", server.localPort).use {
            Thread.sleep(250)
            request(server, "/callback?state=expected&error=access_denied")
        }
        assertEquals("access_denied", result.get(2, TimeUnit.SECONDS).third)
    }
    @Test fun `closing the listener cancels the wait`() = fixture { server, result ->
        server.close()
        assertFails { result.get(2, TimeUnit.SECONDS) }
    }
}
