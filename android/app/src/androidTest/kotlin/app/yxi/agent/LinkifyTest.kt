package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径变链接。
 *
 * ⚠️ **认错比漏认严重得多** —— 漏认只是点不动，认错是把一段正文变成假链接、
 * 点了还跳走，而且会在屏幕上留下一串 markdown 语法。下面一半的用例是防误伤的。
 */
class LinkifyTest {

    private fun on(s: String) = Linkify.apply(s)
    private fun linked(s: String) = "[" + s + "](" + Linkify.SCHEME + s + ")"

    @Test fun 绝对路径变成链接() {
        assertEquals("看 " + linked("/root/src/a.md"), on("看 /root/src/a.md"))
    }

    @Test fun 波浪号路径一段就够() {
        assertEquals(linked("~/.yxi"), on("~/.yxi"))
    }

    @Test fun 只有一段的斜杠不算路径() {
        // ⚠️ 真事：用户的对话里有「整个 /22 段注册给一家代理商」
        assertEquals("整个 /22 段注册给一家代理商", on("整个 /22 段注册给一家代理商"))
        assertEquals("放在 /tmp 下", on("放在 /tmp 下"))
    }

    @Test fun 不碰网址() {
        val u = "见 https://claude.ai/code/artifact 这一页"
        assertEquals(u, on(u))
    }

    @Test fun 不碰_and_or_这种() {
        assertEquals("读/写 都行，and/or 随便", on("读/写 都行，and/or 随便"))
    }

    @Test fun 围栏代码块里一个字都不动() {
        val src = "前面 /root/a/b\n```\ncd /root/a/b\n```\n后面 /root/c/d"
        val out = on(src)
        assertTrue("围栏外的要变", out.startsWith("前面 " + linked("/root/a/b")))
        assertTrue("围栏内必须原样", out.contains("\ncd /root/a/b\n"))
        assertTrue("围栏后的要变", out.endsWith("后面 " + linked("/root/c/d")))
    }

    @Test fun 行内代码整段是路径时连反引号一起包进去() {
        // ⚠️ 不能只换反引号里面 —— 代码段里的 []() 不会被解析，
        // 屏幕上会原样冒出一串 markdown 语法
        val out = on("改 `/root/src/a.md` 这个文件")
        assertEquals("改 [`/root/src/a.md`](" + Linkify.SCHEME + "/root/src/a.md) 这个文件", out)
        assertFalse("反引号里面不能被塞进链接语法", out.contains("`[/"))
    }

    @Test fun 行内代码不是路径就别动() {
        assertEquals("变量 `foo_bar` 是它", on("变量 `foo_bar` 是它"))
    }

    @Test fun 已经是链接的不套两层() {
        val s = "[说明](/root/src/a.md)"
        assertEquals(s, on(s))
    }

    @Test fun 句末的点还回去() {
        assertEquals("见 " + linked("/root/src/a") + ".", on("见 /root/src/a."))
        // 但扩展名里的点是路径的一部分，不能剥
        assertEquals("见 " + linked("/root/src/a.md"), on("见 /root/src/a.md"))
    }

    @Test fun 一行里多条都要认() {
        assertEquals(
            linked("/a/b") + " 和 " + linked("/c/d"),
            on("/a/b 和 /c/d"),
        )
    }

    @Test fun 中文文件名和中文目录都要认全() {
        // ⚠️ 用户机器上真有这些目录，只认 ASCII 的话会在中文那一段被切断，
        // 点开的是上一级目录 —— 看起来像「跳错了」
        assertEquals("在 " + linked("/opt/workspace/诗歌"), on("在 /opt/workspace/诗歌"))
        assertEquals(linked("/a/b/跳转样例.md"), on("/a/b/跳转样例.md"))
        assertEquals("在 " + linked("/opt/workspace/文件传") + " 里", on("在 /opt/workspace/文件传 里"))
    }

    @Test fun 中文句号不能被吃进路径() {
        // ⚠️ 中文不写空格，标点一旦被当成路径字符，整句话都会变成假链接
        assertEquals("文件在 " + linked("/a/b/c.md") + "。剩下这句要留着。",
            on("文件在 /a/b/c.md。剩下这句要留着。"))
        assertEquals("放在 " + linked("/a/b") + "，然后呢", on("放在 /a/b，然后呢"))
    }

    @Test fun 真实的一条回复() {
        // ⚠️ 从用户 taobao 会话里原样抠出来的。四个坑一次踩齐：
        // 行内代码 + 中文文件名 + 末尾斜杠 + 紧跟着中文全角括号
        val src = "文件已落到服务器：`/root/src/workspace/taobao/淘宝抓取-Claude魔法师/`（1.7 MB）。"
        val out = on(src)
        val p = "/root/src/workspace/taobao/淘宝抓取-Claude魔法师/"
        assertEquals(
            "文件已落到服务器：[`" + p + "`](" + Linkify.SCHEME + p + ")（1.7 MB）。",
            out,
        )
    }

    @Test fun 目录的末尾斜杠要留着() {
        assertEquals(linked("/a/b/"), on("/a/b/"))
    }

    @Test fun 空字符串和纯文本不出事() {
        assertEquals("", on(""))
        assertEquals("就是一句普通的话", on("就是一句普通的话"))
    }
}
