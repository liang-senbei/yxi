package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class OpenCodeMcpHttpHeadersNativeTest {
    @Test fun `native HTTP MCP sends the complete environment header value without persisting it`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val received = CompletableDeferred<Boolean>()
        val invalidHeaders = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestMethod != "POST") {
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    val request = JSONObject(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                    if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-http-token") invalidHeaders.incrementAndGet()
                    if (request.optString("method") == "initialize") received.complete(
                        exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-http-token")
                    if (!request.has("id")) exchange.sendResponseHeaders(202, -1)
                    else {
                        val result = when (request.optString("method")) {
                            "initialize" -> JSONObject().put("protocolVersion", request.getJSONObject("params").getString("protocolVersion"))
                                .put("capabilities", JSONObject().put("tools", JSONObject()))
                                .put("serverInfo", JSONObject().put("name", "fixture").put("version", "1"))
                            "tools/list" -> JSONObject().put("tools", JSONArray())
                            else -> JSONObject()
                        }
                        val body = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString().toByteArray()
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val root = File("/sandbox/home/opencode-http").apply { mkdirs() }
            val config = File(root, "opencode.json").apply { writeText("""{"${'$'}schema":"https://opencode.ai/config.json","provider":{"fixture":{"npm":"@ai-sdk/openai-compatible","options":{"baseURL":"http://127.0.0.1:9/v1","apiKey":"fixture"},"models":{"fixture-model":{"name":"Fixture"}}}}}""") }
            val original = config.readBytes()
            val definition = SharedMcpDefinition("@local", "http", "fixture", "1", "http_fixture",
                url = "http://127.0.0.1:${server.address.port}/mcp", headerVariables = mapOf("Authorization" to "YXI_TEST_MCP_AUTH"))
            val runtime = LocalRuntimeInstallation("opencode", "isolated", listOf("/opt/native/claude"), "/sandbox/home/.local/share/opencode", "1.18.32")
            val inherited = System.getenv() + ("YXI_TEST_MCP_AUTH" to "Bearer fixture-http-token")
            assertFalse(OpenCodeStartupMcp.configuration(listOf(definition)).contains("fixture-http-token"))
            assertFailsWith<IllegalArgumentException> { OpenCodeStartupMcp.environment(emptyMap(), listOf(definition)) }
            assertFailsWith<IllegalArgumentException> {
                OpenCodeStartupMcp.environment(inherited + ("OPENCODE_CONFIG_CONTENT" to "{}"), listOf(definition))
            }
            LocalOpenCodeServer.start(runtime, root, inherited, sharedMcp = listOf(definition)).use { owned ->
                assertEquals("connected", owned.client.mcpStatus().getJSONObject("http_fixture").getString("status"))
                assertTrue(withTimeout(20000) { received.await() }, "MCP must receive the complete header value")
            }
            assertEquals(0, invalidHeaders.get())
            assertContentEquals(original, config.readBytes())
            val remoteHome = File(root, "remote-home").apply { mkdirs() }
            val bin = File(remoteHome, ".local/bin").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(File(bin, "opencode").toPath(), java.nio.file.Path.of("/opt/native/claude"))
            val remoteEnvironment = mapOf("HOME" to remoteHome.path, "XDG_DATA_HOME" to File(remoteHome, ".local/share").path,
                "XDG_CONFIG_HOME" to File(remoteHome, ".config").path, "XDG_CACHE_HOME" to File(remoteHome, ".cache").path,
                "YXI_TEST_MCP_AUTH" to "Bearer fixture-http-token")
            IsolatedSshBridge(File("/sandbox/tmp/http-mcp-ssh"), remoteEnvironment, File("/sandbox/tmp/http-mcp-unused.sock"), allowForwarding = true).use { bridge ->
                bridge.conn.ssh.connect()
                val remoteRegistry = SharedMcpRegistry(File(root, "remote-shared.json"))
                remoteRegistry.save(definition.copy(hostKey = projectKey(bridge.conn.host, "/")), setOf("opencode"), null)
                RemoteOpenCodeTasks(InstructionQueue(File(root, "remote-queue.json")), File(root, "remote-tasks.json"), remoteRegistry).use { tasks ->
                    val model = tasks.models(bridge.conn, root.path).single { it.providerId == "fixture" && it.modelId == "fixture-model" }
                    val record = tasks.create(bridge.conn, root.path, "Remote HTTP shared task", model)
                    assertTrue(tasks.controllers.getValue(record.key).ready)
                    assertEquals(record, tasks.registry.records.single())
                    assertEquals("", tasks.recoverySessionId)
                }
            }
            assertEquals(0, invalidHeaders.get())
            assertContentEquals(original, config.readBytes())
            assertFalse(definition.json().toString().contains("fixture-http-token"))
            val shared = SharedMcpRegistry(File(root, "shared.json"))
            shared.save(definition, setOf("opencode"), null)
            val queue = InstructionQueue(File(root, "queue.json"))
            val index = File(root, "tasks.json")
            LocalOpenCodeTasks(queue, index, shared, inherited).use { tasks ->
                val model = tasks.models(runtime, root.path).single { it.providerId == "fixture" && it.modelId == "fixture-model" }
                val record = tasks.create(runtime, root.path, "HTTP shared task", model)
                assertEquals(record, tasks.registry.records.single())
                assertTrue(tasks.controllers.containsKey(record.key))
                assertEquals("", tasks.recoverySessionId)
            }
            assertEquals(0, invalidHeaders.get())
            assertContentEquals(original, config.readBytes())
        } finally { server.stop(0) }
    }
}
