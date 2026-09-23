package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RemoteAcpCreationUiTest {
    @Test fun `server creation routes ACP choice to its host preview without executing the draft`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val root = File("/sandbox/tmp/acp-create-ui").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val directory = File(home, "project").apply { mkdirs() }
        val marker = File(root, "must-not-start")
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/gemini").apply { writeText("#!/bin/sh\ntouch '${marker.path}'\n"); setExecutable(true) }
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path), File(root, "unused.sock")).use { bridge ->
            runBlocking { bridge.conn.ssh.connect() }
            val state = AppState()
            var failure: Throwable? = null
            var routed = false
            try {
                application(exitProcessOnExit = false) {
                    Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1180.dp, height = 960.dp)) {
                        var creating by remember { mutableStateOf(true) }
                        YxiTheme { Surface {
                            if (creating) NewSessionDialog(bridge.conn, { creating = false }, initialDirectory = directory.path,
                                initialAgent = "gemini", groupContext = "Do not send this draft automatically", onAcpConversation = { engine, path, prompt ->
                                    state.prepareAcpTask(bridge.conn, engine, path, prompt); routed = true; creating = false
                                }) { failure = AssertionError("ACP must not use the tmux creation path") }
                            else RemoteAcpPane(state)
                        } }
                        LaunchedEffect(Unit) {
                            try {
                                delay(1400)
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize)), "png", File("/results/server-acp-create.png"))
                                val dialog = java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().firstOrNull { it.isShowing }
                                val locations = if (dialog != null) listOf((dialog.locationOnScreen.x + dialog.width - 100) to (dialog.locationOnScreen.y + dialog.height - 44))
                                    else (200..440 step 16).map { (window.locationOnScreen.x + window.width / 2 + 170) to (window.locationOnScreen.y + window.height / 2 + it) }
                                for ((x, y) in locations) {
                                    if (routed) break
                                    withContext(Dispatchers.IO) { Robot().apply {
                                        mouseMove(x, y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                    } }
                                    delay(120)
                                }
                                withTimeout(3000) { while (!routed) delay(20) }
                                delay(400)
                                assertEquals(Page.Acp, state.page)
                                assertSame(bridge.conn, state.conn)
                                assertEquals("gemini", state.remoteAcpEngine)
                                assertEquals(directory.path, state.remoteAcpDirectory)
                                assertEquals("Do not send this draft automatically", state.remoteAcpPrompt)
                                assertTrue(state.remoteAcpTasks.registry.records.isEmpty())
                                assertFalse(marker.exists())
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/server-acp-preview.png"))
                            } catch (e: Throwable) { failure = e }
                            finally { exitApplication() }
                        }
                    }
                }
            } finally { state.remoteAcpTasks.close(); state.closeLocalFeatures() }
            failure?.let { throw it }
        }
    }
}
