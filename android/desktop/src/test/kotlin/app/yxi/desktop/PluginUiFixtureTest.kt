package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Robot
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class PluginUiFixtureTest {
    @Test fun `plugin cards render and refresh at desktop and compact widths`() {
        val directory = System.getenv("YXI_PLUGIN_UI_OUT")
        assumeTrue(directory != null)
        val output = File(directory!!).apply { mkdirs() }
        System.setProperty("user.home", output.resolve("profile").apply { mkdirs() }.path)
        val refreshes = AtomicInteger()
        val data = PluginInventory(listOf(
            InstalledPlugin("design-review@team-marketplace", "1.2.0", "user", "", "/home/test/.claude/plugins/cache/team-marketplace/design-review/1.2.0", true, true, "listed", true),
            InstalledPlugin("long-project-interface-quality-check@regional-server-marketplace", "0.8.1", "project", "/workspace/customer-portal/windows-interface", "/workspace/plugins/long-project-interface-quality-check", false, false, "reported-error", false)
        ), emptyList())
        for (width in listOf(820, 460)) {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Yxi plugin fixture", state = rememberWindowState(width = width.dp, height = 820.dp)) {
                    YxiTheme { PluginInventoryContent("香港 · hk13 测试主机", data, "", false, refresh = { refreshes.incrementAndGet() }) }
                    LaunchedEffect(Unit) {
                        delay(2500)
                        val origin = window.contentPane.locationOnScreen
                        val area = Rectangle(window.bounds)
                        val buttonX = origin.x + window.contentPane.width - 75
                        withContext(Dispatchers.IO) {
                            val robot = Robot()
                            ImageIO.write(robot.createScreenCapture(area), "png", output.resolve("plugins-$width.png"))
                            robot.mouseMove(buttonX, origin.y + 45)
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        }
                        delay(500)
                        withContext(Dispatchers.IO) {
                            val robot = Robot()
                            robot.mouseMove(origin.x + 100, origin.y + 170)
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                            repeat(4) { robot.keyPress(KeyEvent.VK_Z); robot.keyRelease(KeyEvent.VK_Z) }
                        }
                        delay(500)
                        withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(area), "png", output.resolve("plugins-search-$width.png")) }
                        exitApplication()
                    }
                }
            }
        }
        assertEquals(2, refreshes.get(), "Refresh button must be clickable in both layouts")
    }
}
