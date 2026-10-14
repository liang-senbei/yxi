package app.yxi.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** 1×1 的真图片字节，过得了 ClaudeImageInput 的尺寸检查。 */
internal fun testImage(format: String = "png"): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), format, it) }.toByteArray()

class LocalCodexImagesTest {
    @TempDir lateinit var directory: File
    private val images by lazy { LocalCodexImages(File(directory, "codex-images")) }

    @Test fun `capture stores a content addressed copy that later source edits do not change`() {
        val bytes = testImage()
        val source = File(directory, "shot.png").apply { writeBytes(bytes) }
        val reference = images.capture(source)
        assertEquals("shot.png", reference.name)
        assertEquals("/yxi-local-codex-image/" + contentHash(bytes) + ".png", reference.remotePath)
        source.writeBytes(testImage("jpg"))
        val stored = File(images.resolve(reference))
        assertTrue(stored.isAbsolute)
        assertEquals(File(directory, "codex-images").canonicalPath, stored.parentFile.canonicalPath)
        assertContentEquals(bytes, stored.readBytes())
        assertEquals(reference, images.capture("shot.png", bytes))
        assertTrue(images.capture("photo.jpg", testImage("jpg")).remotePath.endsWith(".jpg"))
    }

    @Test fun `capture rejects gif unknown content and invalid names`() {
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        assertEquals("仅支持PNG、JPEG或WebP图片", assertFailsWith<IllegalStateException> { images.capture("a.gif", gif) }.message)
        assertFailsWith<IllegalStateException> { images.capture("a.png", byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { images.capture(" ", testImage()) }
        assertFailsWith<IllegalArgumentException> { images.capture("a\u0000.png", testImage()) }
        assertFalse(File(directory, "codex-images").exists())
    }

    @Test fun `resolve refuses foreign references and tampered or missing snapshots and capture heals them`() {
        val bytes = testImage()
        assertFailsWith<IllegalArgumentException> { images.resolve(InstructionAttachment("x", File(directory, "shot.png").absolutePath)) }
        assertFailsWith<IllegalArgumentException> { images.resolve(InstructionAttachment("x", "/yxi-local-codex-image/../shot.png")) }
        assertFailsWith<IllegalArgumentException> { images.resolve(InstructionAttachment("x", "/yxi-local-codex-image/" + contentHash(bytes) + ".gif")) }
        val reference = images.capture("shot.png", bytes)
        val stored = File(images.resolve(reference))
        stored.writeBytes(testImage("jpg"))
        assertEquals("图片快照内容不匹配，请重新添加图片", assertFailsWith<IllegalStateException> { images.resolve(reference) }.message)
        images.capture("shot.png", bytes)
        assertContentEquals(bytes, File(images.resolve(reference)).readBytes())
        stored.delete()
        assertFailsWith<IllegalStateException> { images.resolve(reference) }
    }

    /** 像素不同的 2×2 图（同 LocalClaudeImagesTest），每张的快照哈希都不同。 */
    private fun distinctImage(index: Int): ByteArray {
        val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, index) }
        return ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
    }

    @Test fun `draft merge deduplicates by snapshot caps at four and rejects foreign or missing snapshots`() {
        val captured = (1..5).map { images.capture("$it.png", distinctImage(it)) }
        val draft = captured.take(4)
        assertEquals(draft, images.mergeDraft(draft, listOf(draft.first().copy(name = "renamed.png"))))
        assertEquals("每次最多4张图片", assertFailsWith<IllegalArgumentException> { images.mergeDraft(draft, listOf(captured.last())) }.message)
        assertFailsWith<IllegalArgumentException> { images.mergeDraft(emptyList(), listOf(InstructionAttachment("x", "/yxi-local-image/other"))) }
        File(images.resolve(captured.last())).delete()
        assertFailsWith<IllegalStateException> { images.mergeDraft(draft.take(1), listOf(captured.last())) }
    }

    @Test fun `draft commit adds only validated new references and does not restore ones removed meanwhile`() {
        val (removed, retained, added) = (1..3).map { images.capture("$it.png", distinctImage(it)) }
        val before = listOf(removed, retained)
        val current = mutableListOf(retained)
        images.commitDraft(current, before, before + added)
        assertEquals(listOf(retained, added), current)
        images.commitDraft(current, before, before + added)
        assertEquals(listOf(retained, added), current)
    }

    @Test fun `preview reads the stored snapshot and is keyed by the reference`() {
        val bytes = distinctImage(7)
        val source = File(directory, "preview.png").apply { writeBytes(bytes) }
        val reference = images.capture(source)
        source.writeBytes(testImage("jpg"))
        val preview = images.preview(reference)
        assertTrue(preview.isImage)
        assertEquals("preview.png", preview.name)
        assertEquals(reference.remotePath, preview.stamp)
        assertEquals(bytes.size.toLong(), preview.size)
        repeat(2) { preview.open().use { assertContentEquals(bytes, it.readBytes()) } }
        File(images.resolve(reference)).delete()
        assertFailsWith<IllegalStateException> { images.preview(reference) }
    }
}
