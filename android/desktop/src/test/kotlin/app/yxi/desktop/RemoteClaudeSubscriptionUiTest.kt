package app.yxi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class RemoteClaudeSubscriptionUiTest {
    @Test fun `server authentication dialog checks the chosen SSH host without closing it`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val root = File("/sandbox/tmp/remote-subscription-ui").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val bin = File(home, ".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "claude").toPath(), File("/opt/native/claude").toPath())
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path, "CLAUDE_CONFIG_DIR" to File(home, ".claude").path,
            "CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-oauth-token"), File(root, "unused.sock")).use { bridge ->
            runBlocking { bridge.conn.ssh.connect() }; bridge.conn.status = Conn.Status.Connected
            var bounds: Rect? = null; var calls = 0; var completed = false; var failure: Throwable? = null
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 760.dp, height = 650.dp)) {
                    YxiTheme { RemoteClaudeSubscriptionCheck(bridge.conn, Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) { directory ->
                        calls++; assertEquals("/", directory)
                        ClaudeSubscriptionProbe.verifyRemote(bridge.conn.ssh, directory); completed = true
                    } }
                    LaunchedEffect(Unit) {
                        suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) {
                            val origin = window.contentPane.locationOnScreen
                            Robot().apply { mouseMove(origin.x + x, origin.y + y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }
                        try {
                            withTimeout(5000) { while (bounds == null) { if (window.isShowing) click(65, 20); delay(200) } }
                            delay(300)
                            val box = checkNotNull(bounds)
                            click(box.right.toInt() - 65, box.bottom.toInt() - 45)
                            withTimeout(10000) { while (!completed) delay(20) }
                            delay(400)
                            assertEquals(1, calls)
                            assertEquals("alive", bridge.conn.ssh.exec("printf alive").trim())
                            assertFalse(File(home, ".claude/.credentials.json").exists())
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/remote-claude-subscription.png"))
                        } catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        }
    }
}
