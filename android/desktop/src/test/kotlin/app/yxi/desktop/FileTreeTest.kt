package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileTreeTest {
    private val root = "/p"
    private val children = mapOf(
        "/p" to listOf(FileTreeRow("/p/src", "src", 0, true), FileTreeRow("/p/README.md", "README.md", 0, false)),
        "/p/src" to listOf(FileTreeRow("/p/src/index.ts", "index.ts", 1, false)),
    )

    @Test fun `collapsed directories hide their loaded children and expanded ones show them in place`() {
        assertEquals(listOf("src", "README.md"), FileTree.rows(root, children, emptySet(), "").map { it.name })
        assertEquals(listOf("src", "index.ts", "README.md"), FileTree.rows(root, children, setOf("/p/src"), "").map { it.name })
    }

    @Test fun `a filter lists every loaded match flat with its path relative to the root`() {
        val rows = FileTree.rows(root, children, emptySet(), "INDEX")
        assertEquals(listOf("src/index.ts"), rows.map { it.name })
        assertEquals(0, rows.single().depth)
    }

    @Test fun `text is split into lines and binary is refused`() {
        assertEquals(listOf("a", "b", ""), FileTree.lines("a\r\nb\n\n".toByteArray()))
        assertNull(FileTree.lines(byteArrayOf(1, 0, 2)))
        assertEquals(2, FileTree.lines("1\n2\n3\n".toByteArray(), max = 2)!!.size)
    }
}
