package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

class AcpConversationUiTest {
    private class Fixture : AcpTransport {
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        fun config(model: String) = JSONArray().put(JSONObject().put("id", "model").put("name", "模型").put("category", "model")
            .put("type", "select").put("currentValue", model).put("options", JSONArray()
                .put(JSONObject().put("value", "provider/model-a").put("name", "Model A"))
                .put(JSONObject().put("value", "provider/model-b").put("name", "Model B"))))
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            val result = when (request.optString("method")) {
                "initialize" -> JSONObject().put("protocolVersion", 1)
                "session/new" -> JSONObject().put("sessionId", "ui-session").put("configOptions", config("provider/model-a")).put("modes", JSONObject().put("currentModeId", "ask")
                    .put("availableModes", JSONArray().put(JSONObject().put("id", "ask").put("name", "请求批准"))))
                "session/set_config_option" -> JSONObject().put("configOptions", config(request.getJSONObject("params").getString("value")))
                else -> null
            }
            if (result != null) emit(JSONObject().put("id", request.get("id")).put("result", result))
            return true
        }
        fun emit(value: JSONObject) { pipe.write((value.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
        override fun close() { pipe.close(); output.close() }
    }
    @Test fun `approval card renders native scopes and clicking once returns the exact native option`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val fixture = Fixture(); val client = AcpClient(fixture)
        runBlocking { client.initialize(); client.newSession("/sandbox/home") }
        val state = AppState()
        val record = LocalCodexTaskRecord("ui-session", System.getProperty("user.name"), System.getProperty("os.name"),
            "/sandbox/home/.hermes", "/sandbox/home", "项目文件核对", "native-default", System.currentTimeMillis(), "hermes", "native")
        val controller = AcpTaskController(record.key, record.threadId, client, state.instructions)
        state.localAcpTasks.controllers[record.key] = controller
        var failure: Throwable? = null
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1000.dp, height = 760.dp)) {
                    YxiTheme { Surface { AcpConversationPane(state, record) } }
                    LaunchedEffect(Unit) {
                        try {
                            delay(700)
                            suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) { Robot().apply {
                                mouseMove(window.locationOnScreen.x + x, window.locationOnScreen.y + y)
                                mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                            }; Unit }
                            click(100, 155); delay(800)
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize)), "png", File("/results/acp-model-menu.png"))
                            click(100, 250)
                            withTimeout(3000) { while (acpConfigSelectors(controller.configOptions).single().current != "provider/model-b") delay(20) }
                            val selection = fixture.writes.single { it.optString("method") == "session/set_config_option" }.getJSONObject("params")
                            assertEquals("model", selection.getString("configId"))
                            assertEquals("provider/model-b", selection.getString("value"))
                            fixture.emit(JSONObject().put("id", "approval-1").put("method", "session/request_permission").put("params", JSONObject()
                                .put("sessionId", "ui-session").put("toolCall", JSONObject().put("title", "允许读取项目文件？"))
                                .put("options", JSONArray().put(JSONObject().put("optionId", "once").put("name", "允许").put("kind", "allow_once"))
                                    .put(JSONObject().put("optionId", "always").put("name", "允许").put("kind", "allow_always"))
                                    .put(JSONObject().put("optionId", "deny").put("name", "拒绝").put("kind", "reject_once")))))
                            withTimeout(3000) { while (controller.pendingApprovals.isEmpty()) delay(20) }
                            delay(700)
                            ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/acp-approval.png"))
                            withContext(Dispatchers.IO) { Robot().apply {
                                mouseMove(window.locationOnScreen.x + 120, window.locationOnScreen.y + 255)
                                mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                            } }
                            withTimeout(3000) { while (fixture.writes.none { it.optString("id") == "approval-1" }) delay(20) }
                            val reply = fixture.writes.single { it.optString("id") == "approval-1" }.getJSONObject("result").getJSONObject("outcome")
                            assertEquals("selected", reply.getString("outcome"))
                            assertEquals("once", reply.getString("optionId"))
                        } catch (e: Throwable) { failure = e }
                        finally { exitApplication() }
                    }
                }
            }
        } finally { state.closeLocalFeatures() }
        failure?.let { throw it }
    }
}
