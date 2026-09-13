package app.yxi.desktop

import java.awt.image.BufferedImage
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cef.browser.CefBrowser
import org.json.JSONObject

internal data class BrowserCapture(val png: ByteArray, val pixels: BufferedImage) {
    fun copyTo(clipboard: Clipboard) {
        clipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                return pixels
            }
        }, null)
    }
    companion object {
        private val captureLock = Mutex()
        suspend fun capture(browser: CefBrowser): BrowserCapture = captureLock.withLock {
            val client = withContext(Dispatchers.Swing) { browser.devToolsClient ?: error("浏览器尚未就绪") }
            try {
                val future = withContext(Dispatchers.Swing) { client.executeDevToolsMethod("Page.captureScreenshot", "{\"format\":\"png\",\"fromSurface\":true,\"captureBeyondViewport\":false}") }
                val raw = withContext(Dispatchers.IO) { future.get(15, java.util.concurrent.TimeUnit.SECONDS) }
                withContext(Dispatchers.Default) { decode(raw) }
            } finally { client.close() }
        }
        internal fun decode(raw: String): BrowserCapture {
            require(raw.length <= 48 * 1024 * 1024) { "截图过大，请缩小预览窗口" }
            val response = JSONObject(raw)
            val encoded = (response.optJSONObject("result") ?: response).getString("data")
            val bytes = Base64.getDecoder().decode(encoded)
            javax.imageio.stream.MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                require(readers.hasNext()) { "浏览器未返回有效图片" }
                val reader = readers.next()
                try {
                    reader.input = input
                    require(reader.formatName.equals("png", true)) { "浏览器未返回PNG截图" }
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    require(width > 0 && height > 0 && width.toLong() * height <= 40_000_000) { "截图分辨率过大，请缩小预览窗口" }
                    return BrowserCapture(bytes, reader.read(0))
                } finally { reader.dispose() }
            }
        }
    }
}
