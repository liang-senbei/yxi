package app.yxi

import app.yxi.agent.MdChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * markdown 分块器。它的用途是把网址预览卡摆到「它所属那一段」下面，
 * 所以**切错的代价是正文被渲染坏**（编号重来、代码块残缺），比卡片位置本身严重得多 —— 这几条盯的就是那个。
 */
class MdChunksTest {

    @Test fun 普通段落_按空行切() {
        val md = "第一段。\n\n第二段 https://a.com 在这儿。\n\n第三段。"
        val c = MdChunks.split(md)
        assertEquals(3, c.size)
        assertTrue(c[1].contains("https://a.com"))
        assertTrue(c[0].contains("第一段") && !c[0].contains("第二段"))
    }

    /** 最容易被忽略的一种坏法：松散列表被切开，有序编号从 1 重来（第 3 条变成新列表第 1 条） */
    @Test fun 松散有序列表_不切开() {
        val md = "开头\n\n1. 一\n\n2. 二 https://a.com\n\n3. 三\n\n结尾"
        val c = MdChunks.split(md)
        val list = c.first { it.contains("1.") }
        assertTrue("列表被切开了：$c", list.contains("2.") && list.contains("3."))
        assertEquals(3, c.size)                       // 开头 / 整个列表 / 结尾
    }

    @Test fun 无序列表_也不切开() {
        val md = "- 甲\n\n- 乙\n\n- 丙"
        assertEquals(1, MdChunks.split(md).size)
    }

    /** 列表项的第二段是缩进的续行，属于同一个列表 */
    @Test fun 列表里的缩进续行_跟着列表走() {
        val md = "1. 一\n\n   补充说明\n\n2. 二"
        assertEquals(1, MdChunks.split(md).size)
    }

    /** 代码块里的空行是代码的一部分，切开就成了两个残缺的围栏 */
    @Test fun 围栏代码块_里面的空行不切() {
        val md = "前言\n\n```bash\necho a\n\necho b\n```\n\n后记"
        val c = MdChunks.split(md)
        assertEquals(3, c.size)
        val code = c[1]
        assertEquals(2, Regex("```").findAll(code).count())   // 围栏成对
        assertTrue(code.contains("echo a") && code.contains("echo b"))
    }

    @Test fun 波浪线围栏_同样处理() {
        val md = "~~~\nx\n\ny\n~~~"
        assertEquals(1, MdChunks.split(md).size)
    }

    @Test fun 表格不受影响() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |\n\n下面"
        val c = MdChunks.split(md)
        assertEquals(2, c.size)
        assertTrue(c[0].contains("|---|"))
    }

    @Test fun 边角_空输入与无空行() {
        assertTrue(MdChunks.split("").isEmpty())
        assertTrue(MdChunks.split("   \n  \n").isEmpty())
        assertEquals(listOf("就一行"), MdChunks.split("就一行"))
        assertEquals(1, MdChunks.split("连着两行\n没有空行").size)
    }

    /** 连着好几个空行不该切出空块来 */
    @Test fun 多个空行_不产生空块() {
        val c = MdChunks.split("甲\n\n\n\n乙")
        assertEquals(listOf("甲", "乙"), c)
    }

    /** 引用式链接：定义在最底下，用法在上面。切开后含用法的那块必须还带着定义，否则渲染成纯文本 */
    @Test fun 引用式链接定义_补进每一块() {
        val md = "见[规约][1] 那条。\n\n还有[音源][2]。\n\n[1]: https://a.com\n[2]: https://b.com"
        val c = MdChunks.split(md)
        assertTrue("第一块丢了定义：${c[0]}", c[0].contains("[1]: https://a.com"))
        assertTrue("第二块丢了定义：${c[1]}", c[1].contains("[2]: https://b.com"))
        // 纯定义块不再重复追加自己
        val defOnly = c.last()
        assertEquals(1, Regex(Regex.escape("[1]: https://a.com")).findAll(defOnly).count())
    }

    @Test fun 没有引用式定义_不加尾巴() {
        val c = MdChunks.split("甲\n\n乙")
        assertEquals(listOf("甲", "乙"), c)
    }

    /** CRLF：\r 会让空行不算空行 → 一个块都切不出来，功能静默失效 */
    @Test fun CRLF_照样切得开() {
        assertEquals(2, MdChunks.split("甲\r\n\r\n乙").size)
    }

    /** 拼回去内容不丢：每一行正文都还在（顺序也不变） */
    @Test fun 切完不丢内容() {
        val md = "标题\n\n1. 一\n\n2. 二\n\n```\ncode\n\nmore\n```\n\n结尾 https://x.com"
        val joined = MdChunks.split(md).joinToString("\n")
        listOf("标题", "1. 一", "2. 二", "code", "more", "结尾 https://x.com").forEach {
            assertTrue("丢了：$it", joined.contains(it))
        }
    }
}
