package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class OpenCodeSharedMcpNativeTest {
    @Test fun `native OpenCode invokes dynamically shared MCP and returns the actual result to its model`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val root = File("/sandbox/home/shared-mcp-call").apply { mkdirs() }
        fun resource(name: String) = File(root, name).apply { writeBytes(requireNotNull(OpenCodeSharedMcpNativeTest::class.java.getResourceAsStream("/$name")).use { it.readBytes() }) }
        val mcp = resource("shared_mcp_fixture.py")
        val stub = resource("opencode_mcp_stub.py")
        val provider = ProcessBuilder("python3", stub.path, root.path).redirectErrorStream(true).redirectOutput(File(root, "provider.log")).start()
        try {
            withTimeout(10000) { while (!File(root, "port").isFile) delay(50) }
            val config = File(root, "opencode.json").apply { writeText(JSONObject().put("${'$'}schema", "https://opencode.ai/config.json")
                .put("model", "fixture/fixture-model").put("small_model", "fixture/fixture-model").put("permission", JSONObject().put("*", "ask"))
                .put("provider", JSONObject().put("fixture", JSONObject().put("npm", "@ai-sdk/openai-compatible")
                    .put("options", JSONObject().put("baseURL", "http://127.0.0.1:${File(root, "port").readText().trim()}/v1").put("apiKey", "fixture"))
                    .put("models", JSONObject().put("fixture-model", JSONObject().put("name", "Fixture"))))).toString()) }
            val before = config.readBytes()
            val definition = SharedMcpDefinition("machine", "shared-echo", "yxi-test", "0.1.0", "shared_echo",
                listOf("/usr/bin/python3", "-c", "import os,runpy,sys; assert os.environ.get('YXI_MCP_FIXTURE_VALUE') == 'fixture-inherited'; runpy.run_path(sys.argv[1], run_name='__main__')", mcp.path),
                environmentNames = setOf("YXI_MCP_FIXTURE_VALUE"))
            val record = SharedMcpRegistry(File(root, "registry.json")).save(definition, setOf("claude", "codex", "opencode"), null)
            val runtime = LocalRuntimeInstallation("opencode", "isolated", listOf("/opt/native/claude"), "/sandbox/home/.local/share/opencode", "1.18.32")
            LocalOpenCodeServer.start(runtime, root, System.getenv() + ("YXI_MCP_FIXTURE_VALUE" to "fixture-inherited")).use { server ->
                assertEquals("connected", OpenCodeMcpBindings("machine", "owned", server.client, File(root, "binding.json")).apply(record))
                val session = server.client.create("Native shared MCP call").getString("id")
                val queue = InstructionQueue(File(root, "queue.json"))
                val controller = OpenCodeTaskController("fixture-task", session, root.path, "fixture", "fixture-model", server.client, queue)
                controller.enqueue("Call shared echo with opencode-native-call.")
                try {
                    controller.sendNext()
                    withTimeout(45000) {
                        while (queue.entries.single().runtimeTurnState != RuntimeTurnState.Completed) {
                            delay(150); controller.refresh()
                            controller.permissions.toList().forEach { permission ->
                                check(permission.getString("permission").contains("shared_echo")) { "Unexpected permission: $permission" }
                                controller.replyPermission(permission.getString("id"), OpenCodePermissionReply.Once)
                            }
                        }
                    }
                    assertTrue(File(root, "calls.jsonl").readLines().map(::JSONObject).any { it.optBoolean("result_confirmed") })
                    assertTrue(controller.messages.any { it.toString().contains("SHARED_MCP_NATIVE_CONFIRMED") })
                    assertContentEquals(before, config.readBytes())
                    println("Real OpenCode called shared MCP, received its echo result, and completed the native turn.")
                } catch (e: Throwable) {
                    File("/results/shared-mcp-call-diagnostics.json").writeText(controller.messages.toString())
                    throw e
                }
            }
        } finally { LocalRuntimeDiscovery.stopOwnedProcess(provider) }
    }
    @Test fun `native OpenCode loads the shared stdio resource without changing project configuration`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val project = File("/sandbox/home/shared-mcp-native").apply { mkdirs() }
        // OpenCode automatically adds its schema to schema-less files at startup.
        // Start with a complete config so byte equality isolates MCP apply writes.
        val config = File(project, "opencode.json").apply { writeText(JSONObject().put("${'$'}schema", "https://opencode.ai/config.json").put("share", "disabled").toString()) }
        val original = config.readBytes()
        val script = File(project, "shared_mcp_fixture.py").apply {
            writeBytes(requireNotNull(OpenCodeSharedMcpNativeTest::class.java.getResourceAsStream("/shared_mcp_fixture.py")).use { it.readBytes() })
        }
        val resource = SharedMcpDefinition("local-fixture", "echo", "yxi-test", "0.1.0", "shared_echo", listOf("/usr/bin/python3", script.path))
        val registry = SharedMcpRegistry(File(project, "shared-registry.json"))
        val record = registry.save(resource, setOf("claude", "codex", "opencode"), null)
        val runtime = LocalRuntimeInstallation("opencode", "isolated-native", listOf("/opt/native/claude"), "/sandbox/home/.local/share/opencode", "1.18.32")
        LocalOpenCodeServer.start(runtime, project).use { server ->
            val journal = File(project, "binding.json")
            val binding = OpenCodeMcpBindings("local-fixture", "owned-service", server.client, journal)
            assertEquals("connected", binding.apply(record))
            assertEquals("connected", server.client.mcpStatus().getJSONObject("shared_echo").getString("status"))
            assertEquals("connected", JSONObject(journal.readText()).getString("phase"))
            assertContentEquals(original, config.readBytes())
            assertEquals(resource, registry.records.single().definition)
            assertFailsWith<IllegalStateException> { binding.apply(record) }
            println("Native OpenCode 1.18.32 initialized shared MCP fixture and discovered tools; project config unchanged. No model call performed.")
        }
    }
}
