package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalHistoryNativeTest {
    @Test fun `native history browser reads existing turns without another model request or transcript writes`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val home = File(System.getProperty("user.home"))
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "local-history-").toFile()
        val bin = File(home, ".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "codex").toPath(), Path.of("/opt/native/claude"))
        val stub = root.resolve("server.py").apply { writeText(javaClass.getResource("/codex_responses_stub.py")!!.readText()) }
        val server = ProcessBuilder("python3", stub.path, root.path, "fixture-key-0").redirectErrorStream(true).redirectOutput(root.resolve("server.log")).start()
        var cli: Process? = null
        val state = AppState()
        var failure: Throwable? = null
        try {
            repeat(100) { if (!root.resolve("port").exists()) Thread.sleep(50) }
            val port = root.resolve("port").readText().trim()
            val codexHome = File(home, ".codex").apply { mkdirs() }
            val config = File(codexHome, "config.toml").apply { writeText("""
model = "fixture-model-0"
model_provider = "fixture"
sandbox_mode = "read-only"
[model_providers.fixture]
name = "Fixture"
base_url = "http://127.0.0.1:$port/v1"
wire_api = "responses"
requires_openai_auth = false
http_headers = { Authorization = "Bearer fixture-key-0" }
""".trimIndent()) }
            val cliOutput = root.resolve("cli.jsonl")
            cli = ProcessBuilder("/opt/native/claude", "exec", "--skip-git-repo-check", "--json", "-").directory(root)
                .redirectError(root.resolve("cli.err")).redirectOutput(cliOutput).start()
            cli.outputStream.use { it.write("YXI-LOCAL-HISTORY-FIXTURE".toByteArray()) }
            check(cli.waitFor(45, TimeUnit.SECONDS)) { "Fixture native CLI timed out" }
            assertEquals(0, cli.exitValue())
            val nativeId = cliOutput.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .first { it.optString("type") == "thread.started" }.getString("thread_id")
            val requestCount = root.resolve("requests.jsonl").readLines().size
            val beforeConfig = config.readBytes()
            fun transcripts() = File(codexHome, "sessions").walkTopDown().filter { it.isFile && it.extension == "jsonl" }
                .associate { it.relativeTo(codexHome).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
            val beforeTranscripts = transcripts(); assertTrue(beforeTranscripts.isNotEmpty())
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1360.dp, height = 960.dp), title = "Yxi local history") {
                    YxiTheme { Row { Box(Modifier.width(250.dp)) { LocalWorkspaceSidebar(state, "") }; Box(Modifier.weight(1f)) { LocalWorkspacePane(state) } } }
                    LaunchedEffect(Unit) {
                        try {
                            state.selectLocal()
                            withTimeout(30000) { while (state.localWorkspace.threads.none { it.id == nativeId } && state.localWorkspace.error.isEmpty()) delay(100) }
                            assertEquals("", state.localWorkspace.error)
                            assertTrue(state.localWorkspace.installations.any { it.engine == "codex" && it.ready })
                            val thread = state.localWorkspace.threads.single { it.id == nativeId }
                            assertEquals("fixture", thread.provider)
                            delay(500)
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/local-workspace.png"))
                            state.localWorkspace.openThread(thread)
                            withTimeout(15000) { while (state.localWorkspace.turns.isEmpty() && state.localWorkspace.readError.isEmpty()) delay(100) }
                            assertEquals("", state.localWorkspace.readError)
                            assertTrue(state.localWorkspace.turns.toString().contains("YXI-LOCAL-HISTORY-FIXTURE"))
                            assertTrue(state.localWorkspace.turns.toString().contains("fixture-complete"))
                            delay(500)
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/local-native-history.png"))
                            assertEquals(requestCount, root.resolve("requests.jsonl").readLines().size, "Reading native history must not call the model")
                            assertContentEquals(beforeConfig, config.readBytes())
                            assertEquals(beforeTranscripts, transcripts(), "Reading must not append or rewrite native turns")
                        } catch (e: Throwable) { failure = e }
                        finally { state.closeLocalFeatures(); exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        } finally { state.closeLocalFeatures(); cli?.let(LocalRuntimeDiscovery::stopOwnedProcess); LocalRuntimeDiscovery.stopOwnedProcess(server) }
    }
}
