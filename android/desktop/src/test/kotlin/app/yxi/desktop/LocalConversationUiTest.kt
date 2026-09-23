package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalConversationUiTest {
    @Test fun `local conversation send button commits the outbox and renders streamed results`() {
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        FakeRunner("delta-full").use { runner ->
            handshake(runner)
            val state = AppState()
            val record = LocalCodexTaskRecord("thr-1", System.getProperty("user.name"), System.getProperty("os.name"),
                "/sandbox/home/.codex", "/sandbox/home", "本地对话测试", "fixture-model", 1)
            val controller = CodexTaskController(record.key, record.threadId, runner.client, state.instructions, initialModel = record.model)
            state.localCodexTasks.registry.save(record)
            state.localCodexTasks.controllers[record.key] = controller
            state.chatDrafts[record.key] = mutableStateOf(TextFieldValue("GUI-LOCAL-FIXTURE"))
            runBlocking { controller.reconcile() }
            var failure: Throwable? = null
            try {
                application(exitProcessOnExit = false) {
                    Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1180.dp, height = 760.dp)) {
                        YxiTheme { Surface { LocalConversationPane(state, record) } }
                        LaunchedEffect(Unit) {
                            try {
                                delay(700)
                                withContext(Dispatchers.IO) { Robot().apply {
                                    mouseMove(window.locationOnScreen.x + 60, window.locationOnScreen.y + window.height - 48)
                                    mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                    mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                } }
                                withTimeout(8000) { while (state.instructions.entries.none { it.runtimeTurnState == RuntimeTurnState.Completed }) delay(50) }
                                assertEquals("", state.chatDrafts.getValue(record.key).value.text)
                                val requests = runner.inboundJson().filter { it.optString("method") == "turn/start" }
                                assertEquals(1, requests.size)
                                assertTrue(requests.single().getJSONObject("params").getJSONArray("input").toString().contains("GUI-LOCAL-FIXTURE"))
                                assertTrue(controller.messages.any { it.text == "你好，世界" })
                                delay(300)
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/local-conversation.png"))
                            } catch (e: Throwable) { failure = e }
                            finally { exitApplication() }
                        }
                    }
                }
                failure?.let { throw it }
            } finally { state.closeLocalFeatures() }
        }
    }
}
