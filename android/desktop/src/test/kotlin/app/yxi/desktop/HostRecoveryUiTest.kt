package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Robot
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class HostRecoveryUiTest {
    @Test fun `recovery dialog cancels and restores only an explicitly selected valid copy`() {
        val path = System.getenv("YXI_HOST_RECOVERY_UI_OUT")
        assumeTrue(path != null)
        val root = File(path!!).apply { mkdirs() }
        System.setProperty("user.home", root.resolve("profile").apply { mkdirs() }.path)
        val codec = object : CredentialProtector {
            override fun protect(plain: ByteArray) = byteArrayOf(17) + plain.reversedArray()
            override fun unprotect(cipher: ByteArray): ByteArray { require(cipher.first() == 17.toByte()); return cipher.drop(1).toByteArray().reversedArray() }
        }
        val file = root.resolve("hosts.json")
        val original = """[{"id":"a","alias":"香港开发主机","hostname":"alpha.invalid","password":"synthetic-secret"},{"id":"b","alias":"备用主机","hostname":"beta.invalid","port":2222}]"""
        file.writeText(original)
        root.resolve("hosts.json.damaged").writeText("not a valid host array")
        val store = HostConfigFile(file, codec) { JSONArray(it) }
        store.read(); root.resolve("hosts.json.protected").delete()
        var open by mutableStateOf(true)
        var restored = false
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "Yxi host recovery fixture", state = rememberWindowState(width = 820.dp, height = 820.dp)) {
                YxiTheme { if (open) HostRecoveryDialog(close = { open = false }, loadCopies = { store.recoveryCopies() }) { copy ->
                    assertEquals(original, store.restoreMissing(copy.name, copy.fingerprint)); restored = true; open = false
                } }
                LaunchedEffect(Unit) {
                    suspend fun key(code: Int) = withContext(Dispatchers.IO) { Robot().apply { keyPress(code); keyRelease(code) } }
                    suspend fun shot(name: String) = withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(Rectangle(0, 0, 1400, 1000)), "png", root.resolve(name)) }
                    try {
                        delay(2000); shot("01-copies.png")
                        key(KeyEvent.VK_ESCAPE); delay(600)
                        assertFalse(open); assertFalse(restored); assertTrue(store.needsRecovery())
                        open = true; delay(800)
                        key(KeyEvent.VK_TAB); key(KeyEvent.VK_SPACE); delay(500); shot("02-selected.png")
                        key(KeyEvent.VK_TAB); key(KeyEvent.VK_TAB); key(KeyEvent.VK_ENTER); delay(500)
                        assertTrue(restored)
                        assertEquals(original, store.read())
                    } catch (e: Throwable) { failure = e; shot("failure.png") }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
