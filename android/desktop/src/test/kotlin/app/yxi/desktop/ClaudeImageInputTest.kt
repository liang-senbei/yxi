package app.yxi.desktop

import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

class ClaudeImageInputTest {
    @Test fun `normal image bytes remain unchanged and signature-only files are refused`() {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(12, 9, BufferedImage.TYPE_INT_RGB), "png", out)
        val bytes = out.toByteArray()
        val input = ClaudeImageInput.fromBytes(bytes)
        assertEquals("image/png", input.mediaType)
        assertContentEquals(bytes, java.util.Base64.getDecoder().decode(input.block().getJSONObject("source").getString("data")))
        assertFails { ClaudeImageInput.fromBytes(bytes.copyOf(8)) }
        assertFailsWith<IllegalArgumentException> { ClaudeImageInput.fromBytes(ByteArray(5 * 1024 * 1024 + 1)) }
        // PNG IHDR width/height are read before allocation; no huge bitmap is needed for this fixture.
        val oversized = bytes.copyOf()
        java.nio.ByteBuffer.wrap(oversized).putInt(16, 100_000).putInt(20, 100_000)
        assertFailsWith<IllegalArgumentException> { ClaudeImageInput.fromBytes(oversized) }
    }
}
