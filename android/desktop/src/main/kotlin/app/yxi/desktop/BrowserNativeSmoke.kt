package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.cef.browser.CefBrowser
import java.net.InetSocketAddress
import javax.swing.JFrame

/** Explicit test entry point. A disposable loopback page exercises the bundled
 * Chromium binaries without connecting to configured hosts or logging in. */
internal fun runBrowserNativeSmoke() = runBlocking {
    val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    http.createContext("/") { exchange ->
        val page = "<html><meta charset='utf-8'><body style='font-family:sans-serif;padding:32px'><h1 id='title'>Yxi Windows 网页预览</h1><p id='probe'>NATIVE_RENDER_READY</p></body></html>".toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, page.size.toLong())
        exchange.responseBody.use { it.write(page) }
    }
    http.start()
    var frame: JFrame? = null
    var browser: CefBrowser? = null
    val loaded = CompletableDeferred<Unit>()
    suspend fun renderedText(): String {
        val answer = CompletableDeferred<String>()
        withContext(Dispatchers.Swing) { browser!!.getText { answer.complete(it) } }
        return withTimeout(5000) { answer.await() }
    }
    try {
        val cef = withContext(Dispatchers.IO) { BrowserRuntime.get() }
        withContext(Dispatchers.Swing) {
            val (client, context) = BrowserRuntime.newClient(cef)
            client.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                    if (!isLoading && browser?.url?.startsWith("http://127.0.0.1:${http.address.port}/") == true) loaded.complete(Unit)
                }
            })
            browser = client.createBrowser("http://127.0.0.1:${http.address.port}/", false, false, context)
            frame = JFrame("Yxi isolated browser smoke").apply { add(browser!!.uiComponent); setSize(800, 600); setLocationRelativeTo(null); isVisible = true }
        }
        withTimeout(30000) { loaded.await() }
        println("browser native page loaded")
        withTimeout(30000) { while (!renderedText().contains("NATIVE_RENDER_READY")) delay(150) }
        withContext(Dispatchers.Swing) {
            browser!!.executeJavaScript("document.getElementById('title').style.fontSize='40px';document.getElementById('probe').textContent='LIVE_STYLE='+getComputedStyle(document.getElementById('title')).fontSize;", browser!!.url, 0)
        }
        withTimeout(10000) { while (!renderedText().contains("LIVE_STYLE=40px")) delay(150) }
        delay(500)
        System.getProperty("yxi.browser.smokeImage")?.let { path ->
            // Desktop capture can be black in a disconnected Windows session.
            // Capture Chromium's composed surface rather than claiming that
            // reading DOM text proves pixels were rendered.
                val screenshot = BrowserCapture.capture(browser!!)
                val pixels = screenshot.pixels
                check(pixels.width >= 100 && pixels.height >= 100)
                val base = pixels.getRGB(0, 0)
                check((0 until pixels.height step 3).any { y -> (0 until pixels.width step 3).any { x -> pixels.getRGB(x, y) != base } }) { "Chromium screenshot is blank" }
                java.io.File(path).writeBytes(screenshot.png)
                println("browser native pixels ok")
        }
        println("browser native render and live style ok")
    } finally {
        try {
            // macOS：10-15 的 macOS CI 冒烟里页面、像素、样式都过了，只有关运行时卡住（CEF 停在 INITIALIZED、客户端一直没回收完）。
            // 原因（对着 jcef-api 146.0.10 的字节码核过）：客户端没有生命周期处理器时，CefClient.doClose 交给 CefBrowser_N.doClose，它在没调过
            // setCloseAllowed() 时一律取消关闭、只给父窗口发 WINDOW_CLOSING——close(true) 也一样，所以这个浏览器永远关不掉。真 App 的预览客户端
            // 挂了生命周期处理器（doClose 默认返回 false = 允许关），不走这条。Mac 上照 JCEF 示例先 setCloseAllowed() 再关，并拆掉窗口；
            // Windows 照旧（它是发版的门槛，不跟着试）。关运行时的耗时和卡住时的状态都打出来。
            val mac = System.getProperty("os.name").startsWith("Mac")
            withContext(Dispatchers.Swing) {
                if (mac) browser?.setCloseAllowed()
                browser?.close(true)
                if (mac) frame?.let { it.contentPane.removeAll(); it.dispose() }
            }
            val started = System.nanoTime()
            val finished = BrowserRuntime.shutdown()
            println("browser native shutdown ${if (finished) "finished" else "timed out"} after ${(System.nanoTime() - started) / 1_000_000} ms (${BrowserRuntime.describe()})")
            check(finished) { "Native browser shutdown did not finish (${BrowserRuntime.describe()})" }
        } finally {
            withContext(Dispatchers.Swing) { frame?.dispose() }
            http.stop(0)
        }
    }
    println("browser native shutdown ok")
}
