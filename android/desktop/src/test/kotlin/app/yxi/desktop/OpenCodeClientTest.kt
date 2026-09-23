package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.*

class OpenCodeClientTest {
    @Test fun `native session lifecycle uses directory auth and explicit model`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val key = exchange.requestMethod + " " + exchange.requestURI.path
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            synchronized(calls) { calls += key to body }
            val authorized = exchange.requestHeaders.getFirst("Authorization") == "Basic " +
                Base64.getEncoder().encodeToString("opencode:fixture-secret".toByteArray())
            val scoped = exchange.requestURI.rawQuery.contains("directory=C%3A%5Cwork+space")
            val response = when (key) {
                "GET /global/health" -> "{\"healthy\":true,\"version\":\"fixture\"}"
                "GET /provider" -> "{\"connected\":[\"opencode\"],\"all\":[],\"default\":{}}"
                "POST /session" -> "{\"id\":\"ses_fixture\",\"title\":\"新对话\"}"
                "POST /session/ses_fixture/abort" -> "true"
                else -> "{}"
            }.toByteArray(Charsets.UTF_8)
            if (!authorized || !scoped) exchange.sendResponseHeaders(403, -1)
            else if (key.endsWith("/prompt_async")) exchange.sendResponseHeaders(204, -1)
            else { exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response) }
            exchange.close()
        }
        server.start()
        try {
            val client = OpenCodeClient(server.address.port, "fixture-secret", "C:\\work space")
            assertTrue(client.health().getBoolean("healthy"))
            assertEquals("opencode", client.providers().getJSONArray("connected").getString(0))
            val id = client.create("新对话").getString("id")
            client.send(id, "你好", "opencode", "fixture-model")
            assertTrue(client.abort(id))
            val sent = JSONObject(calls.single { it.first.endsWith("/prompt_async") }.second)
            assertEquals("fixture-model", sent.getJSONObject("model").getString("modelID"))
            assertEquals("你好", sent.getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals(5, calls.size)
        } finally { server.stop(0) }
    }

    @Test fun `redirected mutations are not followed or retried`() = runBlocking {
        var count = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            count++
            exchange.responseHeaders.set("Location", "/another-endpoint")
            exchange.sendResponseHeaders(307, -1); exchange.close()
        }
        server.start()
        try {
            assertFailsWith<IllegalStateException> { OpenCodeClient(server.address.port, "secret", "/tmp").create("task") }
            assertEquals(1, count)
        } finally { server.stop(0) }
    }
}
