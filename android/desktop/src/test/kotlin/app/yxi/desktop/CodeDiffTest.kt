package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeDiffTest {
    private val sample = """
        diff --git a/src/a.ts b/src/a.ts
        index 3f2a9c1..8b7d4e0 100644
        --- a/src/a.ts
        +++ b/src/a.ts
        @@ -12,4 +12,5 @@ export function f() {
           switch (code) {
        -    default: return 1
        +    case 2: return 2
        +    default: return 1
           }
        \ No newline at end of file
    """.trimIndent() + "\n"

    @Test fun `file headers are meta and hunk headers set both line counters`() {
        val lines = CodeDiff.parse(sample)
        assertEquals(List(4) { DiffLine.Kind.Meta }, lines.take(4).map { it.kind })
        assertEquals(DiffLine.Kind.Hunk, lines[4].kind)
        assertEquals(DiffLine(DiffLine.Kind.Context, 12, 12, "  switch (code) {"), lines[5])
        assertEquals(DiffLine(DiffLine.Kind.Del, 13, null, "    default: return 1"), lines[6])
        assertEquals(DiffLine(DiffLine.Kind.Add, null, 13, "    case 2: return 2"), lines[7])
        assertEquals(DiffLine(DiffLine.Kind.Add, null, 14, "    default: return 1"), lines[8])
        assertEquals(DiffLine(DiffLine.Kind.Context, 14, 15, "  }"), lines[9])
        assertEquals(DiffLine.Kind.Note, lines[10].kind)
        assertEquals(11, lines.size)   // 末尾的换行不多出一行空的
    }

    @Test fun `a second file starts over as meta until its own hunk`() {
        val two = sample + "diff --git a/b.txt b/b.txt\n--- a/b.txt\n+++ b/b.txt\n@@ -1 +1 @@\n-x\n+y\n"
        val lines = CodeDiff.parse(two)
        val second = lines.drop(11)
        assertEquals(listOf(DiffLine.Kind.Meta, DiffLine.Kind.Meta, DiffLine.Kind.Meta, DiffLine.Kind.Hunk, DiffLine.Kind.Del, DiffLine.Kind.Add), second.map { it.kind })
        assertEquals(1, second[4].old); assertEquals(1, second[5].new)
        assertEquals(3, CodeDiff.parse(two, max = 3).size)
    }

    @Test fun `combined diffs of conflicted files keep their lines without line numbers`() {
        val cc = "diff --cc a.txt\nindex 1,2..3\n--- a/a.txt\n+++ b/a.txt\n@@@ -1,2 -1,2 +1,3 @@@\n  same\n+ ours\n -theirs\n++both\n"
        val lines = CodeDiff.parse(cc).filter { it.kind != DiffLine.Kind.Meta }
        assertEquals(listOf(DiffLine.Kind.Hunk, DiffLine.Kind.Context, DiffLine.Kind.Add, DiffLine.Kind.Del, DiffLine.Kind.Add), lines.map { it.kind })
        assertEquals(listOf("same", "ours", "theirs", "both"), lines.drop(1).map { it.text })
        assertEquals(null, lines[2].new)
    }

    @Test fun `long diffs are flagged as clipped at the parse cap`() {
        val long = (1..CodeDiff.MAX_LINES + 5).joinToString("\n") { "+x" }
        assertEquals(true, CodeDiff.clipped(long))
        assertEquals(false, CodeDiff.clipped(sample))
    }
}
