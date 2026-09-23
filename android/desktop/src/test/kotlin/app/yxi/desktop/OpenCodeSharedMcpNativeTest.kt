package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class OpenCodeSharedMcpNativeTest {
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
