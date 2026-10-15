package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffCommentTest {
    @Test fun `comments are quoted with their origin, sorted by file and line, removed lines named as such`() {
        val q = DiffComment.quote("本机", "/repo", "fix/a", "8b7d4e0", listOf(
            DiffComment("src/b.ts", false, null, 14, "case 403: x", "403 要单独提示"),
            DiffComment("src/a.ts", false, 13, null, "default: y", "  这行别删  "),
            DiffComment("src/b.ts", false, 12, 12, "switch (code) {", "这里加注释"),
        ))
        assertTrue(q.startsWith("Git 差异逐行评论\n本机\n仓库：/repo\n分支：fix/a\n提交：8b7d4e0"))
        val order = listOf("src/a.ts 删除的第 13 行", "src/b.ts 第 12 行", "src/b.ts 第 14 行").map { q.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), q)
        assertTrue("> default: y\n这行别删" in q, q)
    }

    @Test fun `the same line in the same scope has one key`() {
        val line = DiffLine(DiffLine.Kind.Add, null, 14, "x")
        assertEquals(DiffComment("f", true, null, 14, "x", "c").key, DiffComment.keyOf("f", true, line))
        assertTrue(DiffComment.keyOf("f", false, line) != DiffComment.keyOf("f", true, line))
    }
}
