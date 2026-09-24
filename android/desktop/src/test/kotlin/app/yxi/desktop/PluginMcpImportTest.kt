package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import org.json.JSONObject
import kotlin.test.*

class PluginMcpImportTest {
    @TempDir lateinit var root: java.io.File
    @Test fun `scoped npm package remains a package argument rather than relative resource path`() {
        root.resolve(".mcp.json").writeText("""{"mcpServers":{"fixture":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem@1.0.0"]}}}""")
        assertEquals(listOf("npx", "-y", "@modelcontextprotocol/server-filesystem@1.0.0"),
            PluginMcpImport.read(plugin(root)).single().definition.command)
    }
    @Test fun `plugin root paths resolve while ambiguous relative paths and escapes fail`() {
        val script = root.resolve("tokenizer.js").apply { writeText("fixture") }
        val file = root.resolve(".mcp.json")
        fun manifest(argument: String) = file.writeText(JSONObject().put("mcpServers", JSONObject().put("fixture",
            JSONObject().put("command", "node").put("args", org.json.JSONArray(listOf(argument))))).toString())
        manifest("${'$'}{CLAUDE_PLUGIN_ROOT}/tokenizer.js")
        assertEquals(listOf("node", script.canonicalPath), PluginMcpImport.read(plugin(root)).single().definition.command)
        listOf("./tokenizer.js", "tokenizer.js", "${'$'}{CLAUDE_PLUGIN_ROOT}/../outside.js", "--token=synthetic").forEach {
            manifest(it); assertFails { PluginMcpImport.read(plugin(root)) }
        }
    }
    @Test fun `unsupported root fields and mistyped routing fields are refused`() {
        val file = root.resolve(".mcp.json")
        listOf(
            """{"inputs":[],"mcpServers":{"fixture":{"command":"node"}}}""",
            """{"mcpServers":{"fixture":{"command":false,"url":"https://example.org/mcp"}}}""",
            """{"mcpServers":{"fixture":{"command":"node","url":null}}}""",
            """{"mcpServers":{"fixture":{"url":"https://example.org/mcp","args":["ignored"]}}}"""
        ).forEach { file.writeText(it); assertFails { PluginMcpImport.read(plugin(root)) } }
    }
    private fun plugin(root: java.io.File) = NativePlugin("sample@fixture", "sample", "Sample", "", "", "Fixture", null,
        "1", true, true, false, null, JSONObject().put("type", "local").put("path", root.path).toString(), "", "fixture")
    @Test fun `local HTTP references preview and register without copying credentials`() {

            root.resolve(".mcp.json").writeText("""{"mcpServers":{"fixture":{"url":"https://example.org/mcp","headers":{"Authorization":"${'$'}{PLUGIN_TOKEN}"}}}}""")
            val p = plugin(root); val preview = PluginMcpImport.read(p).single()
            assertEquals(mapOf("Authorization" to "PLUGIN_TOKEN"), preview.definition.headerVariables)
            val file = root.resolve("registry.json"); val registry = SharedMcpRegistry(file)
            PluginMcpImport.confirm(p, preview, registry, setOf("claude", "codex", "opencode"))
            assertEquals(3, registry.records.single().desiredRunners.size)
            assertEquals("@local", registry.records.single().definition.hostKey)
            assertFalse(file.readText().contains("Bearer"))
            assertFails { PluginMcpImport.confirm(p, preview, registry, setOf("codex")) }

    }
    @Test fun `literal secrets and changes after preview refuse registration`() {

            val manifest = root.resolve(".mcp.json"); val p = plugin(root)
            manifest.writeText("""{"mcpServers":{"fixture":{"url":"https://example.org/mcp","headers":{"Authorization":"Bearer synthetic-secret"}}}}""")
            assertFails { PluginMcpImport.read(p) }
            manifest.writeText("""{"mcpServers":{"fixture":{"command":"fixture-server","env":{"PLUGIN_TOKEN":"${'$'}{PLUGIN_TOKEN}"}}}}""")
            val preview = PluginMcpImport.read(p).single()
            assertEquals(setOf("PLUGIN_TOKEN"), preview.definition.environmentNames)
            manifest.writeText("""{"mcpServers":{"fixture":{"command":"different-server"}}}""")
            val registry = SharedMcpRegistry(root.resolve("registry.json"))
            assertFails { PluginMcpImport.confirm(p, preview, registry, setOf("codex")) }
            assertTrue(registry.records.isEmpty())

    }
    @Test fun `remote connector and unsupported transport are not fabricated`() {

            val p = plugin(root)
            assertFails { PluginMcpImport.read(p.copy(source = """{"type":"remote"}""")) }
            root.resolve(".mcp.json").writeText("""{"mcpServers":{"fixture":{"type":"sse","url":"https://example.org/mcp"}}}""")
            assertFails { PluginMcpImport.read(p) }

    }
}
