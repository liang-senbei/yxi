package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class OpenCodeMcpBindingsTest {
    @TempDir lateinit var root: File
    @Test fun `runtime MCP application reads status and cannot repeat an ambiguous write`() = runBlocking {
        var writes = 0
        var status = JSONObject()
        var failReply = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "POST") {
                writes++
                val body = JSONObject(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                status.put(body.getString("name"), JSONObject().put("status", "connected"))
                if (failReply) { exchange.sendResponseHeaders(500, -1); exchange.close(); return@createContext }
            }
            val bytes = status.toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes); exchange.close()
        }
        server.start()
        try {
            val client = OpenCodeClient(server.address.port, "fixture", "/fixture")
            val definition = SharedMcpDefinition("machine-a", "echo", "fixture", "1", "echo", listOf("python3", "fixture.py"))
            val record = SharedMcpRecord(definition, setOf("opencode"), 0)
            val wrongHost = OpenCodeMcpBindings("machine-b", "service", client, File(root, "wrong.json"))
            assertFailsWith<IllegalArgumentException> { wrongHost.apply(record) }
            assertEquals(0, writes)
            val binding = OpenCodeMcpBindings("machine-a", "service", client, File(root, "ok.json"))
            assertEquals("connected", binding.apply(record))
            assertEquals("connected", binding.observedStatus())
            assertEquals("connected", JSONObject(File(root, "ok.json").readText()).getString("phase"))
            assertFailsWith<IllegalStateException> { binding.apply(record) }
            val collision = OpenCodeMcpBindings("machine-a", "service", client, File(root, "collision.json"))
            assertFailsWith<IllegalStateException> { collision.apply(record) }
            assertEquals(1, writes)
            failReply = true
            val uncertainFile = File(root, "unknown.json")
            val uncertain = OpenCodeMcpBindings("machine-a", "service", client, uncertainFile)
            val another = record.copy(definition = definition.copy(pluginId = "another", name = "another"))
            assertFailsWith<IllegalStateException> { uncertain.apply(another) }
            assertEquals("unknown", JSONObject(uncertainFile.readText()).getString("phase"))
            val restored = OpenCodeMcpBindings("machine-a", "service", client, uncertainFile)
            assertEquals("connected", restored.observedStatus())
            assertFailsWith<IllegalStateException> { restored.apply(another) }
            assertFailsWith<IllegalStateException> { OpenCodeMcpBindings("machine-a", "other-service", client, uncertainFile).apply(another) }
            assertEquals(2, writes)
        } finally { server.stop(0) }
    }
}
