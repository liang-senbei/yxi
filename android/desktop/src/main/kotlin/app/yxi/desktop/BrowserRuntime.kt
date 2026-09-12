package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.browser.*
import org.cef.handler.*
import org.cef.callback.CefQueryCallback
import org.cef.network.CefRequest
import org.cef.misc.BoolRef
import org.json.JSONObject
import java.io.File
import javax.swing.SwingUtilities

object BrowserRuntime {
    val fixtureMode = java.lang.Boolean.getBoolean("yxi.browser.localFixture")
    private var app: CefApp? = null
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val clients = java.util.concurrent.ConcurrentHashMap<org.cef.CefClient, CefRequestContext>()
    private val retiring = java.util.concurrent.ConcurrentHashMap.newKeySet<org.cef.CefClient>()
    private val cleanup = java.util.concurrent.Executors.newCachedThreadPool { runnable -> Thread(runnable, "yxi-browser-cleanup").apply { isDaemon = true } }
    @Synchronized fun newClient(app: CefApp): Pair<org.cef.CefClient, CefRequestContext> {
        check(!stopping.get()) { "浏览器正在退出" }
        val c = app.createClient()
        try {
            val context = CefRequestContext.createContext(null)
            clients[c] = context
            return c to context
        } catch (error: Throwable) { c.dispose(); throw error }
    }
    fun retire(client: org.cef.CefClient) {
        if (!retiring.add(client)) return
        val dispose = {
            client.dispose()
            cleanup.execute {
                // Never hold CefApp's monitor while waiting on a client's browser map.
                while (!org.cef.YxiClientLifecycle.isDrained(client)) Thread.sleep(20)
                clients.remove(client)?.dispose()
                retiring.remove(client)
            }
        }
        if (SwingUtilities.isEventDispatchThread()) dispose() else SwingUtilities.invokeLater(dispose)
    }
    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        val current = synchronized(this@BrowserRuntime) { stopping.set(true); app } ?: return@withContext true
        clients.keys.toList().forEach(::retire)
        if (withTimeoutOrNull(10000) { while (clients.isNotEmpty()) delay(20); true } != true) return@withContext false
        if (disposed.compareAndSet(false, true)) { current.dispose(); app = null }
        withTimeoutOrNull(10000) { while (CefApp.getState() != CefApp.CefAppState.TERMINATED) delay(20); true } == true
    }
    @Synchronized fun get(): CefApp {
        check(!stopping.get()) { "浏览器正在退出" }
        app?.let { return it }
        val linuxRoot = System.getProperty("os.name").startsWith("Linux") && System.getProperty("user.name") == "root"
        check(!linuxRoot || fixtureMode) { "Linux 浏览器请使用普通用户启动；root仅支持受限的本地测试模式。" }
        val builder = CefAppBuilder()
        builder.setInstallDir(File(System.getProperty("yxi.browser.runtimeDir", File(Store.dir, "browser-runtime/146.0.10").path)))
        builder.cefSettings.windowless_rendering_enabled = false
        builder.cefSettings.root_cache_path = File(Store.dir, "browser-data").absolutePath
        builder.cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE
        if (fixtureMode) {
            builder.cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
            builder.cefSettings.log_file = File(Store.dir, "browser-fixture.log").absolutePath
        }
        // Runtime natives are bundled by Gradle; do not silently download executables on the user's first preview.
        builder.setMirrors(emptyList())
        if (linuxRoot && fixtureMode) builder.addJcefArgs("--no-sandbox", "--disable-background-networking", "--disable-component-update")
        return builder.build().also { app = it }
    }
}

data class PageSelection(val url: String, val selector: String, val text: String, val tag: String, val capturedAt: Long = System.currentTimeMillis())

class BrowserPreview(val owner: Host, val taskId: String) {
    var address by mutableStateOf("http://localhost:3000/")
    var title by mutableStateOf("网页预览")
    var status by mutableStateOf("输入地址或服务器端口以打开预览")
    var error by mutableStateOf("")
    var loading by mutableStateOf(false)
    var preparing by mutableStateOf(false)
        private set
    var comment by mutableStateOf("")
    var commentAdded by mutableStateOf(false)
    var selectionStale by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var selection by mutableStateOf<PageSelection?>(null)
    var picking by mutableStateOf(false)
    var handle by mutableStateOf<CefBrowser?>(null)
        private set
    private var client: org.cef.CefClient? = null
    private var context: CefRequestContext? = null
    private data class Forwarded(val address: PreviewAddress, val lease: SshSession.PreviewForward)
    private val forwards = java.util.concurrent.ConcurrentHashMap<String, Forwarded>()
    private var target: PreviewAddress? = null
    private val opening = Mutex()
    private fun ui(action: () -> Unit) { SwingUtilities.invokeLater(action) }
    val source get() = target?.remotePort?.let { "${owner.label} → 远端 ${target?.remoteHost}:$it" } ?: "本机打开网页 · ${owner.label} 的任务"
    val remote get() = target?.remotePort != null
    val needsReconnect get() = target?.let { page -> page.remotePort?.let { forwards["${page.remoteHost}:$it"]?.lease?.active != true } } == true

