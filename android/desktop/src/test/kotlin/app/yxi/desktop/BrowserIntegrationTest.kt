package app.yxi.desktop

import app.yxi.ssh.HostKeys
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Base64
import javax.swing.JFrame
import kotlin.test.*

class BrowserIntegrationTest {
    @Test fun `native browser renders live content isolates cookies and returns selection`(): Unit = runBlocking {
        val dir = System.getenv("YXI_BROWSER_FIXTURE")
        assumeTrue(dir != null, "Requires an isolated SSH/HTTP fixture and X display")
        val root = File(dir!!)
        val expected = root.resolve("host.pub").readText().split(' ')[1]
        val keys = object : HostKeys {
            override var changedDetected = false
            override fun check(host: String?, key: ByteArray?): Int { val ok = key != null && Base64.getEncoder().encodeToString(key) == expected; changedDetected = !ok; return if (ok) HostKeyRepository.OK else HostKeyRepository.CHANGED }
            override fun add(key: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "browser-fixture"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            override fun userInfo() = object : UserInfo {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String? = null
                override fun promptPassword(message: String?) = false
                override fun promptPassphrase(message: String?) = false
                override fun promptYesNo(message: String?) = false
                override fun showMessage(message: String?) = Unit
            }
        }
        val host = Host("browser-fixture", "Browser fixture", "127.0.0.1", root.resolve("port").readText().trim().toInt(), "root", root.resolve("client").path)
        val conn = Conn(host, keys)
        val a = BrowserPreview(host, "a"); val b = BrowserPreview(host, "b")
        val frames = mutableListOf<JFrame>()
        suspend fun text(preview: BrowserPreview): String {
            val result = CompletableDeferred<String>()
            withContext(Dispatchers.Swing) { preview.handle!!.getText { result.complete(it) } }
            return withTimeout(10000) { result.await() }
        }
        suspend fun eventually(check: suspend () -> Boolean) = withTimeout(20000) { while (!check()) delay(150) }
        suspend fun js(preview: BrowserPreview, code: String) = withContext(Dispatchers.Swing) { preview.handle!!.executeJavaScript(code, preview.handle!!.url, 0) }
        try {
            conn.ssh.connect()
            root.resolve("browser-state.txt").writeText("<h1>INITIAL_PREVIEW</h1>")
            val address = "http://127.0.0.1:${root.resolve("http-port").readText().trim()}/"
            withContext(Dispatchers.Swing) {
                a.open(conn, address)
                frames += JFrame("Yxi browser fixture A").apply { add(a.handle!!.uiComponent); setSize(640, 640); setLocation(20, 20); isVisible = true }
            }
            eventually { a.status == "页面已载入" || a.error.isNotBlank() }
            assertEquals("", a.error)
            eventually { text(a).contains("INITIAL_PREVIEW") }
            println("browser-fixture: initial render")
            js(a, "window.live=new WebSocket(location.origin.replace('http','ws')+'/live');live.onmessage=e=>document.body.innerText=e.data;")
            root.resolve("browser-state.txt").writeText("UPDATED_OVER_WEBSOCKET")
            eventually { text(a).contains("UPDATED_OVER_WEBSOCKET") }
            println("browser-fixture: websocket update")
            conn.ssh.disconnect()
            assertTrue(a.needsReconnect)
            conn.ssh.connect()
            root.resolve("browser-state.txt").writeText("RECONNECTED_PREVIEW")
            withContext(Dispatchers.Swing) { a.open(conn, a.address) }
            eventually { a.status == "页面已载入" || a.error.isNotBlank() }
            assertEquals("", a.error)
            eventually { text(a).contains("RECONNECTED_PREVIEW") }
            println("browser-fixture: reconnect")
            js(a, "document.cookie='yxi_fixture=alpha; path=/';document.body.innerText=document.cookie;")
            eventually { text(a).contains("yxi_fixture=alpha") }
            withContext(Dispatchers.Swing) {
                b.open(conn, address)
                frames += JFrame("Yxi browser fixture B").apply { add(b.handle!!.uiComponent); setSize(640, 640); setLocation(700, 20); isVisible = true }
            }
            eventually { b.status == "页面已载入" || b.error.isNotBlank() }
            assertEquals("", b.error)
            eventually { text(b).contains("RECONNECTED_PREVIEW") }
            js(b, "document.body.innerText=document.cookie||'COOKIE_ISOLATED';")
            eventually { text(b).contains("COOKIE_ISOLATED") }
            println("browser-fixture: cookie isolation")
            js(a, "document.body.innerHTML='<button id=target>Selected component</button><input id=secret type=password value=DO_NOT_CAPTURE>';")
            withContext(Dispatchers.Swing) { a.pick() }
            js(a, "document.getElementById('target').click();")
            eventually { a.selection != null }
            assertEquals("Selected component", a.selection!!.text)
            withContext(Dispatchers.Swing) { a.pick() }
            js(a, "document.getElementById('secret').click();")
            eventually { a.selection != null }
            assertEquals("", a.selection!!.text)
            withContext(Dispatchers.Swing) { a.handle!!.loadURL("file:///etc/passwd") }
            eventually { a.error.isNotBlank() }
            assertFalse(text(a).contains("root:x:"))
        } finally {
            withContext(Dispatchers.Swing) { a.close(); b.close(); frames.forEach { it.dispose() } }
            conn.ssh.disconnect(); assertTrue(BrowserRuntime.shutdown(), "Browser cleanup must finish")
        }
    }
}
