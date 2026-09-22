package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalAgentNativeTest {
    @Test fun `local native agent runs outside git using stdin and renders Explore pages`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val home = File(System.getProperty("user.home"))
        check(home.canonicalPath == "/sandbox/home")
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "local-agent-").toFile()
        val bin = File(home, ".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "codex").toPath(), Path.of("/opt/native/claude"))
        val script = root.resolve("server.py").apply { writeText(LocalAgentNativeTest::class.java.getResource("/codex_responses_stub.py")!!.readText()) }
        val server = ProcessBuilder("python3", script.path, root.path, "fixture-key-0").redirectErrorStream(true).redirectOutput(root.resolve("server.log")).start()
        val state = AppState()
        var failure: Throwable? = null
        try {
            repeat(100) { if (!root.resolve("port").exists()) Thread.sleep(50) }
            val port = root.resolve("port").readText().trim()
            val config = File(home, ".codex").apply { mkdirs() }.resolve("config.toml")
            config.writeText("""
model = "fixture-model-0"
model_provider = "fixture"
sandbox_mode = "read-only"
[model_providers.fixture]
name = "Fixture"
base_url = "http://127.0.0.1:$port/v1"
wire_api = "responses"
requires_openai_auth = false
http_headers = { Authorization = "Bearer fixture-key-0" }
""".trimIndent())
            val before = config.readBytes()
            runBlocking {
                withContext(Dispatchers.Swing) { state.localAgents.start("codex", root.path, "YXI-LOCAL-FIXTURE literal \$(touch not-a-command) and 中文") }
                withTimeout(45000) { while (state.localAgents.jobs.single().running) delay(100) }
                val job = state.localAgents.jobs.single()
                assertEquals("已完成", job.status, job.output)
                assertTrue(job.output.contains("fixture-complete"), job.output)
                assertFalse(root.resolve("not-a-command").exists())
                assertTrue(root.resolve("requests.jsonl").readText().contains("YXI-LOCAL-FIXTURE"))
                assertContentEquals(before, config.readBytes(), "Launching local agents must not rewrite their global configuration")
            }
            state.conn = Conn(Host("fixture-server", "hk13 · 测试", "192.0.2.1"), NoHostKeys)
            var page by androidx.compose.runtime.mutableStateOf(0)
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Yxi Explore preview", state = rememberWindowState(width = 1100.dp, height = 880.dp)) {
                    YxiTheme { if (page == 0) ConnectionsPane(state) else LocalAgentsPane(state) }
                    LaunchedEffect(Unit) {
                        suspend fun shot(name: String) = withContext(Dispatchers.IO) {
                            check(ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/$name.png")))
                        }
                        try { delay(1200); shot("explore-connections"); page = 1; delay(700); shot("explore-local-agent") }
                        catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
        } finally {
            state.closeLocalFeatures(); server.destroyForcibly(); server.waitFor(); root.deleteRecursively()
        }
        failure?.let { throw it }
    }
}
