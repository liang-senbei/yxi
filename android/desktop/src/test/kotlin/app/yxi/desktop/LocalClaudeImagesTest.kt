package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.*

class LocalClaudeImagesTest {
    @TempDir lateinit var root: File
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
