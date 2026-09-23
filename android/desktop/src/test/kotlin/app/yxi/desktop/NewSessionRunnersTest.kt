package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import org.junit.jupiter.api.condition.*
import org.junit.jupiter.api.io.TempDir
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class NewSessionRunnersTest {
    @TempDir lateinit var root: File
    @Test fun `runtime probe finds local bin with spaces and never executes the CLI`() {
        val home = File(root, "user home 中文").apply { mkdirs() }
        val marker = File(root, "executed")
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/codex").apply { writeText("#!/bin/sh\ntouch '${marker.path}'\n"); setExecutable(true) }
        val process = ProcessBuilder("/bin/sh", "-c", RunnerCatalog.probeCommand("codex")).apply {
            environment()["HOME"] = home.path; environment()["PATH"] = "/no-runtime"
        }.start()
        assertEquals("available", process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor()); assertFalse(marker.exists())
        val plan = DesktopLaunchPlan("/project", "codex", DesktopLaunchPlan.newRequestId())
        assertTrue(plan.command().contains(RunnerCatalog.resolveCommand("codex")), "Probe and actual launch must share the resolver")
    }
    @Test fun `new session dialog renders all catalog runners with explicit unsupported status`() {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1180.dp, height = 960.dp)) {
                YxiTheme { NewSessionDialog(Conn(Host("fixture", "hk13", "192.0.2.1"), NoHostKeys), ::exitApplication,
                    initialDirectory = "/root/src/workplace/BoomAsset/", initialAgent = "opencode") { failure = AssertionError("Unsupported runner must not launch") } }
                LaunchedEffect(Unit) {
                    try {
                        delay(1600)
                        ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/new-session-runners.png"))
                    } catch (e: Throwable) { failure = e }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
