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
class NativePluginIsolationTest {
    @Test fun `real Codex catalog install targets one isolated home and renders native metadata`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(File("/opt/native/claude").canExecute()) // Runner's generic read-only native binary mount is Codex here.
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "codex-plugins-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val market = home.resolve("market").apply { mkdir() }
        market.resolve(".agents/plugins").mkdirs()
        market.resolve("plugins/sample/.codex-plugin").mkdirs()
        market.resolve("plugins/sample/skills/sample").mkdirs()
        market.resolve("plugins/sample/skills/sample/SKILL.md").writeText("---\nname: sample\ndescription: Isolated fixture only\n---\nSay fixture.\n")
        market.resolve("plugins/sample/.codex-plugin/plugin.json").writeText("""{"name":"sample","version":"1.0.0","skills":"./skills/","interface":{"displayName":"隔离测试插件","shortDescription":"验证目标机器独立安装与目录展示","category":"Developer Tools"}}""")
        market.resolve(".agents/plugins/marketplace.json").writeText("""{"name":"yxi-native-fixture","owner":{"name":"Yxi fixture"},"plugins":[{"name":"sample","source":"./plugins/sample","description":"Isolated test","version":"1.0.0"}]}""")
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
                        val result = rpc.request("plugin/install", sample.installParams())
                        assertEquals(0, result.getJSONArray("appsNeedingAuth").length())
                        val after = NativePluginSnapshot.parse(rpc.request("plugin/list"))
                        assertTrue(after.entries.single { it.id == sample.id }.installed)
                    }
                    // A distinct untouched HOME must not acquire a registry/cache from the install.
                    assertFalse(root.resolve("other-home/.codex").exists())
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
