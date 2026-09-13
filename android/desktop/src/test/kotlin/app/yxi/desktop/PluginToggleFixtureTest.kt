package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Robot
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class PluginToggleFixtureTest {
    @Test fun `cancel then confirm plugin disable through isolated SSH`() {
        val path = System.getenv("YXI_PLUGIN_TOGGLE_FIXTURE")
        assumeTrue(path != null)
        val fixture = File(path!!)
        System.setProperty("user.home", fixture.resolve("client-home").apply { mkdirs() }.path)
        val home = fixture.resolve("home")
        val config = home.resolve(".claude/settings.json")
        val original = """{"enabledPlugins":{"sample@fixture":true},"permissions":{"allow":["Read"]}}"""
        config.writeText(original)
        val plugin = home.resolve("fixture-plugin")
        plugin.resolve(".claude-plugin").mkdirs()
        plugin.resolve(".claude-plugin/plugin.json").writeText("""{"name":"sample","version":"1.0.0"}""")
        home.resolve(".claude/plugins").mkdirs()
        home.resolve(".claude/plugins/installed_plugins.json").writeText("""{"version":2,"plugins":{"sample@fixture":[{"scope":"user","version":"1.0.0","installPath":"${plugin.path}"}]}}""")
        home.resolve(".local/bin/claude").writeText("#!/bin/sh\nexport CLAUDE_CONFIG_DIR=\"\$HOME/.claude\"\nexec /usr/local/bin/claude \"\$@\"\n")
        val port = fixture.resolve("port").readText().trim().toInt()
        val pub = fixture.resolve("host.pub").readText().trim().split(' ').take(2).joinToString(" ")
        Store.knownHostsFile.writeText("[127.0.0.1]:$port $pub\n")
        val conn = Conn(Host("plugin-fixture", "Plugin test", "127.0.0.1", port, "root", fixture.resolve("client").path), FileHostKeys())
        val state = AppState().apply { this.conn = conn }
        var failure: Throwable? = null
        try {
            runBlocking { conn.ssh.connect() }; conn.status = Conn.Status.Connected
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Yxi plugin toggle fixture", state = rememberWindowState(width = 820.dp, height = 820.dp)) {
                    YxiTheme { PluginInventoryPane(state, conn) }
                    LaunchedEffect(Unit) {
                        suspend fun awaitCondition(test: () -> Boolean) { withTimeout(20000) { while (!test()) delay(100) } }
                        suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) { Robot().apply { mouseMove(x, y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) } }
                        suspend fun shot(name: String) = withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(Rectangle(0, 0, 1400, 1000)), "png", fixture.resolve(name)) }
                        try {
                            delay(5000)
                            val origin = window.contentPane.locationOnScreen
                            shot("01-before.png")
                            click(origin.x + 145, origin.y + 445)
                            awaitCondition { NativeOverlays.active }
                            delay(500)
                            shot("02-confirm.png")
                            withContext(Dispatchers.IO) { Robot().apply { keyPress(KeyEvent.VK_ESCAPE); keyRelease(KeyEvent.VK_ESCAPE) } }
                            awaitCondition { !NativeOverlays.active }
                            assertEquals(original, config.readText())
                            assertTrue(state.pluginOperations.entries.isEmpty())
                            delay(800) // Allow the native dialog to close and return focus before the next click.
                            click(origin.x + 145, origin.y + 445)
                            awaitCondition { NativeOverlays.active }
                            delay(500)
                            click(origin.x + 570, origin.y + 498)
                            awaitCondition { state.pluginOperations.entries.isNotEmpty() && state.pluginOperations.running.isEmpty() }
                            assertEquals("configured", state.pluginOperations.entries.single().status)
                            assertFalse(JSONObject(config.readText()).getJSONObject("enabledPlugins").getBoolean("sample@fixture"))
                            assertEquals("Read", JSONObject(config.readText()).getJSONObject("permissions").getJSONArray("allow").getString(0))
                            delay(2000)
                            shot("03-disabled.png")
                            click(origin.x + 650, origin.y + 32)
                            awaitCondition { NativeOverlays.active }
                            delay(600)
                            shot("04-restore-confirm.png")
                            click(origin.x + 570, origin.y + 512)
                            awaitCondition { !NativeOverlays.active }
                            assertEquals(1, state.pluginOperations.entries.size)
                            assertFalse(JSONObject(config.readText()).getJSONObject("enabledPlugins").getBoolean("sample@fixture"))
                            delay(800)
                            click(origin.x + 650, origin.y + 32)
                            awaitCondition { NativeOverlays.active }
                            delay(600)
                            click(origin.x + 625, origin.y + 512)
                            awaitCondition { state.pluginOperations.entries.size == 2 && state.pluginOperations.running.isEmpty() }
                            assertEquals("restored", state.pluginOperations.entries.last().status)
                            assertEquals(original, config.readText())
                            val reopened = PluginOperations(File(Store.dir, "plugin-operations.json"))
                            assertEquals("restored", reopened.entries.last().status)
                            assertEquals(2, reopened.entries.size)
                            delay(2000)
                            shot("05-restored.png")
                            click(origin.x + 205, origin.y + 500)
                            awaitCondition { NativeOverlays.active }; delay(600)
                            shot("06-uninstall-confirm.png")
                            withContext(Dispatchers.IO) { Robot().apply { keyPress(KeyEvent.VK_ESCAPE); keyRelease(KeyEvent.VK_ESCAPE) } }
                            awaitCondition { !NativeOverlays.active }
                            assertEquals(2, state.pluginOperations.entries.size)
                            assertEquals(original, config.readText())
                            delay(800)
                            click(origin.x + 205, origin.y + 500)
                            awaitCondition { NativeOverlays.active }; delay(600)
                            click(origin.x + 625, origin.y + 512)
                            awaitCondition { state.pluginOperations.entries.size == 3 && state.pluginOperations.running.isEmpty() }
                            assertEquals("uninstalled", state.pluginOperations.entries.last().status)
                            assertTrue(PluginInventory.parse(conn.ssh.exec(PluginInventory.command())).plugins.isEmpty())
                            assertEquals("Read", JSONObject(config.readText()).getJSONObject("permissions").getJSONArray("allow").getString(0))
                            assertTrue(home.resolve(".yxi/plugin-operations/${state.pluginOperations.entries.last().id}.plugin-before.tar").isFile)
                            assertEquals("uninstalled", PluginOperations(File(Store.dir, "plugin-operations.json")).entries.last().status)
                            delay(1500); shot("07-uninstalled.png")
                        } catch (e: Throwable) { failure = e; shot("failure.png") }
                        finally { exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        } finally { conn.ssh.disconnect() }
    }
}
