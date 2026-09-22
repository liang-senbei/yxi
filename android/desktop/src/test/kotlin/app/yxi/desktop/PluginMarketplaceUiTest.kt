package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.*
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class PluginMarketplaceUiTest {
    @Test fun `marketplace renders local and real isolated server catalog without installing anything`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(File("/opt/native/claude").canExecute())
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "plugin-market-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val market = home.resolve("market").apply { mkdir() }
        market.resolve(".claude-plugin").mkdir()
        market.resolve("plugins/sample/.claude-plugin").mkdirs()
        market.resolve("plugins/sample/.claude-plugin/plugin.json").writeText("""{"name":"sample","version":"1.0.0"}""")
        market.resolve(".claude-plugin/marketplace.json").writeText("""{"name":"yxi-market-fixture","owner":{"name":"Yxi fixture"},"plugins":[{"name":"sample","source":"./plugins/sample","description":"开发辅助插件 · 隔离界面样例","version":"1.0.0"}]}""")
        var failure: Throwable? = null
        try {
            IsolatedSshBridge(root.resolve("ssh"), mapOf("HOME" to home.path, "CLAUDE_CONFIG_DIR" to home.resolve(".claude").path),
                root.resolve("unused.sock")).use { bridge ->
                Files.createSymbolicLink(root.resolve("ssh/bin/claude").toPath(), Path.of("/opt/native/claude"))
                runBlocking {
                    bridge.conn.ssh.connect()
                    bridge.conn.ssh.exec("claude plugin marketplace add ${Shell.q(market.path)}")
                    val entries = PluginCatalog.parse(bridge.conn.ssh.exec(PluginCatalog.command()))
                    assertEquals("sample@yxi-market-fixture", entries.single().id)
                }
                bridge.conn.status = Conn.Status.Connected
                val state = AppState().apply { conn = bridge.conn; pluginMarketplace = true }
                application(exitProcessOnExit = false) {
                    Window(onCloseRequest = ::exitApplication, title = "Yxi marketplace preview",
                        state = rememberWindowState(width = 1040.dp, height = 860.dp)) {
                        YxiTheme { PluginsPane(state) }
                        LaunchedEffect(Unit) {
                            suspend fun shot(name: String) = withContext(Dispatchers.IO) {
                                check(ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)),
                                    "png", File("/results/$name.png")))
                            }
                            try {
                                delay(3000); shot("marketplace-all")
                                state.pluginMarketScope = 1; delay(500); shot("marketplace-local")
                                state.pluginMarketScope = 2; state.pluginMarketQuery = "yxi-market-fixture"
                                delay(1800); shot("marketplace-server-search")
                                assertTrue(state.pluginOperations.entries.isEmpty(), "Browsing must not install plugins")
                                assertFalse(state.showAndroidEmulator, "Browsing must not launch a local runtime")
                                val inventory = PluginInventory.parse(bridge.conn.ssh.exec(PluginInventory.command()))
                                assertTrue(inventory.plugins.isEmpty())
                            } catch (e: Throwable) { failure = e }
                            finally { exitApplication() }
                        }
                    }
                }
            }
        } finally { root.deleteRecursively() }
        failure?.let { throw it }
    }
}
