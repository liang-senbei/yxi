package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.*

class OpenCodeConversationUiTest {
    @Test fun `local OpenCode send button persists one message and renders native reply`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val history = JSONArray()
        var posts = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val response = when (exchange.requestURI.path) {
                "/session/ses_ui" -> """{"id":"ses_ui","directory":"/sandbox/home"}"""
                "/session/ses_ui/message" -> history.toString()
                "/session/status" -> "{}"
                "/permission", "/question" -> "[]"
                "/provider" -> """{"connected":["fixture"],"all":[{"id":"fixture","models":{"model":{"id":"model","name":"Model"}}}]}"""
                "/session/ses_ui/prompt_async" -> {
                    posts++
                    val input = JSONObject(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                    val id = input.getString("messageID")
                    history.put(JSONObject().put("info", JSONObject().put("id", id).put("sessionID", "ses_ui").put("role", "user").put("model", input.getJSONObject("model")))
                        .put("parts", input.getJSONArray("parts")))
                    history.put(JSONObject().put("info", JSONObject().put("id", "reply").put("sessionID", "ses_ui").put("role", "assistant").put("parentID", id)
                        .put("finish", "stop").put("time", JSONObject().put("created", 1).put("completed", 2)))
                        .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "OpenCode 回复已显示"))))
                    ""
                }
                else -> error(exchange.requestURI.path)
            }
            if (response.isEmpty()) exchange.sendResponseHeaders(204, -1) else {
                val bytes = response.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        server.start()
        val state = AppState()
        val record = LocalCodexTaskRecord("ses_ui", "fixture", "Linux", "/sandbox/home", "/sandbox/home", "OpenCode 本地对话", "model", 1, "opencode", "fixture")
        val controller = OpenCodeTaskController(record.key, record.threadId, record.directory, record.provider, record.model,
            OpenCodeClient(server.address.port, "fixture", record.directory), state.instructions)
        state.localOpenCodeTasks.registry.save(record)
        state.localOpenCodeTasks.controllers[record.key] = controller
        state.chatDrafts[record.key] = mutableStateOf(TextFieldValue("UI-OPENCODE-FIXTURE"))
        var failure: Throwable? = null
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1180.dp, height = 760.dp)) {
                    YxiTheme { Surface { OpenCodeConversationPane(state, record) } }
                    LaunchedEffect(Unit) {
                        try {
                            withTimeout(8000) { while (!controller.ready) delay(30) }
                            delay(700)
                            withContext(Dispatchers.IO) { Robot().apply {
                                mouseMove(window.locationOnScreen.x + 60, window.locationOnScreen.y + window.height - 48)
                                repeat(2) { mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                            } }
                            withTimeout(8000) { while (state.instructions.entries.none { it.taskKey == record.key && it.runtimeTurnState == RuntimeTurnState.Completed }) delay(50) }
                            assertEquals(1, posts)
                            assertEquals("", state.chatDrafts.getValue(record.key).value.text)
                            assertEquals(2, controller.messages.size)
                            delay(300)
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/opencode-conversation.png"))
                        } catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
        } finally { state.closeLocalFeatures(); server.stop(0) }
        failure?.let { throw it }
    }
}
