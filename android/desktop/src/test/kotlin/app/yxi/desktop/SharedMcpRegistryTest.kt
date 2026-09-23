package app.yxi.desktop

import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class SharedMcpRegistryTest {
    @Test fun `version replacement rollback retirement and restore preserve history and current runner choices`() {
        val file = File(root, "versions.json")
        val registry = SharedMcpRegistry(file)
        val first = registry.save(definition(), setOf("claude", "opencode"), null)
        val secondDefinition = definition().copy(version = "2", command = listOf("version-two", "literal argument"))
        val second = registry.replaceVersion(first.definition.key, first.revision, secondDefinition)
        assertEquals(2, registry.records.size)
        assertEquals(second, registry.forHost("local").single())
        assertTrue(registry.records.single { it.definition.version == "1" }.retired)
        assertFailsWith<IllegalStateException> { registry.replaceVersion(first.definition.key, first.revision, secondDefinition) }
        val edited = registry.save(secondDefinition, setOf("codex"), second.revision)
        val rollback = registry.replaceVersion(edited.definition.key, edited.revision, first.definition)
        assertEquals(setOf("codex"), rollback.desiredRunners)
        assertEquals(first.definition, rollback.definition)
        assertFailsWith<IllegalStateException> { registry.replaceVersion(rollback.definition.key, rollback.revision, secondDefinition.copy(command = listOf("tampered"))) }
        registry.retire(rollback.definition.key, rollback.revision)
        assertTrue(registry.forHost("local").isEmpty())
        val restoredStore = SharedMcpRegistry(file)
        assertEquals(2, restoredStore.forHost("local", includeRetired = true).size)
        val retired = restoredStore.records.single { it.definition.version == "1" }
        val restored = restoredStore.restore(retired.definition.key, retired.revision)
        assertEquals(setOf("codex"), restored.desiredRunners)
        val otherVersion = restoredStore.records.single { it.definition.version == "2" }
        assertFailsWith<IllegalStateException> { restoredStore.restore(otherVersion.definition.key, otherVersion.revision) }
        assertEquals(1, SharedMcpRegistry(file).forHost("local").size)
    }
    @TempDir lateinit var root: File
    private fun definition(host: String = "local") = SharedMcpDefinition(host, "fixture", "official-source", "1", "fixture",
        listOf("C:\\Program Files\\MCP\\server.exe", "argument with spaces", "literal;$(not-a-shell)"))

    @Test fun `one definition binds multiple runners while machine identities remain separate`() {
        val file = File(root, "shared.json")
        val store = SharedMcpRegistry(file)
        val local = store.save(definition(), setOf("claude", "codex", "opencode"), null)
        store.save(definition("server-a"), setOf("codex"), null)
        store.save(definition("server-b"), emptySet(), null)
        val restored = SharedMcpRegistry(file)
        assertEquals(3, restored.records.size)
        assertEquals(setOf("claude", "codex", "opencode"), restored.forHost("local").single().desiredRunners)
        assertEquals(setOf("codex"), restored.forHost("server-a").single().desiredRunners)
        assertFailsWith<IllegalStateException> { restored.save(definition(), emptySet(), null) }
        assertFailsWith<IllegalStateException> { restored.save(definition().copy(pluginId = "other"), emptySet(), null) }
        restored.save(definition(), setOf("opencode"), local.revision)
        assertEquals(setOf("codex"), restored.forHost("server-a").single().desiredRunners)
    }

    @Test fun `native fragments preserve argv without shell joining and keep other formats distinct`() {
        val source = definition()
        val claude = SharedMcpSettings.forRunner(source, "claude").getJSONObject("mcpServers").getJSONObject("fixture")
        val codex = SharedMcpSettings.forRunner(source, "codex").getJSONObject("mcp_servers").getJSONObject("fixture")
        val openCode = SharedMcpSettings.forRunner(source, "opencode").getJSONObject("mcp").getJSONObject("fixture")
        assertEquals(source.command.first(), claude.getString("command"))
        assertEquals(source.command[2], codex.getJSONArray("args").getString(1))
        assertEquals(source.command[1], openCode.getJSONArray("command").getString(1))
        val http = source.copy(command = emptyList(), url = "https://mcp.example.com/service")
        assertEquals("http", SharedMcpSettings.forRunner(http, "claude").getJSONObject("mcpServers").getJSONObject("fixture").getString("type"))
        assertEquals("remote", SharedMcpSettings.forRunner(http, "opencode").getJSONObject("mcp").getJSONObject("fixture").getString("type"))
        assertEquals(http.url, SharedMcpSettings.forRunner(http, "codex").getJSONObject("mcp_servers").getJSONObject("fixture").getString("url"))
        assertFailsWith<IllegalArgumentException> { http.copy(url = "https://user:secret@mcp.example.com/service").validate() }
        assertFailsWith<IllegalArgumentException> { http.copy(url = "http://external.example.com/service").validate() }
        http.copy(url = "http://127.0.0.1:1234/mcp").validate()
    }

    @Test fun `corrupt shared catalog is retained and blocks replacing native intentions`() {
        val file = File(root, "shared.json").apply { writeText("broken") }
        val store = SharedMcpRegistry(file)
        assertTrue(store.problem.isNotBlank())
        assertFailsWith<IllegalStateException> { store.save(definition(), setOf("codex"), null) }
        assertEquals("broken", file.readText())
    }
}