    suspend fun open(conn: Conn, input: String) = opening.withLock {
        check(DocumentEndpoint.of(conn.host) == DocumentEndpoint.of(owner) && conn.host.id == owner.id) { "主机已变化，请关闭旧预览后重新打开" }
        val next = PreviewAddress.parse(input)
        check(PreviewAddress.allowed(next.url, BrowserRuntime.fixtureMode)) { "此地址不能在当前预览环境中打开" }
        preparing = true
        try {
        loading = true; error = ""; selectionStale = selection != null; picking = false; status = "准备浏览器…"
        val cef = withContext(Dispatchers.IO) { BrowserRuntime.get() }
        val lease = if (next.remotePort != null) {
            val routeKey = "${next.remoteHost}:${next.remotePort}"
            val old = forwards[routeKey]
            if (old?.lease?.active == true) old.lease else {
                old?.lease?.close()
                val created = withContext(Dispatchers.IO) { conn.ssh.forwardPreview(next.remotePort, next.remoteHost, old?.lease?.localPort ?: 0) }
                forwards[routeKey] = Forwarded(next, created)
                created
            }
        } else null
        target = next
        address = next.url
        val resolved = lease?.let { next.forwarded(it.localPort) } ?: next.url
        if (handle == null) create(cef, resolved) else { handle!!.stopLoad(); handle!!.loadURL(resolved) }
        } finally { preparing = false }
    }

