package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import app.yxi.ssh.Shell
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Robot
import java.awt.Rectangle
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class PluginInstallFixtureTest {
    @Test fun `install from catalog into isolated host`() {
        val path = System.getenv("YXI_PLUGIN_INSTALL_FIXTURE")
        assumeTrue(path != null)
        val fixture = File(path!!)
        System.setProperty("user.home", fixture.resolve("client-home").apply { mkdirs() }.path)
        val home = fixture.resolve("home")
        home.resolve(".local/bin/claude").writeText("#!/bin/sh\nexport CLAUDE_CONFIG_DIR=\"\$HOME/.claude\"\nexec /usr/local/bin/claude \"\$@\"\n")
        val market = home.resolve("market")
        market.resolve(".claude-plugin").mkdirs()
        market.resolve("plugins/sample/.claude-plugin").mkdirs()
        market.resolve("plugins/sample/.claude-plugin/plugin.json").writeText("""{"name":"sample","version":"1.0.0"}""")
        market.resolve(".claude-plugin/marketplace.json").writeText("""{"name":"yxi-test","owner":{"name":"Yxi test"},"plugins":[{"name":"sample","source":"./plugins/sample","description":"A local UI installation fixture","version":"1.0.0"}]}""")
        val port = fixture.resolve("port").readText().trim().toInt()
        val pub = fixture.resolve("host.pub").readText().trim().split(' ').take(2).joinToString(" ")
        Store.knownHostsFile.writeText("[127.0.0.1]:$port $pub\n")
        val conn = Conn(Host("install-fixture", "Installation test", "127.0.0.1", port, "root", fixture.resolve("client").path), FileHostKeys())
        val state = AppState().apply { this.conn = conn }
        var failure: Throwable? = null
        var showInstalled by mutableStateOf(false)
        try {
            runBlocking {
                conn.ssh.connect()
                conn.ssh.exec("claude plugin marketplace add ${Shell.q(market.path)}")
                assertEquals(1, PluginCatalog.parse(conn.ssh.exec(PluginCatalog.command())).size)
            }
            conn.status = Conn.Status.Connected
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Yxi plugin installation fixture", state = rememberWindowState(width = 820.dp, height = 820.dp)) {
                    YxiTheme { if (showInstalled) PluginInventoryPane(state, conn) else PluginCatalogPane(state, conn) }
                    LaunchedEffect(Unit) {
                        suspend fun shot(name: String) = withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(Rectangle(0, 0, 1400, 1000)), "png", fixture.resolve(name)) }
                        try {
                            delay(4000); shot("01-catalog.png"); fixture.resolve("ready").writeText("ready")
                            withTimeout(180000) { while (!fixture.resolve("cancel-check").exists()) delay(100) }
                            assertTrue(state.pluginOperations.entries.isEmpty())
                            assertEquals(1, PluginCatalog.parse(conn.ssh.exec(PluginCatalog.command())).size)
                            fixture.resolve("cancel-verified").writeText("verified")
                            withTimeout(180000) { while (state.pluginOperations.entries.isEmpty() || state.pluginOperations.running.isNotEmpty()) delay(100) }
                            assertEquals("installed", state.pluginOperations.entries.single().status)
                            val inventory = PluginInventory.parse(conn.ssh.exec(PluginInventory.command()))
                            assertEquals("sample@yxi-test", inventory.plugins.single().id)
                            assertEquals("listed", inventory.plugins.single().runnerState)
                            assertEquals("installed", PluginOperations(File(Store.dir, "plugin-operations.json")).entries.single().status)
                            delay(1500); shot("04-installed.png")
                            market.resolve("plugins/sample/.claude-plugin/plugin.json").writeText("""{"name":"sample","version":"1.1.0"}""")
                            val definition = market.resolve(".claude-plugin/marketplace.json")
                            val changed = JSONObject(definition.readText())
                            changed.getJSONArray("plugins").getJSONObject(0).put("version", "1.1.0")
                            definition.writeText(changed.toString())
                            conn.ssh.exec("claude plugin marketplace update yxi-test")
                            showInstalled = true
                            delay(3000); shot("05-update-ready.png"); fixture.resolve("update-ready").writeText("ready")
                            withTimeout(90000) { while (state.pluginOperations.entries.size < 2 || state.pluginOperations.running.isNotEmpty()) delay(100) }
                            val result = state.pluginOperations.entries.last()
                            assertEquals("updated", result.status)
                            assertEquals("1.0.0", result.beforeVersion)
                            assertEquals("1.1.0", result.afterVersion)
                            val fresh = PluginInventory.parse(conn.ssh.exec(PluginInventory.command())).plugins.single()
                            assertEquals("1.1.0", fresh.version)
                            assertEquals("1.1.0", JSONObject(File(fresh.path, ".claude-plugin/plugin.json").readText()).getString("version"))
                            assertEquals("1.1.0", PluginOperations(File(Store.dir, "plugin-operations.json")).entries.last().afterVersion)
                            delay(2000); shot("07-updated.png")
                            fixture.resolve("rollback-ready").writeText("ready")
                            withTimeout(90000) { while (state.pluginOperations.entries.size < 3 || state.pluginOperations.running.isNotEmpty()) delay(100) }
                            val rollback = state.pluginOperations.entries.last()
                            assertEquals("package-restored", rollback.status)
                            assertEquals("1.0.0", rollback.afterVersion)
                            val restored = PluginInventory.parse(conn.ssh.exec(PluginInventory.command())).plugins.single()
                            assertEquals("1.0.0", restored.version)
                            assertTrue(restored.path.contains("/plugin-operations/restored/"))
                            assertEquals("1.0.0", JSONObject(File(restored.path, ".claude-plugin/plugin.json").readText()).getString("version"))
                            assertEquals("package-restored", PluginOperations(File(Store.dir, "plugin-operations.json")).entries.last().status)
                            delay(1500); shot("09-package-restored.png")
                        } catch(e: Throwable) { failure = e; shot("failure.png") }
                        finally { exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        } finally { conn.ssh.disconnect() }
    }
}
