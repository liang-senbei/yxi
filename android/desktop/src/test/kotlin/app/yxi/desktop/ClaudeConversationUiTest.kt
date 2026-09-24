package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

class ClaudeConversationUiTest {
    private class Fixture(val session: String) : ClaudeControlTransport {
        override val output = PipedInputStream(65536)
        private val producer = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        private var turn = 0
        private var model = "claude-fixture"
        val command = (1..60).joinToString("\n") { "printf 'fixture line $it\\n'" }
        fun emit(value: JSONObject) { producer.write((value.toString() + "\n").toByteArray()); producer.flush() }
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            when (request.getString("type")) {
                "control_request" -> {
                    val subtype = request.getJSONObject("request").getString("subtype")
                    if (subtype == "set_model") model = request.getJSONObject("request").getString("model")
                    val value = if (subtype == "initialize") JSONObject().put("account", JSONObject().put("tokenSource", "claude.ai").put("apiProvider", "firstParty"))
                        .put("models", JSONArray().put(JSONObject().put("value", "sonnet").put("resolvedModel", "claude-fixture"))
                            .put(JSONObject().put("value", "opus").put("resolvedModel", "third-party-fixture")))
                        else JSONObject().put("effective", ClaudeSubscriptionSettings.overlay()).put("applied", JSONObject().put("model", model))
                    emit(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success").put("request_id", request.getString("request_id")).put("response", value)))
                    if (subtype == "interrupt") {
                        emit(JSONObject().put("type", "control_cancel_request").put("request_id", "permission-$turn"))
                        emit(JSONObject().put("type", "result").put("uuid", "result-$turn").put("session_id", session)
                            .put("subtype", "error_during_execution").put("is_error", true).put("terminal_reason", "aborted_streaming"))
                    }
                }
                "user" -> {
                    turn++
                    emit(JSONObject().put("type", "control_request").put("request_id", "permission-$turn").put("request", JSONObject()
                        .put("subtype", "can_use_tool").put("tool_name", "Bash").put("input", JSONObject().put("command", command))))
                }
                "control_response" -> {
                    emit(JSONObject().put("type", "assistant").put("uuid", "answer-$turn").put("message", JSONObject().put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "## 检查完成\n\n这是一条 **Markdown** 回复。")))))
                    emit(JSONObject().put("type", "result").put("uuid", "result-$turn").put("session_id", session).put("subtype", "success").put("is_error", false))
                }
            }
            return true
        }
        override fun close() { producer.close(); output.close() }
    }
    @Test fun `new Claude dialog sends once with Enter and routes the allow-once UI choice`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val fixture = Fixture(UUID.randomUUID().toString())
        val state = AppState { queue, file -> LocalClaudeTasks(queue, file, LocalClaudeSubscription { _, _ -> ClaudeControlClient(fixture, fixture.session) }) }
        val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("/fixture/claude"), "/sandbox/home/.claude", "fixture")
        var failure: Throwable? = null; var bounds: Rect? = null
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 960.dp, height = 820.dp)) {
                    var creating by remember { mutableStateOf(true) }
                    YxiTheme { Surface {
                        if (creating) NewClaudeConversationDialog(state, runtime, { creating = false }, Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
                            state.localSelectedTaskKey = it.key; creating = false
                        } else state.localClaudeTasks.registry.records.singleOrNull()?.let { ClaudeConversationPane(state, it) }
                    } }
                    LaunchedEffect(Unit) {
                        suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) {
                            val origin = window.contentPane.locationOnScreen
                            Robot().apply { mouseMove(origin.x + x, origin.y + y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }
                        fun screenshot(name: String) { ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/$name.png")) }
                        try {
                            withTimeout(5000) { while (bounds == null) delay(20) }; delay(400)
                            val box = checkNotNull(bounds); click(box.right.toInt() - 65, box.bottom.toInt() - 45)
                            withTimeout(3000) { while (state.localSelectedTaskKey == null) delay(20) }
                            assertTrue(fixture.writes.none { it.optString("type") == "user" })
                            val record = state.localClaudeTasks.registry.records.single(); val controller = state.localClaudeTasks.controllers.getValue(record.key)
                            state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) }.value = TextFieldValue("检查界面发送")
                            delay(500); click(150, window.height - 115)
                            withContext(Dispatchers.IO) { Robot().apply { keyPress(KeyEvent.VK_ENTER); keyRelease(KeyEvent.VK_ENTER) } }
                            withTimeout(3000) { while (controller.pendingApprovals.isEmpty()) delay(20) }
                            assertEquals(1, state.localOperations)
                            assertEquals(1, fixture.writes.count { it.optString("type") == "user" })
                            assertEquals("", state.chatDrafts.getValue(record.key).value.text)
                            delay(500); screenshot("claude-conversation-approval")
                            withContext(Dispatchers.IO) { Robot().apply {
                                keyPress(KeyEvent.VK_SHIFT)
                                repeat(2) { keyPress(KeyEvent.VK_TAB); keyRelease(KeyEvent.VK_TAB); delay(80) }
                                keyRelease(KeyEvent.VK_SHIFT); keyPress(KeyEvent.VK_SPACE); keyRelease(KeyEvent.VK_SPACE)
                            } }
                            withTimeout(3000) { while (controller.busy) delay(20) }
                            assertEquals("allow", fixture.writes.single { it.optString("type") == "control_response" }.getJSONObject("response").getJSONObject("response").getString("behavior"))
                            assertEquals(fixture.command, fixture.writes.single { it.optString("type") == "control_response" }
                                .getJSONObject("response").getJSONObject("response").getJSONObject("updatedInput").getString("command"))
                            assertEquals(RuntimeTurnState.Completed, state.instructions.entries.single().runtimeTurnState)
                            assertEquals(0, state.localOperations)
                            delay(400); screenshot("claude-conversation-completed")
                            suspend fun sendNext(text: String) {
                                state.chatDrafts.getValue(record.key).value = TextFieldValue(text)
                                delay(300); click(150, window.height - 115)
                                withContext(Dispatchers.IO) { Robot().apply { keyPress(KeyEvent.VK_ENTER); keyRelease(KeyEvent.VK_ENTER) } }
                                withTimeout(3000) { while (controller.pendingApprovals.isEmpty()) delay(20) }
                                delay(300)
                            }
                            sendNext("检查停止按钮")
                            click(110, window.height - 45)
                            withTimeout(3000) { while (controller.busy || controller.cancelling) delay(20) }
                            assertTrue(controller.pendingApprovals.isEmpty())
                            assertEquals(1, fixture.writes.count { it.optString("type") == "control_request" && it.getJSONObject("request").optString("subtype") == "interrupt" })
                            assertEquals(1, fixture.writes.count { it.optString("type") == "control_response" })
                            assertEquals(RuntimeTurnState.Interrupted, state.instructions.entries.last().runtimeTurnState)
                            assertEquals(0, state.localOperations)
                            delay(300); screenshot("claude-conversation-stopped")
                            sendNext("停止后继续，并拒绝工具")
                            withContext(Dispatchers.IO) { Robot().apply {
                                keyPress(KeyEvent.VK_SHIFT); keyPress(KeyEvent.VK_TAB); keyRelease(KeyEvent.VK_TAB)
                                keyRelease(KeyEvent.VK_SHIFT); keyPress(KeyEvent.VK_SPACE); keyRelease(KeyEvent.VK_SPACE)
                            } }
                            withTimeout(3000) { while (controller.busy) delay(20) }
                            assertEquals("deny", fixture.writes.last { it.optString("type") == "control_response" }
                                .getJSONObject("response").getJSONObject("response").getString("behavior"))
                            assertEquals(listOf(RuntimeTurnState.Completed, RuntimeTurnState.Interrupted, RuntimeTurnState.Completed),
                                state.instructions.entries.map { it.runtimeTurnState })
                            assertEquals(3, fixture.writes.count { it.optString("type") == "user" })
                            assertEquals(0, state.localOperations)
                            delay(300); screenshot("claude-conversation-continued")
                            click(80, 165); delay(400); screenshot("claude-conversation-model-menu")
                            withContext(Dispatchers.IO) { Robot().apply {
                                keyPress(KeyEvent.VK_DOWN); keyRelease(KeyEvent.VK_DOWN)
                                keyPress(KeyEvent.VK_DOWN); keyRelease(KeyEvent.VK_DOWN)
                                keyPress(KeyEvent.VK_ENTER); keyRelease(KeyEvent.VK_ENTER)
                            } }
                            withTimeout(3000) { while (controller.model != "third-party-fixture" || controller.changingModel) delay(20) }
                            assertEquals("third-party-fixture", state.localClaudeTasks.registry.records.single().model)
                            assertEquals(1, fixture.writes.count { it.optString("type") == "control_request" && it.getJSONObject("request").optString("subtype") == "set_model" })
                            assertEquals(3, fixture.writes.count { it.optString("type") == "user" })
                            delay(300); screenshot("claude-conversation-model-selected")
                        } catch (e: Throwable) { screenshot("claude-conversation-failure"); failure = e }
                        finally { exitApplication() }
                    }
                }
            }
        } finally { state.closeLocalFeatures() }
        failure?.let { throw it }
    }
}
