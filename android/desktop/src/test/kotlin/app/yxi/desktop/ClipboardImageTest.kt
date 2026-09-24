package app.yxi.desktop

import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import kotlin.test.*

class ClipboardImageTest {
    @Test fun `clipboard conversion accepts toolkit images and detaches mutable pixels`() {
        val source = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(0, 0, Color.BLUE.rgb)
        val copy = Attach.bufferClipboardImage(source)
        source.setRGB(0, 0, Color.RED.rgb)
        assertEquals(Color.BLUE.rgb, copy.getRGB(0, 0))
        val toolkitImage = source.getScaledInstance(4, 3, Image.SCALE_FAST)
        try {
            assertFalse(toolkitImage is BufferedImage)
            val converted = Attach.bufferClipboardImage(toolkitImage)
            assertEquals(4, converted.width); assertEquals(3, converted.height)
        } finally { toolkitImage.flush() }
    }
}
