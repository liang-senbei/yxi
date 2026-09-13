package app.yxi.desktop

import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.*

class PluginOperationsTest {
    @Test fun `installed results persist and survive unavailable status queries`() {
        val file = Files.createTempDirectory("plugin-install-ledger").resolve("operations.json").toFile()
        val ledger = PluginOperations(file)
        ledger.begin("host", JSONObject().put("operation", "d".repeat(32)).put("action", "install"))
        ledger.finish("d".repeat(32), "installed")
        ledger.finish("d".repeat(32), "unknown")
        assertEquals("installed", PluginOperations(file).latest("host")!!.status)
        assertFalse(ledger.unresolved("host"))
        ledger.begin("host", JSONObject().put("operation", "e".repeat(32)).put("action", "uninstall"))
        ledger.finish("e".repeat(32), "uninstalled")
        ledger.finish("e".repeat(32), "unknown")
        assertEquals("uninstalled", PluginOperations(file).latest("host")!!.status)
        ledger.begin("host", JSONObject().put("operation", "f".repeat(32)).put("action", "update"))
        ledger.finish("f".repeat(32), "updated", "1.0.0", "1.1.0")
        ledger.finish("f".repeat(32), "unknown")
        val upgrade = PluginOperations(file).latest("host")!!
        assertEquals("updated", upgrade.status)
        assertEquals("1.0.0", upgrade.beforeVersion)
        assertEquals("1.1.0", upgrade.afterVersion)
    }
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
        reopened.finish("a".repeat(32), "unknown")
        assertEquals("configured", reopened.latest("host-a")!!.status)
        assertFalse(PluginOperations(file).unresolved("host-a"))
        file.writeText("broken")
        val recovered = PluginOperations(file)
        assertTrue(recovered.error.isNotBlank())
        assertFails { recovered.begin("host-b", JSONObject().put("operation", "c".repeat(32))) }
        assertTrue(PluginOperations(file).error.isNotBlank())
    }
}
