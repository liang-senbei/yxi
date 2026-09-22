package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.condition.*
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class NativePluginIsolationTest {
    @Test fun `real Codex catalog install targets one isolated home and renders native metadata`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(File("/opt/native/claude").canExecute()) // Runner's generic read-only native binary mount is Codex here.
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "codex-plugins-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        home.resolve(".codex").mkdir()
        val market = home.resolve("market").apply { mkdir() }
        market.resolve(".agents/plugins").mkdirs()
        market.resolve("plugins/sample/.codex-plugin").mkdirs()
        market.resolve("plugins/sample/skills/sample").mkdirs()
        market.resolve("plugins/sample/skills/sample/SKILL.md").writeText("---\nname: sample\ndescription: Isolated fixture only\n---\nSay fixture.\n")
        market.resolve("plugins/sample/.codex-plugin/plugin.json").writeText("""{"name":"sample","version":"1.0.0","skills":"./skills/","interface":{"displayName":"隔离测试插件","shortDescription":"验证目标机器独立安装与目录展示","category":"Developer Tools"}}""")
        market.resolve(".agents/plugins/marketplace.json").writeText("""{"name":"yxi-native-fixture","plugins":[{"name":"sample","source":{"source":"local","path":"./plugins/sample"},"policy":{"installation":"AVAILABLE","authentication":"ON_USE"},"category":"Developer Tools"}]}""")
        var failure: Throwable? = null
        try {
            IsolatedSshBridge(root.resolve("ssh"), mapOf("HOME" to home.path, "CODEX_HOME" to home.resolve(".codex").path), root.resolve("unused.sock")).use { bridge ->
                Files.createSymbolicLink(root.resolve("ssh/bin/codex").toPath(), Path.of("/opt/native/claude"))
                runBlocking {
                    bridge.conn.ssh.connect()
                    bridge.conn.status = Conn.Status.Connected
                    bridge.conn.ssh.exec("codex plugin marketplace add ${Shell.q(market.path)}")
                    PluginRpc.connect(bridge.conn).use { rpc ->
                        val before = NativePluginSnapshot.parse(rpc.request("plugin/list"))
                        val sample = before.entries.single { it.name == "sample" }
                        assertFalse(sample.installed)
                        assertTrue(sample.installable)
                        assertEquals("隔离测试插件", sample.title)
                        val store = NativePluginStore(bridge.conn, root.resolve("install-ledger.json"))
                        withContext(Dispatchers.Swing) { store.install(sample) }
                        withTimeout(30000) { while (store.busy) delay(100) }
                        assertEquals("", store.error)
                        assertNull(store.pendingId)
                        assertTrue(store.message.contains("已安装"))
                        val after = NativePluginSnapshot.parse(rpc.request("plugin/list"))
                        assertTrue(after.entries.single { it.id == sample.id }.installed)
                    }
                    val otherHome = root.resolve("other-home").apply { mkdir() }
                    otherHome.resolve(".codex").mkdir()
                    IsolatedSshBridge(root.resolve("ssh-other"), mapOf("HOME" to otherHome.path,
                        "CODEX_HOME" to otherHome.resolve(".codex").path), root.resolve("other.sock")).use { other ->
                        Files.createSymbolicLink(root.resolve("ssh-other/bin/codex").toPath(), Path.of("/opt/native/claude"))
                        other.conn.ssh.connect(); other.conn.status = Conn.Status.Connected
                        other.conn.ssh.exec("codex plugin marketplace add ${Shell.q(market.path)}")
                        PluginRpc.connect(other.conn).use { rpc ->
                            val otherEntries = NativePluginSnapshot.parse(rpc.request("plugin/list")).entries
                            assertFalse(otherEntries.single { it.name == "sample" }.installed, "Installing for A must not install for B")
                        }
                    }
                    assertFalse(File(System.getProperty("user.home"), ".codex/plugins/cache/yxi-native-fixture").exists())
                }
                val state = AppState().apply { conn = bridge.conn; pluginLocation = "服务器"; pluginCatalogRuntime = "codex" }
                val local = state.nativePlugins(null)
                val remote = state.nativePlugins(bridge.conn)
                assertNotSame(local, remote)
                state.conn = null
                assertSame(local, state.nativePlugins(null))
                state.conn = bridge.conn
                application(exitProcessOnExit = false) {
                    Window(onCloseRequest = ::exitApplication, title = "Yxi native plugin market",
                        state = rememberWindowState(width = 1080.dp, height = 880.dp)) {
                        YxiTheme { PluginsPane(state) }
                        LaunchedEffect(Unit) {
                            try {
                                withTimeout(30000) { while (remote.busy || remote.entries.isEmpty()) delay(100) }
                                assertTrue(remote.entries.single { it.name == "sample" }.installed)
                                delay(800)
                                withContext(Dispatchers.IO) {
                                    check(ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/native-plugin-market.png")))
                                }
                                state.pluginMarketplace = false
                                delay(800)
                                withContext(Dispatchers.IO) {
                                    check(ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/native-plugin-installed.png")))
                                }
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