    private fun create(app: CefApp, url: String) {
        val (c, ctx) = BrowserRuntime.newClient(app)
        client = c; context = ctx
        c.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, gesture: Boolean, redirect: Boolean): Boolean {
                val blocked = !permittedRequest(request.url, resource = false)
                if (blocked && frame.isMain) ui { error = "不支持此页面地址" }
                return blocked
            }
            override fun getResourceRequestHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest, navigation: Boolean, download: Boolean, initiator: String, disable: BoolRef): CefResourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
                override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest) = !permittedRequest(request.url, resource = true)
            }
        })
        c.addDownloadHandler(object : CefDownloadHandlerAdapter() {
            override fun onBeforeDownload(browser: CefBrowser, item: org.cef.callback.CefDownloadItem, name: String, callback: org.cef.callback.CefBeforeDownloadCallback): Boolean {
                ui { error = "下载请使用外部浏览器打开；预览未保存此文件。" }
                return true
            }
        })
        c.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, url: String, name: String): Boolean { ui { error = "页面请求打开新窗口，请将目标地址粘贴到预览栏" }; return true }
        })
        c.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) { if (frame.isMain) ui {
                if (url == "about:blank" || !PreviewAddress.allowed(url, BrowserRuntime.fixtureMode)) return@ui
                val actual = runCatching { java.net.URI(url) }.getOrNull()
                val route = forwards.values.firstOrNull { actual?.host == "127.0.0.1" && it.lease.localPort == actual.port }
                if (route != null) { target = route.address; address = route.address.logical(url, route.lease.localPort) }
                else { address = url; target = runCatching { PreviewAddress.parse(url) }.getOrNull() }
            } }
            override fun onTitleChange(browser: CefBrowser, value: String) { ui { title = value.take(120) } }
            override fun onConsoleMessage(browser: CefBrowser, severity: CefSettings.LogSeverity, message: String, source: String, line: Int) = true
        })
        c.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(browser: CefBrowser, busy: Boolean, back: Boolean, next: Boolean) { ui { loading = busy; canBack = back; canForward = next; if (!busy && error.isBlank()) status = "页面已载入" } }
            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transition: CefRequest.TransitionType) { if (frame.isMain) ui { selectionStale = selection != null; picking = false; error = ""; status = "正在加载…" } }
            override fun onLoadError(browser: CefBrowser, frame: CefFrame, code: CefLoadHandler.ErrorCode, text: String, url: String) { if (frame.isMain && code != CefLoadHandler.ErrorCode.ERR_ABORTED) ui { error = "加载失败：$text"; loading = false } }
        })
        val router = CefMessageRouter.create()
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(browser: CefBrowser, frame: CefFrame, id: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
                if (!frame.isMain || request.length > 16000) { callback.failure(400, "Invalid selection"); return true }
                val obj = runCatching { JSONObject(request) }.getOrNull()
                if (obj == null || obj.optString("type") != "selection") { callback.failure(400, "Unknown message"); return true }
                ui {
                    if (picking) {
                        selection = PageSelection(address, obj.optString("selector").replace('\n', ' ').replace('\r', ' ').take(1000), obj.optString("text").take(4000), obj.optString("tag").replace('\n', ' ').replace('\r', ' ').take(30))
                        selectionStale = false; commentAdded = false
                        picking = false
                    }
                }
                callback.success("received"); return true
            }
        }, true)
        c.addMessageRouter(router)
        handle = c.createBrowser(url, false, false, ctx)
    }
    fun pick() {
        if (picking) {
            picking = false
            handle?.executeJavaScript("window.__yxiPickCancel?.()", handle?.url.orEmpty(), 0)
            return
        }
        picking = true; selection = null; selectionStale = false
        handle?.executeJavaScript(PICK_SCRIPT, handle?.url.orEmpty(), 0)
    }
    fun close() {
        forwards.values.forEach { it.lease.close() }; forwards.clear()
        handle?.close(true); handle = null
        client?.let { BrowserRuntime.retire(it) }; client = null
        context = null
        selection = null; picking = false; loading = false
    }
    private fun permittedRequest(url: String, resource: Boolean): Boolean {
        if (!PreviewAddress.allowed(url, BrowserRuntime.fixtureMode, resource)) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        // A remote page must not accidentally access an unrelated localhost service on the client.
        if (PreviewAddress.loopback(uri.host)) return uri.host == "127.0.0.1" && forwards.values.any { it.lease.active && it.lease.localPort == uri.port }
        return true
    }
    companion object {
        private val PICK_SCRIPT = """
(()=>{
window.__yxiPickCancel?.();
const overlay=document.createElement('div');Object.assign(overlay.style,{position:'fixed',pointerEvents:'none',zIndex:'2147483647',border:'2px solid #2563eb',background:'rgba(37,99,235,.05)',boxSizing:'border-box'});document.documentElement.appendChild(overlay);
let selectedNode=null;
const paint=(node)=>{const r=node.getBoundingClientRect();Object.assign(overlay.style,{left:r.left+'px',top:r.top+'px',width:r.width+'px',height:r.height+'px'});};
const refresh=()=>{if(selectedNode?.isConnected)paint(selectedNode);else overlay.remove();};
const hover=(e)=>{if(e.target instanceof Element)paint(e.target);};
window.__yxiPickCancel=(keep=false)=>{document.removeEventListener('click',window.__yxiPick,true);document.removeEventListener('mousemove',hover,true);if(!keep){window.removeEventListener('resize',refresh);document.removeEventListener('scroll',refresh,true);overlay.remove();}else{window.addEventListener('resize',refresh);document.addEventListener('scroll',refresh,true);requestAnimationFrame(refresh);}};
document.addEventListener('mousemove',hover,true);
window.__yxiPick=(event)=>{
event.preventDefault();event.stopImmediatePropagation();
const el=event.target;if(!(el instanceof Element))return;
selectedNode=el;
const clone=el.cloneNode(true);clone.querySelectorAll('input,textarea,select,script,style,[contenteditable],[data-private],[hidden]').forEach(x=>x.remove());
const sensitive=!!el.closest('input,textarea,select,[contenteditable],[data-private]');
let n=el,parts=[];while(n&&n!==document.documentElement&&parts.length<6){
 if(n.id){parts.unshift('#'+CSS.escape(n.id));break;}
 let index=1,s=n;while((s=s.previousElementSibling))if(s.tagName===n.tagName)index++;
 parts.unshift(n.tagName.toLowerCase()+':nth-of-type('+index+')');n=n.parentElement;
}
window.cefQuery({request:JSON.stringify({type:'selection',selector:parts.join(' > '),text:sensitive?'':(clone.textContent||'').trim().slice(0,4000),tag:el.tagName.toLowerCase()})});
window.__yxiPickCancel(true);window.__yxiPick=null;
};document.addEventListener('click',window.__yxiPick,true);
})();
""".trimIndent()
    }
}
