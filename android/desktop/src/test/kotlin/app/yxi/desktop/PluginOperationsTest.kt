package app.yxi.desktop

import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.*

class PluginOperationsTest {
    @Test fun `restart retains operation identity and blocks new sends until queried`() {
        val file = Files.createTempDirectory("plugin-ledger").resolve("operations.json").toFile()
        val original = PluginOperations(file)
        val request = JSONObject().put("operation", "a".repeat(32)).put("action", "set")
        original.begin("host-a", request)
        val reopened = PluginOperations(file)
        assertEquals("unknown", reopened.latest("host-a")!!.status)
        assertTrue(reopened.unresolved("host-a"))
        assertFalse(reopened.unresolved("host-b"))
        assertFails { reopened.begin("host-a", JSONObject().put("operation", "b".repeat(32))) }
        reopened.finish("a".repeat(32), "configured")
        assertFalse(PluginOperations(file).unresolved("host-a"))
        file.writeText("broken")
        val recovered = PluginOperations(file)
        assertTrue(recovered.error.isNotBlank())
        assertFails { recovered.begin("host-b", JSONObject().put("operation", "c".repeat(32))) }
        assertTrue(PluginOperations(file).error.isNotBlank())
    }
}
