package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.awt.Color
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

class ClaudeImageUiTest {
    private class Fixture(val session: String) : ClaudeControlTransport {
        override val output = PipedInputStream(65536)
        private val producer = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        var closed = false
        fun emit(value: JSONObject) { producer.write((value.toString() + "\n").toByteArray()); producer.flush() }
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            if (request.getString("type") == "control_request") {
                val value = if (request.getJSONObject("request").getString("subtype") == "initialize")
                    JSONObject().put("account", JSONObject().put("tokenSource", "claude.ai").put("apiProvider", "firstParty"))
                else JSONObject().put("effective", ClaudeSubscriptionSettings.overlay()).put("applied", JSONObject().put("model", "claude-fixture"))
                emit(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
                    .put("request_id", request.getString("request_id")).put("response", value)))
            }
            return true
        }
        fun complete() = emit(JSONObject().put("type", "result").put("uuid", "image-result").put("session_id", session)
            .put("subtype", "success").put("is_error", false))
        override fun close() { closed = true; producer.close(); output.close() }
    }
    @Test fun `image button previews and sends image only once with durable reference and exit protection`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        check(System.getProperty("user.home") == "/sandbox/home")
        check(System.getProperty("os.name").startsWith("Linux"))
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val folder = File("/sandbox/tmp/image-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "fixture-image.png")
        val pixels = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        pixels.createGraphics().apply {
            color = Color(40, 110, 220); fillRect(0, 0, 120, 80)
            color = Color.WHITE; fillOval(32, 12, 56, 56); dispose()
        }
        check(ImageIO.write(pixels, "png", source))
        val originalBytes = source.readBytes()
        val fixture = Fixture(UUID.randomUUID().toString())
        val state = AppState { queue, file -> LocalClaudeTasks(queue, file,
            LocalClaudeSubscription { _, _ -> ClaudeControlClient(fixture, fixture.session) }) }
        val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("/fixture/claude"), folder.path, "fixture")
        var failure: Throwable? = null
        var imageBounds: Rect? = null
        var sendBounds: Rect? = null
        var pickerCalls = 0
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 960.dp, height = 820.dp)) {
                    var record by remember { mutableStateOf<LocalCodexTaskRecord?>(null) }
                    YxiTheme { Surface { record?.let { saved ->
                        ClaudeConversationPane(state, saved, installations = listOf(runtime), pickImages = { pickerCalls++; listOf(source) },
                            imageButtonModifier = Modifier.onGloballyPositioned { imageBounds = it.boundsInWindow() },
                            sendButtonModifier = Modifier.onGloballyPositioned { sendBounds = it.boundsInWindow() })
                    } } }
                    LaunchedEffect(Unit) {
                        suspend fun click(bounds: Rect) = withContext(Dispatchers.IO) {
                            val origin = window.contentPane.locationOnScreen
                            Robot().apply { mouseMove(origin.x + bounds.center.x.toInt(), origin.y + bounds.center.y.toInt())
                                mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }
                        fun screenshot(name: String) { ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/$name.png")) }
                        try {
                            val saved = state.localClaudeTasks.create(runtime, folder.path, "图片发送测试")
                            record = saved
                            withTimeout(5000) { while (imageBounds == null || sendBounds == null) delay(20) }
                            delay(350)
                            assertEquals(0, state.pendingWork().drafts)
                            click(checkNotNull(imageBounds))
                            withTimeout(5000) { while (state.claudeImageDrafts[saved.key].isNullOrEmpty() || state.claudeImageCaptures.isNotEmpty()) delay(20) }
                            assertEquals(1, pickerCalls)
                            val image = state.claudeImageDrafts.getValue(saved.key).single()
                            assertEquals(1, state.pendingWork().drafts)
                            assertTrue(state.chatDrafts.getValue(saved.key).value.text.isEmpty())
                            assertTrue(fixture.writes.none { it.optString("type") == "user" })
                            delay(500); screenshot("claude-image-preview")
                            // The original picker file may disappear: the preview/send uses the captured immutable snapshot.
                            assertTrue(source.delete())
                            click(checkNotNull(sendBounds))
                            withTimeout(5000) { while (fixture.writes.none { it.optString("type") == "user" }) delay(20) }
                            val instruction = state.instructions.entries.single { it.taskKey == saved.key }
                            assertEquals(listOf(image), instruction.attachments)
                            assertTrue(instruction.text.isBlank())
                            assertTrue(state.claudeImageDrafts.getValue(saved.key).isEmpty())
                            assertEquals(0, state.pendingWork().drafts)
                            assertTrue(state.pendingWork().operations > 0)
                            val request = fixture.writes.single { it.optString("type") == "user" }
                            val blocks = request.getJSONObject("message").getJSONArray("content")
                            val images = (0 until blocks.length()).map { blocks.getJSONObject(it) }.filter { it.optString("type") == "image" }
                            val payload = images.single().getJSONObject("source")
                            assertEquals("image/png", payload.getString("media_type"))
                            assertContentEquals(originalBytes, Base64.getDecoder().decode(payload.getString("data")))
                            fixture.complete()
                            val controller = state.localClaudeTasks.controllers.getValue(saved.key)
                            withTimeout(5000) { while (controller.busy) delay(20) }
                            assertEquals(RuntimeTurnState.Completed, state.instructions.entries.single { it.id == instruction.id }.runtimeTurnState)
                            assertEquals(listOf(image), controller.messages.first { it.role == "User" }.attachments)
                            assertEquals(listOf(image), state.instructions.entries.single { it.id == instruction.id }.attachments)
                            assertEquals(0, state.pendingWork().operations)
                            assertEquals(1, fixture.writes.count { it.optString("type") == "user" })
                            delay(500); screenshot("claude-image-sent")
                        } catch (e: Throwable) {
                            failure = e
                            runCatching { screenshot("claude-image-failure") }
                        } finally { exitApplication() }
                    }
                }
            }
        } finally { state.closeLocalFeatures() }
        assertTrue(fixture.closed)
        failure?.let { throw it }
    }
}
