package app.yxi

import app.yxi.agent.Dirs
import org.junit.Assert.*
import org.junit.Test

class DirsTest {

    @Test fun 从cwd反推父目录() {
        assertEquals(
            listOf("/root/src/workspace"),
            Dirs.parentsOf(listOf("/root/src/workspace/Yxi", "/root/src/workspace/mail/")),
        )
    }

    /** ⚠️ 这条是这个功能存在的理由：**已经开着会话的目录不能再出现在候选里** */
    @Test fun 已经开着会话的目录要剔掉() {
        val out = """
            /root/src/workspace/Yxi
            /root/src/workspace/mail
            /root/src/workspace/新项目
        """.trimIndent()
        val got = Dirs.candidates(out, taken = listOf("/root/src/workspace/Yxi", "/root/src/workspace/mail"))
        assertEquals(listOf("/root/src/workspace/新项目"), got)
    }

    /** 末尾斜杠不该让同一个目录被当成两个 */
    @Test fun 末尾斜杠不影响比对() {
        val got = Dirs.candidates("/a/b/\n/a/c", taken = listOf("/a/b"))
        assertEquals(listOf("/a/c"), got)
    }

    /** `.git` / `.cache` 不是项目 */
    @Test fun 隐藏目录不列() {
        val got = Dirs.candidates("/a/.git\n/a/proj", taken = emptyList())
        assertEquals(listOf("/a/proj"), got)
    }

    @Test fun 命令只列一层且带引号转义() {
        val c = Dirs.listCommand(listOf("/a/it's"))!!
        assertTrue(c, c.contains("-maxdepth 1"))
        assertTrue(c, c.contains("""'/a/it'\''s'"""))
    }

    @Test fun 没有父目录就没有命令() {
        assertNull(Dirs.listCommand(emptyList()))
        assertNull(Dirs.listCommand(listOf("相对路径")))
    }
}
