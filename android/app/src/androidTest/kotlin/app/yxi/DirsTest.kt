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

    /**
     * 用户报的：想在 `/root/src/workspace/logto` 开会话，最后开在了 `/root`。
     * 病根是 `tmux new-session -c <不存在的目录>` **返回 0** 然后跑去 `$HOME`。
     */
    @Test fun 新目录要先建出来() {
        val c = Dirs.createCommand("/root/src/workspace/logto", "cc-logto")
        assertTrue("没有 mkdir 的话，目录不存在时 tmux 会静悄悄开在 \$HOME", "mkdir -p" in c)
    }

    @Test fun 建完要核对真的落在那儿() {
        val c = Dirs.createCommand("/a/b", "cc-b")
        assertTrue("必须回头问 pane_current_path —— tmux 的退出码在这件事上会骗人",
            "pane_current_path" in c)
        assertTrue("对不上要把会话杀掉，不留一个位置错的会话骗人", "kill-session" in c)
        assertTrue("两边都要 pwd -P，否则软链会让比较误判", c.count { it == 'P' } >= 2)
    }

    @Test fun 路径里的单引号不会把命令劈开() {
        val c = Dirs.createCommand("/tmp/it's here", "cc-x")
        // 正确的转义是 '\'' 四个字符；写成 ''' 会把整条命令劈断
        assertTrue("单引号没转义对，命令会被劈开", """'\''""" in c)
        assertFalse("转义成了 '''，是错的", "'''" in c)
    }

    @Test fun 认不出的结果一律当失败() {
        assertEquals(Dirs.Made.Ok, Dirs.madeFrom("${Dirs.TAG}:ok"))
        assertEquals(Dirs.Made.Exists, Dirs.madeFrom("${Dirs.TAG}:exists"))
        // ⚠️ 空输出 = 失败，不能当成功。跳进一个没建成的会话，
        // 用户看到的是空白 + 「连不上」，比直接说「没开成」难查得多。
        assertTrue(Dirs.madeFrom("") is Dirs.Made.Failed)
        assertTrue(Dirs.madeFrom("bash: 什么鬼") is Dirs.Made.Failed)
        assertTrue(Dirs.madeFrom("${Dirs.TAG}:wrongdir:/root") is Dirs.Made.Failed)
        // 开错地方要把它跑去哪儿了说出来
        assertEquals("/root", (Dirs.madeFrom("${Dirs.TAG}:wrongdir:/root") as Dirs.Made.Failed).detail)
    }

    @Test fun 开会话_跑哪个agent() {
        assertTrue(Dirs.createCommand("/a", "cc-a").contains("'claude' Enter"))
        assertTrue(Dirs.createCommand("/a", "cx-a", "codex").contains("'codex' Enter"))
        // 只认两个名字：别的一律退回 claude —— 这行是要 send-keys 进 shell 的
        assertTrue(Dirs.createCommand("/a", "cc-a", "rm -rf /").contains("'claude' Enter"))
    }
}
