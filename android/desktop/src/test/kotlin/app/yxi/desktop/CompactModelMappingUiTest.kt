package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class CompactModelMappingUiTest {
    @Test fun `compact table renders and real popup keyboard selection does not grow form`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val models = listOf(ProviderModels.Model("glm-5.3-flash"), ProviderModels.Model("glm-5.3"), ProviderModels.Model("deepseek-chat"))
        val modelValues = mutableStateMapOf("SONNET" to "glm-5.3-flash[1m]", "OPUS" to "glm-5.3[1m]", "FABLE" to "", "HAIKU" to "glm-5.3-flash", "SUBAGENT" to "")
        val names = mutableStateMapOf("SONNET" to "glm-5.3-flash", "OPUS" to "glm-5.3", "FABLE" to "", "HAIKU" to "glm-5.3-flash")
        var interaction by mutableStateOf(false)
        var theme by mutableStateOf("light")
        var fullForm by mutableStateOf(false)
        var selected by mutableStateOf("glm-5.3-flash")
        var bounds: Rect? = null
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            val windowState = rememberWindowState(width = 1180.dp, height = 760.dp)
            Window(onCloseRequest = ::exitApplication, state = windowState, title = "Yxi model mapping preview") {
                key(theme) { YxiTheme {
                    if (fullForm) RouteForm(app.yxi.agent.Lines.Line("fixture", "Zhipu GLM", "https://open.bigmodel.cn/api/anthropic",
                        apiKey = "fixture-only", extra = org.json.JSONObject().put("env", org.json.JSONObject().apply {
                            modelValues.forEach { (id, model) -> if (id != "SUBAGENT") put("ANTHROPIC_DEFAULT_${id}_MODEL", model) }
                            names.forEach { (id, name) -> put("ANTHROPIC_DEFAULT_${id}_MODEL_NAME", name) }
                        })), remember { Conn(Host("fixture", "hk13 · 隔离预览", "192.0.2.1"), NoHostKeys) }, {}, {})
                    else {
                    Column(Modifier.fillMaxSize().background(Tokens.current.surface2).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("编辑供应商 · Claude Code", style = MaterialTheme.typography.titleLarge)
                        if (interaction) CompactModelInput(selected, models, { selected = it }, "测试模型",
                            Modifier.width(460.dp).onGloballyPositioned { bounds = it.boundsInWindow() })
                        else Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.current.border), color = Tokens.current.surface2) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CompactMappingToolbar(true, {}, {}, {}, false)
                                Text("显示名称只影响菜单；1M 是上下文声明，需要运行器和供应商支持。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                                CompactModelMappings(listOf("SONNET" to "Sonnet", "OPUS" to "Opus", "FABLE" to "Fable", "HAIKU" to "Haiku", "SUBAGENT" to "Subagent").map { (id, label) ->
                                    ModelMappingRow(id, label, modelValues.getValue(id), names[id], id != "HAIKU")
                                }, models, { id, value -> modelValues[id] = value }, { id, value -> names[id] = value }, "glm-5.3-flash", {})
                            }
                        }
                    }
                    }
                } }
                LaunchedEffect(Unit) {
                    suspend fun screenshot(name: String) = withContext(Dispatchers.IO) {
                        check(ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/$name.png")))
                    }
                    suspend fun key(code: Int) = withContext(Dispatchers.IO) { Robot().apply { keyPress(code); keyRelease(code) } }
                    suspend fun openPicker() {
                        val b = requireNotNull(bounds)
                        withContext(Dispatchers.IO) { Robot().apply {
                            mouseMove(window.locationOnScreen.x + b.right.toInt() - 16, window.locationOnScreen.y + b.center.y.toInt())
                            mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        } }
                        delay(400)
                    }
                    try {
                        delay(1000); screenshot("model-mapping-wide")
                        Store.setPref("theme", "dark"); theme = "dark"; delay(500); screenshot("model-mapping-dark")
                        Store.setPref("theme", "light"); theme = "light"; delay(300)
                        windowState.size = androidx.compose.ui.unit.DpSize(680.dp, 960.dp); delay(600); screenshot("model-mapping-narrow")
                        windowState.size = androidx.compose.ui.unit.DpSize(1180.dp, 760.dp); interaction = true; delay(500)
                        assertEquals(36f, requireNotNull(bounds).height, 1f)
                        val before = requireNotNull(bounds)
                        openPicker(); screenshot("model-picker-open")
                        key(KeyEvent.VK_DOWN); key(KeyEvent.VK_ENTER); delay(400)
                        assertEquals("glm-5.3", selected)
                        assertEquals(before, bounds, "Popup must not change form geometry")
                        openPicker(); key(KeyEvent.VK_D); key(KeyEvent.VK_E); key(KeyEvent.VK_E); key(KeyEvent.VK_P); delay(300)
                        key(KeyEvent.VK_ENTER); delay(300)
                        assertEquals("deepseek-chat", selected)
                        openPicker(); key(KeyEvent.VK_UP); key(KeyEvent.VK_ESCAPE); delay(200)
                        assertEquals("deepseek-chat", selected, "Escape must not apply the highlighted item")
                        fullForm = true; delay(700)
                        withContext(Dispatchers.IO) { Robot().apply {
                            mouseMove(window.locationOnScreen.x + 900, window.locationOnScreen.y + 600); mouseWheel(8)
                        } }
                        delay(700); screenshot("provider-editor-production")
                    } catch (e: Throwable) { failure = e }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
