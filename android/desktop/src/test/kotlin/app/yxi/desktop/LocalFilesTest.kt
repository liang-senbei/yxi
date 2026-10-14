package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** 本机对齐 P6b：LocalFiles 只读地列目录、读文件开头、拼面包屑；只碰临时目录。 */
class LocalFilesTest {
    @TempDir lateinit var root: File

    @Test fun `directories come first, then names, and links follow their target`() {
        File(root, "b.txt").writeText("bb"); File(root, "A.md").writeText("a"); File(root, "zdir").mkdirs(); File(root, "adir").mkdirs()
        val windows = System.getProperty("os.name").startsWith("Windows")
        if (!windows) Files.createSymbolicLink(File(root, "link").toPath(), File(root, "zdir").toPath())
        val entries = LocalFiles.list(root)
        assertEquals(if (windows) listOf("adir", "zdir", "A.md", "b.txt") else listOf("adir", "link", "zdir", "A.md", "b.txt"), entries.map { it.name })
        assertEquals(2L, entries.single { it.name == "b.txt" }.size)
        assertTrue(entries.single { it.name == "zdir" }.isDir)
        if (!windows) entries.single { it.name == "link" }.let { assertTrue(it.isDir && it.isLink) }
    }

    @Test fun `reading stops at the limit and refuses directories`() {
        val big = File(root, "big.log").apply { writeBytes(ByteArray(5000) { 'x'.code.toByte() }) }
        assertEquals(1024, LocalFiles.read(big, 1024).size)
        assertEquals(5000, LocalFiles.read(big, 1 shl 20).size)
        assertFailsWith<IllegalArgumentException> { LocalFiles.read(root, 10) }
    }

    @Test fun `missing directories explain themselves and crumbs start at the root`() {
        assertTrue(assertFailsWith<IllegalStateException> { LocalFiles.list(File(root, "nope")) }.message!!.startsWith("目录不存在"))
        val nested = File(root, "a/b").apply { mkdirs() }
        val crumbs = LocalFiles.crumbs(nested)
        assertEquals(nested.absolutePath, crumbs.last().second)
        assertEquals(listOf("a", "b"), crumbs.takeLast(2).map { it.first })
        assertNull(File(crumbs.first().second).parentFile, "第一级是根")
    }
}
