package app.yxi.desktop

import java.awt.image.BufferedImage
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.json.JSONObject
import kotlin.test.*

class BrowserCaptureTest {
    private fun png(): ByteArray {
        val image = BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(2, 3, 0xff123456.toInt())
        return ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()
    }
    private fun response(bytes: ByteArray) = JSONObject().put("data", Base64.getEncoder().encodeToString(bytes)).toString()
    @Test fun `clipboard provides image pixels without a hidden text or URL payload`() {
        val capture = BrowserCapture.decode(response(png()))
        val clipboard = Clipboard("test-only")
        capture.copyTo(clipboard)
        val image = clipboard.getData(DataFlavor.imageFlavor) as BufferedImage
        assertEquals(12, image.width); assertEquals(8, image.height)
        assertEquals(0xff123456.toInt(), image.getRGB(2, 3))
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
    }
    @Test fun `malformed image and oversized raster are rejected before decoding pixels`() {
        assertFails { BrowserCapture.decode(response(byteArrayOf(1, 2, 3))) }
        val large = png()
        ByteBuffer.wrap(large).putInt(16, 10000).putInt(20, 10000)
        val crc = CRC32().apply { update(large, 12, 17) }
        ByteBuffer.wrap(large).putInt(29, crc.value.toInt())
        assertTrue(assertFails { BrowserCapture.decode(response(large)) }.message!!.contains("分辨率"))
    }
    @Test fun `wrapped devtools response is supported`() {
        val nested = JSONObject().put("result", JSONObject(response(png())))
        assertEquals(12, BrowserCapture.decode(nested.toString()).pixels.width)
    }
}
