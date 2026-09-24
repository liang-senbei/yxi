package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ClaudeEffortUiTest {
    @Test fun `drag changes the preview and apply submits exactly one effort`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        System.setProperty("skiko.renderApi", "SOFTWARE")
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 400.dp, height = 240.dp)) {
                var current by remember { mutableStateOf("low") }
                var bounds by remember { mutableStateOf<Rect?>(null) }
                val submitted = remember { mutableListOf<String>() }
                YxiTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(24.dp)) {
                    ClaudeEffortSettings("fixture-model", listOf("low", "medium", "high", "xhigh", "max"), current, true, false,
                        Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) { submitted.add(it); current = it }
                } } }
                LaunchedEffect(Unit) {
                    fun screenshot(name: String) { ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/$name.png")) }
                    try {
                        withTimeout(4000) { while (bounds == null) delay(20) }; delay(300)
                        screenshot("claude-effort-low")
                        val box = checkNotNull(bounds); val origin = window.contentPane.locationOnScreen
                        withContext(Dispatchers.IO) { Robot().apply {
                            val y = origin.y + box.top.toInt() + 52
                            mouseMove(origin.x + box.left.toInt() + 30, y); mousePress(InputEvent.BUTTON1_DOWN_MASK)
                            for (x in 30..300 step 15) { mouseMove(origin.x + box.left.toInt() + x, y); delay(25) }
                            mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        } }
                        delay(300); screenshot("claude-effort-max-preview")
                        assertTrue(submitted.isEmpty()); assertEquals("low", current)
                        withContext(Dispatchers.IO) { Robot().apply {
                            mouseMove(origin.x + box.left.toInt() + 75, origin.y + box.bottom.toInt() - 24)
                            mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        } }
                        withTimeout(2000) { while (submitted.isEmpty()) delay(20) }
                        assertEquals(listOf("max"), submitted); assertEquals("max", current)
                        delay(300); screenshot("claude-effort-max-applied")
                    } catch (e: Throwable) { screenshot("claude-effort-failure"); failure = e }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
