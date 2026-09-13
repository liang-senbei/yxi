package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.swing.Swing
import org.cef.browser.CefBrowser
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class PreviewViewport(val id: String, val label: String, val width: Int, val height: Int)
internal val previewViewports = listOf(
    PreviewViewport("fit", "自适应", 0, 0),
    PreviewViewport("desktop", "桌面", 1440, 900),
    PreviewViewport("tablet", "平板", 768, 1024),
    PreviewViewport("phone", "手机", 390, 844),
)

internal suspend fun applyPreviewViewport(browser: CefBrowser, viewport: PreviewViewport): String {
    val client = withContext(Dispatchers.Swing) { browser.devToolsClient ?: error("浏览器尚未就绪") }
    try {
        var scale = 1.0
        val future = withContext(Dispatchers.Swing) {
            if (viewport.width == 0) client.executeDevToolsMethod("Emulation.clearDeviceMetricsOverride", "{}")
            else {
                val width = browser.uiComponent.width.coerceAtLeast(1)
                val height = browser.uiComponent.height.coerceAtLeast(1)
                scale = minOf(1.0, width.toDouble() / viewport.width, height.toDouble() / viewport.height)
                val parameters = JSONObject().put("width", viewport.width).put("height", viewport.height)
                    .put("deviceScaleFactor", 1).put("mobile", false).put("scale", scale)
                client.executeDevToolsMethod("Emulation.setDeviceMetricsOverride", parameters.toString())
            }
        }
        val result = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
        check(!JSONObject(result).has("error")) { "浏览器未接受视口设置" }
        return if (viewport.width == 0) "视口自适应" else "${viewport.width} × ${viewport.height} · 显示 ${(scale * 100).toInt()}%"
    } finally { client.close() }
}
