package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ClaudeSubscriptionCheckUiTest {
    @Test fun `actual subscription dialog opens runs and cancels its checker`() {
        val windowsFixture = System.getenv("YXI_SUBSCRIPTION_UI_FIXTURE")?.let { File(it).canonicalFile }
        val home = if (windowsFixture != null) {
            check(System.getProperty("os.name").startsWith("Windows"))
            windowsFixture.resolve("profile").also { profile ->
                check(File(System.getProperty("user.home")).canonicalFile == profile)
                check(File(System.getenv("APPDATA")).canonicalFile == profile.resolve("Roaming"))
                check(File(System.getenv("LOCALAPPDATA")).canonicalFile == profile.resolve("Local"))
                profile.mkdirs(); profile.resolve("Roaming").mkdirs(); profile.resolve("Local").mkdirs()
            }
        } else {
            check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
            check(System.getProperty("user.home") == "/sandbox/home")
            File("/sandbox/home")
        }
        val results = windowsFixture?.resolve("results")?.apply { mkdirs() } ?: File("/results")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        for (mode in listOf("success", "error", "cancel")) {
            var bounds: Rect? = null
            var calls = 0
            var cancelled = false
            var failure: Throwable? = null
            val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("/fixture/claude"), home.resolve(".claude").path, "fixture")
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 760.dp, height = 650.dp)) {
                    YxiTheme { ClaudeSubscriptionCheck(runtime, Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) { selected, directory ->
                        assertSame(runtime, selected); assertEquals(home, directory.canonicalFile); calls++
                        delay(250)
                        when (mode) {
                            "error" -> error("测试：仍检测到 API 凭据来源")
                            "cancel" -> try { awaitCancellation() } finally { cancelled = true }
                        }
                    } }
                    LaunchedEffect(Unit) {
                        suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) {
                            val origin = window.contentPane.locationOnScreen
                            Robot().apply { mouseMove(origin.x + x, origin.y + y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }
                        try {
                            window.toFront(); window.requestFocus()
                            delay(300)
                            withTimeout(5000) {
                                while (bounds == null) {
                                    if (window.isShowing) click(65, 20)
                                    delay(200)
                                }
                            }
                            delay(300)
                            val box = checkNotNull(bounds)
                            click(box.right.toInt() - 65, box.bottom.toInt() - 45)
                            click(box.right.toInt() - 65, box.bottom.toInt() - 45)
                            withTimeout(3000) { while (calls == 0) delay(20) }
                            delay(500)
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File(results, "claude-subscription-$mode.png"))
                            withContext(Dispatchers.IO) { Robot().apply { keyPress(KeyEvent.VK_ESCAPE); keyRelease(KeyEvent.VK_ESCAPE) } }
                            if (mode == "cancel") withTimeout(3000) { while (!cancelled) delay(20) }
                            assertEquals(1, calls)
                        } catch (e: Throwable) {
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File(results, "claude-subscription-$mode-failure.png"))
                            failure = e
                        }
                        finally { exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        }
    }
}
