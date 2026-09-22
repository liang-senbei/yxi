package app.yxi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class RewindImageUiTest {
    @Test fun `history image preview and removal retain the original image number`() {
        check(File("/.dockerenv").isFile)
        System.setProperty("user.home", "/sandbox/home")
        val blocks = JSONArray()
        for (color in listOf(0x3975ed, 0x18a579)) {
            val bitmap = BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB)
            bitmap.createGraphics().apply { this.color = java.awt.Color(color); fillRect(0, 0, 240, 160); dispose() }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
            blocks.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
                .put("media_type", "image/png").put("data", Base64.getEncoder().encodeToString(bytes))))
        }
        blocks.put(JSONObject().put("type", "text").put("text", "请按这两张图片调整布局。"))
        var images by mutableStateOf(rewindImageDrafts("fixture", JSONObject().put("role", "user").put("content", blocks)))
        var open by mutableStateOf(true)
        var trayBounds: Rect? = null
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "Yxi rewind image fixture",
                state = rememberWindowState(width = 720.dp, height = 820.dp)) {
                YxiTheme {
                    if (open) RewindMessageDialog("请按这两张图片调整布局。", {}, false, true, false,
                        { open = false }, {}, {}, {}, attachments = {
                            Box(Modifier.onGloballyPositioned { trayBounds = it.boundsInWindow() }) {
                                DraftAttachmentTray(images.map { it.attachment }, { item -> images = images.filterNot { it.attachment === item } },
                                    labels = images.map { "图片${it.originalIndex + 1}" }, showTransferStatus = false)
                            }
                        })
                }
                LaunchedEffect(Unit) {
                    suspend fun screenshot(name: String) = withContext(Dispatchers.IO) {
                        val area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                        ImageIO.write(Robot().createScreenCapture(area), "png", File("/results/$name.png"))
                    }
                    suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) {
                        Robot().apply { autoDelay = 60; mouseMove(x, y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                    }
                    try {
                        delay(2000); screenshot("rewind-images")
                        val origin = window.contentPane.locationOnScreen
                        val tray = requireNotNull(trayBounds)
                        click(origin.x + tray.left.toInt() + 54, origin.y + tray.top.toInt() + 48)
                        delay(700); screenshot("rewind-image-preview")
                        withContext(Dispatchers.IO) { Robot().apply { keyPress(KeyEvent.VK_ESCAPE); keyRelease(KeyEvent.VK_ESCAPE) } }
                        delay(400); assertTrue(open, "Closing image preview must leave the message editor open")
                        click(origin.x + tray.left.toInt() + 80, origin.y + tray.top.toInt() + 22)
                        delay(400)
                        assertEquals(listOf(1), images.map { it.originalIndex })
                        withContext(Dispatchers.IO) { Robot().mouseMove(origin.x + tray.left.toInt() + 54, origin.y + tray.top.toInt() + 48) }
                        delay(900); screenshot("rewind-image-removed")
                    } catch (e: Throwable) { failure = e }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
