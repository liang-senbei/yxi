package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.*

class LocalClaudeImagesTest {
    @TempDir lateinit var root: File
    @Test fun `preview streams reopen independently and use the immutable stored image`() {
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(18, 11, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out)
        val original = out.toByteArray()
        val source = File(root, "preview.png").apply { writeBytes(original) }
        val store = LocalClaudeImages(File(root, "snapshots"))
        val saved = store.capture(source)
        source.writeText("source changed")
        val preview = store.preview(saved)
        assertTrue(preview.isImage)
        assertEquals(saved.remotePath, preview.stamp)
        assertEquals(original.size.toLong(), preview.size)
        repeat(2) { preview.open().use { assertContentEquals(original, it.readBytes()) } }
        preview.open().use { stream ->
            val image = javax.imageio.ImageIO.read(stream)
            assertEquals(18, image.width); assertEquals(11, image.height)
        }
    }
    @Test fun `snapshot survives source removal and queue reopen and rejects changed contents`() {
        val original = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5XcAAAAASUVORK5CYII=")
        val source = File(root, "image.png").apply { writeBytes(original) }
        val directory = File(root, "images")
        val images = LocalClaudeImages(directory)
        val saved = images.capture(source)
        assertEquals(saved, images.capture(source))
        val queueFile = File(root, "queue.json")
        InstructionQueue(queueFile).enqueue("local-claude:fixture", "", listOf(saved))
        assertTrue(source.delete())
        val restored = InstructionQueue(queueFile).entries.single().attachments.single()
        val block = LocalClaudeImages(directory).load(restored).block()
        assertContentEquals(original, Base64.getDecoder().decode(block.getJSONObject("source").getString("data")))
        directory.listFiles()!!.single { it.extension == "json" }.writeText("{\"data\":\"AAAA\"}")
        assertFailsWith<IllegalArgumentException> { images.load(restored) }
        assertEquals(InstructionStatus.Local, InstructionQueue(queueFile).entries.single().status)
        assertFailsWith<IllegalArgumentException> { images.load(saved.copy(remotePath = "/yxi-local-image/../../other")) }
    }
}
