package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
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
            var failure: Throwable? = null
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Yxi rewind dialog fixture",
                    state = rememberWindowState(width = width.dp, height = 720.dp)) {
                    YxiTheme {
                        if (open) RewindMessageDialog(text, { text = it }, enabled, true, enabled,
                            onDismiss = { open = false }, onDraft = { actions++ }, onRewind = { actions++ }, onNative = { actions++ })
                    }
                    LaunchedEffect(Unit) {
                        try {
                            delay(1800)
                            withContext(Dispatchers.IO) {
                                val robot = Robot()
                                val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                                ImageIO.write(robot.createScreenCapture(Rectangle(bounds)), "png", File("/results/rewind-dialog-$width.png"))
                                robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
                            }
                            delay(500)
                            assertFalse(open, "Escape must cancel the dialog")
                            assertEquals(0, actions, "Cancel must not send, load a draft or open the terminal picker")
                        } catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
            failure?.let { throw it }
        }
    }
}
