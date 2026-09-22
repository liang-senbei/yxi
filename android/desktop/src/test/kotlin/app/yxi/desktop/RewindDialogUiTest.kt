package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class RewindDialogUiTest {
    @Test fun `actual edit dialog renders and Escape cancels without sending`() {
        check(File("/.dockerenv").isFile)
        System.setProperty("user.home", "/sandbox/home")
        for ((width, enabled) in listOf(820 to true, 460 to false)) {
            var open by mutableStateOf(true)
            var text by mutableStateOf("请回到这条消息，保留前面的上下文。\n把登录页面改成清爽的布局，并检查保存后的状态。")
            var actions = 0
            var submitted: String? = null
            var dialogBounds: Rect? = null
            var failure: Throwable? = null
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Yxi rewind dialog fixture",
                    state = rememberWindowState(width = width.dp, height = 720.dp)) {
                    YxiTheme {
                        if (open) RewindMessageDialog(text, { text = it }, enabled, true, enabled,
                            onDismiss = { open = false }, onDraft = { actions++ },
                            onRewind = { submitted = text; open = false }, onNative = { actions++ },
                            modifier = Modifier.onGloballyPositioned { dialogBounds = it.boundsInWindow() })
                    }
                    LaunchedEffect(Unit) {
                        try {
                            delay(1800)
                            withContext(Dispatchers.IO) {
                                val robot = Robot()
                                val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                                ImageIO.write(robot.createScreenCapture(Rectangle(bounds)), "png", File("/results/rewind-dialog-$width.png"))
                            }
                            val dialog = requireNotNull(dialogBounds)
                            val origin = window.contentPane.locationOnScreen
                            withContext(Dispatchers.IO) {
                                val robot = Robot()
                                robot.mouseMove(origin.x + dialog.left.toInt() + 80, origin.y + dialog.top.toInt() + 125)
                                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_A)
                                robot.keyRelease(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_CONTROL)
                                robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A)
                            }
                            delay(400)
                            assertEquals("a", text, "Typing must update the actual editor")
                            withContext(Dispatchers.IO) {
                                val robot = Robot()
                                val current = requireNotNull(dialogBounds)
                                robot.mouseMove(origin.x + current.right.toInt() - 90, origin.y + current.bottom.toInt() - 48)
                                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                            }
                            delay(400)
                            if (enabled) {
                                assertEquals("a", submitted, "Primary action must receive edited text")
                                assertFalse(open)
                                open = true
                                delay(400)
                            } else {
                                assertNull(submitted, "Disabled primary action must not submit")
                                assertTrue(open)
                            }
                            withContext(Dispatchers.IO) {
                                Robot().apply { keyPress(KeyEvent.VK_ESCAPE); keyRelease(KeyEvent.VK_ESCAPE) }
                            }
                            delay(500)
                            assertFalse(open, "Escape must cancel the dialog")
                            assertEquals(0, actions, "Primary/cancel must not load a draft or open the terminal picker")
                        } catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        }
    }
}
