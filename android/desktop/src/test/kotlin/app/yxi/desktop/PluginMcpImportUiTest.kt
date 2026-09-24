package app.yxi.desktop

import androidx.compose.foundation.layout.*
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
import org.junit.jupiter.api.io.TempDir
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class PluginMcpImportUiTest {
    @TempDir lateinit var root: File
    @Test fun `local market preview registers shared references only after actual confirmation`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val manifest = root.resolve(".mcp.json")
        val raw = """{"mcpServers":{"fixture":{"url":"https://example.org/mcp","headers":{"Authorization":"${'$'}{PLUGIN_TOKEN}"}}}}"""
        manifest.writeText(raw)
        val registryFile = root.resolve("shared.json")
        val registry = SharedMcpRegistry(registryFile)
        val plugin = NativePlugin("fixture@local", "fixture", "Fixture", "", "开发工具", "Local fixture", null,
            "1.0", true, true, false, null, JSONObject().put("type", "local").put("path", root.path).toString(), "", "fixture")
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 620.dp, height = 740.dp)) {
                var dialogBounds by remember { mutableStateOf<Rect?>(null) }
                YxiTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(24.dp)) {
                    PluginMcpImportPreview(plugin, registry, Modifier.onGloballyPositioned { dialogBounds = it.boundsInWindow() })
                } } }
                LaunchedEffect(Unit) {
                    fun screenshot(name: String, target: java.awt.Window = window) {
                        ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(target.locationOnScreen, target.size)), "png", File("/results/$name.png"))
                    }
                    suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) { Robot().apply {
                        mouseMove(x, y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    } }
                    try {
                        delay(700)
                        assertTrue(registry.records.isEmpty()); assertFalse(registryFile.exists())
                        val origin = window.contentPane.locationOnScreen
                        click(origin.x + 125, origin.y + 48)
                        withTimeout(4000) { while (dialogBounds == null) delay(20) }
                        delay(250)
                        val dialog = java.awt.Window.getWindows().single { it.isVisible && it !== window && it is java.awt.Dialog }
                        assertTrue(registry.records.isEmpty()); assertFalse(registryFile.exists())
                        screenshot("plugin-mcp-preview", dialog)
                        val box = checkNotNull(dialogBounds)
                        val dialogOrigin = (dialog as javax.swing.JDialog).contentPane.locationOnScreen
                        click(dialogOrigin.x + box.right.toInt() - 75, dialogOrigin.y + box.bottom.toInt() - 35)
                        withTimeout(4000) { while (registry.records.isEmpty()) delay(20) }
                        val record = registry.records.single()
                        assertEquals("@local", record.definition.hostKey)
                        assertEquals(setOf("claude", "codex", "opencode"), record.desiredRunners)
                        assertEquals(mapOf("Authorization" to "PLUGIN_TOKEN"), record.definition.headerVariables)
                        assertEquals(raw, manifest.readText())
                        assertFalse(registryFile.readText().contains("Bearer"))
                        delay(250); screenshot("plugin-mcp-registered")
                    } catch (e: Throwable) { screenshot("plugin-mcp-failure"); failure = e }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
