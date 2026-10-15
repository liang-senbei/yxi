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
}
