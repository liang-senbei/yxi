package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class SharedMcpUiTest {
    @Test fun `shared config selection persists only for the displayed machine`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val state = AppState()
        state.pluginLocation = "本地"
        val local = SharedMcpDefinition("@local", "local-fixture", "test", "1", "local_echo", url = "https://mcp.example.com/service")
        val remote = local.copy(hostKey = "remote-fixture", pluginId = "remote-fixture", name = "remote_echo")
        state.sharedMcp.save(local, emptySet(), null)
        state.sharedMcp.save(remote, emptySet(), null)
        var failure: Throwable? = null
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1180.dp, height = 760.dp)) {
                    YxiTheme { Surface { SharedMcpPane(state, null) } }
                    LaunchedEffect(Unit) {
                        try {
                            delay(800)
                            // The only enabled selection in this card is the OpenCode chip.
                            for (y in listOf(140, 156, 172, 188)) {
                                if ("opencode" in state.sharedMcp.forHost("@local").single().desiredRunners) break
                                withContext(Dispatchers.IO) { Robot().apply {
                                    mouseMove(window.locationOnScreen.x + 90, window.locationOnScreen.y + y)
                                    mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                } }
                                delay(150)
                            }
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/shared-mcp-config.png"))
                            assertEquals(setOf("opencode"), state.sharedMcp.forHost("@local").single().desiredRunners)
                            assertTrue(state.sharedMcp.forHost("remote-fixture").single().desiredRunners.isEmpty())
                            val restored = SharedMcpRegistry(File(Store.dir, "shared-mcp.json"))
                            assertEquals(setOf("opencode"), restored.forHost("@local").single().desiredRunners)
                        } catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
        } finally { state.closeLocalFeatures(); state.remoteOpenCodeTasks.close() }
        failure?.let { throw it }
    }
}
