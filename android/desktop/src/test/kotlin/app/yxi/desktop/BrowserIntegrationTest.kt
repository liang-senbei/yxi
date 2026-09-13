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
            val captured = a.captureImage()
            assertTrue(captured.pixels.width > 100 && captured.pixels.height > 100)
            val clipboard = java.awt.datatransfer.Clipboard("isolated browser screenshot")
            captured.copyTo(clipboard)
            assertTrue(clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor))
            assertFalse(clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor))
            assertTrue(a.captureImage().png.isNotEmpty()) // A closed DevTools client must be recreated on a second capture.
            println("browser-fixture: repeated screenshot and image clipboard")
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
            js(a, """document.body.innerHTML='<button id=target style="font-family:sans-serif!important;font-size:16px!important;padding-top:3px!important;padding-right:9px">Selected component</button><input id=secret type=password value=DO_NOT_CAPTURE><span id=probe></span>';""")
            withContext(Dispatchers.Swing) { a.pick() }
            js(a, "document.getElementById('target').click();")
            eventually { a.selection != null }
            assertEquals("Selected component", a.selection!!.text)
            assertEquals("sans-serif", a.selection!!.computed["font-family"])
            withContext(Dispatchers.Swing) { a.trialStyle("font-family", "serif") }
            eventually { a.stylePending == null && a.styleChanges["font-family"] == "serif" }
            js(a, "document.getElementById('probe').textContent='FAMILY='+getComputedStyle(document.getElementById('target')).fontFamily;")
            eventually { text(a).contains("FAMILY=serif") }
            withContext(Dispatchers.Swing) { a.undoStyle() }
            eventually { a.stylePending == null && a.styleChanges.isEmpty() }
            js(a, "document.getElementById('probe').textContent='RESTORED_FAMILY='+document.getElementById('target').style.fontFamily+'/'+document.getElementById('target').style.getPropertyPriority('font-family');")
            eventually { text(a).contains("RESTORED_FAMILY=sans-serif/important") }
            println("browser-fixture: font family trial and original priority restored")
            withContext(Dispatchers.Swing) { a.trialStyle("font-size", "36", "drag-1") }
            eventually { a.stylePending == null && a.styleChanges["font-size"] == "36px" }
            js(a, "document.getElementById('probe').textContent='FONT='+getComputedStyle(document.getElementById('target')).fontSize;")
            eventually { text(a).contains("FONT=36px") }
            withContext(Dispatchers.Swing) { a.trialStyle("font-size", "48", "drag-1") }
            eventually { a.stylePending == null && a.styleChanges["font-size"] == "48px" }
            withContext(Dispatchers.Swing) { a.undoStyle() }
            eventually { a.stylePending == null && a.styleChanges.isEmpty() }
            js(a, "document.getElementById('probe').textContent='RESTORED='+document.getElementById('target').style.fontSize+'/'+document.getElementById('target').style.getPropertyPriority('font-size');")
            eventually { text(a).contains("RESTORED=16px/important") }
            withContext(Dispatchers.Swing) { a.trialStyle("padding", "20") }
            eventually { a.stylePending == null && a.styleChanges["padding"] == "20px" }
            js(a, "document.getElementById('target').style.outline='2px solid red';")
            withContext(Dispatchers.Swing) { a.resetStyle() }
            eventually { a.stylePending == null && a.styleChanges.isEmpty() }
            js(a, "const st=document.getElementById('target').style;document.getElementById('probe').textContent='TOP='+st.paddingTop+'/'+st.getPropertyPriority('padding-top')+' RIGHT='+st.paddingRight+' OUTLINE='+st.outlineWidth;")
            eventually { text(a).contains("TOP=3px/important RIGHT=9px OUTLINE=2px") }
            js(a, "document.getElementById('target').style.fontSize='21px';")
            withContext(Dispatchers.Swing) { a.pick() }
            js(a, "document.getElementById('target').click();")
            eventually { a.selection != null }
            assertEquals("21px", a.selection!!.computed["font-size"])
            js(a, "document.getElementById('target').outerHTML='<button id=target>Replacement</button>';")
            withContext(Dispatchers.Swing) { a.trialStyle("font-size", "30") }
            eventually { a.stylePending == null && a.selectionStale }
            js(a, "document.getElementById('probe').textContent='NEW_FONT='+document.getElementById('target').style.fontSize+';';")
            eventually { text(a).contains("NEW_FONT=;") }
            println("browser-fixture: style trials and restoration")
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
