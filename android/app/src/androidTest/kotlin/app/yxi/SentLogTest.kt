package app.yxi

import androidx.test.platform.app.InstrumentationRegistry
import app.yxi.ui.SentLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 「发过的话」按会话落盘、留三天（老板 2026-09-07）。
 *
 * ⚠️ 这份东西的价值全在**别丢、别串台**：翻历史找的就是转录已经够不到的那条。
 */
class SentLogTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val h = "host-test"

    @Before fun clean() {
        File(ctx.filesDir, "sent").deleteRecursively()
    }

    @Test fun 发一条记一条_新的在上() {
        SentLog.add(ctx, h, "s1", "第一句")
        SentLog.add(ctx, h, "s1", "第二句")
        val got = SentLog.read(ctx, h, "s1")
        assertEquals(2, got.size)
        assertEquals("第二句", got[0].text)   // 新的在上
        assertEquals("第一句", got[1].text)
        assertTrue("没记时间就没法说「留三天」", got[0].at > 0)
    }

    /** 会话之间不能串台 —— 老板的原话是「这个按会话来计算」 */
    @Test fun 不同会话各存各的() {
        SentLog.add(ctx, h, "s1", "给 s1 的")
        SentLog.add(ctx, h, "s2", "给 s2 的")
        assertEquals(listOf("给 s1 的"), SentLog.read(ctx, h, "s1").map { it.text })
        assertEquals(listOf("给 s2 的"), SentLog.read(ctx, h, "s2").map { it.text })
    }

    /** 同名会话在不同主机上是两回事 */
    @Test fun 不同主机的同名会话也分开() {
        SentLog.add(ctx, "hostA", "cc-yxi", "A 的")
        SentLog.add(ctx, "hostB", "cc-yxi", "B 的")
        assertEquals(listOf("A 的"), SentLog.read(ctx, "hostA", "cc-yxi").map { it.text })
        assertEquals(listOf("B 的"), SentLog.read(ctx, "hostB", "cc-yxi").map { it.text })
    }

    /**
     * ⚠️ 会话名里有 `/` 空格 emoji 都很正常 —— 直接当文件名会建不出来，或者写到别的目录去。
     */
    @Test fun 会话名带斜杠空格中文emoji也不出事() {
        val weird = "cc/yxi 主 会话 🚀"
        SentLog.add(ctx, h, weird, "怪名字也要存得住")
        assertEquals(listOf("怪名字也要存得住"), SentLog.read(ctx, h, weird).map { it.text })
    }

    /** 正文里带换行 / 引号 / 制表符 —— 一行一条 JSON 的意义就在这儿 */
    @Test fun 正文带换行引号也不串行() {
        val text = "第一行\n第二行\t带\"引号\"和 \\ 反斜杠"
        SentLog.add(ctx, h, "s1", text)
        SentLog.add(ctx, h, "s1", "后面这条")
        val got = SentLog.read(ctx, h, "s1")
        assertEquals(2, got.size)
        assertEquals(text, got[1].text)
    }

    /** 附件只记个数，正文里的附件标记要摘掉（不然填回输入框就是一串路径） */
    @Test fun 附件只记个数_正文摘干净() {
        // 真实送出去的样子：附件映射整行贴在正文前面（见 Attachments.header）
        val raw = "[图片1] /root/a.jpg\n[文档2] /root/b.pdf\n带图的一句"
        SentLog.add(ctx, h, "s1", raw)
        val one = SentLog.read(ctx, h, "s1").single()
        assertEquals("带图的一句", one.text)
        assertEquals(2, one.attachments)
        assertTrue("正文里还留着附件路径，填回输入框就是一串路径", !one.text.contains("/root/"))
    }

    /** 三天前的要清掉，三天内的要留下 —— 直接往文件里塞两条不同时间戳的 */
    @Test fun 超过三天的清掉_没到的留着() {
        SentLog.add(ctx, h, "s1", "刚发的")
        val f = File(ctx.filesDir, "sent").listFiles()!!.single()
        val now = System.currentTimeMillis()
        val old = now - 4L * 24 * 3600 * 1000      // 四天前
        val fresh = now - 2L * 24 * 3600 * 1000    // 两天前
        f.writeText(
            listOf(
                """{"t":$old,"a":0,"x":"四天前的"}""",
                """{"t":$fresh,"a":0,"x":"两天前的"}""",
            ).joinToString("\n"),
        )
        val got = SentLog.read(ctx, h, "s1").map { it.text }
        assertEquals(listOf("两天前的"), got)
        // 清理要落盘，不是只在内存里滤一遍
        assertTrue("过期的还留在文件里", !f.readText().contains("四天前的"))
    }

    /** 文件被写坏了不该把发送 / 面板带崩 */
    @Test fun 坏行跳过_不崩() {
        SentLog.add(ctx, h, "s1", "好的那条")
        val f = File(ctx.filesDir, "sent").listFiles()!!.single()
        f.writeText("这不是 JSON\n" + f.readText())
        assertEquals(listOf("好的那条"), SentLog.read(ctx, h, "s1").map { it.text })
    }

    /**
     * ⚠️ **追加，不是整份回写。** 第一版是「读全表 → 过滤 → writeText 回去」——
     * `writeText` 先清空再写，写到一半被杀（国产 ROM 后台杀得凶）整个会话的历史就没了；
     * 读一次失败更是把「只有新这条」写回去，瞬时错误变永久全丢。
     * 判据：文件里必须**保留旧行**，而且行数只增一。
     */
    @Test fun 记新的一条不动老的() {
        SentLog.add(ctx, h, "s1", "老的")
        val f = File(ctx.filesDir, "sent").listFiles()!!.single()
        val before = f.readText()
        SentLog.add(ctx, h, "s1", "新的")
        val after = f.readText()
        assertTrue("老的那条被整份覆盖没了", after.startsWith(before))
        assertEquals(2, after.trim().lines().size)
    }

    /** 每条结尾要有换行，否则下一条接在上一行屁股后面，两条一起解析失败 */
    @Test fun 每条都以换行收尾() {
        SentLog.add(ctx, h, "s1", "一")
        val f = File(ctx.filesDir, "sent").listFiles()!!.single()
        assertTrue("没换行结尾，下一条会粘上来", f.readText().endsWith("\n"))
        SentLog.add(ctx, h, "s1", "二")
        assertEquals(listOf("二", "一"), SentLog.read(ctx, h, "s1").map { it.text })
    }

    /**
     * ⚠️ 升级坑：**上一版是整份回写、末尾不带换行的**。装了新版之后第一次发话要是直接追加，
     * 新的一条就粘在老的最后一行屁股后面，两条一起解析失败 —— 老用户的历史当场少两条。
     */
    @Test fun 老格式没有末尾换行_追加不粘行() {
        val dir = File(ctx.filesDir, "sent").apply { mkdirs() }
        SentLog.add(ctx, h, "s1", "占位")            // 先让文件名生成出来
        val f = dir.listFiles()!!.single()
        val now = System.currentTimeMillis()
        // 复刻老版本写出来的样子：两行、**末尾没有换行**
        f.writeText("""{"t":$now,"a":0,"x":"老一"}""" + "\n" + """{"t":$now,"a":0,"x":"老二"}""")
        SentLog.add(ctx, h, "s1", "新的")
        assertEquals(listOf("新的", "老二", "老一"), SentLog.read(ctx, h, "s1").map { it.text })
    }

    /**
     * ⚠️ 「记三天」不能只在你**正在用的那个会话**里成立：不再打开的会话、以及主机删掉之后
     * 那些没人再认得的文件，都得跟着过期。开 App 扫一遍。
     */
    @Test fun 扫全盘_把不再打开的会话也清掉() {
        SentLog.add(ctx, h, "还在用", "新的")
        SentLog.add(ctx, h, "不再打开", "老的")
        val dir = File(ctx.filesDir, "sent")
        val old = dir.listFiles()!!.first { it.readText().contains("老的") }
        old.writeText("""{"t":${System.currentTimeMillis() - 4L * 24 * 3600 * 1000},"a":0,"x":"老的"}""" + "\n")
        assertEquals(1, SentLog.sweep(ctx))
        assertTrue("过期会话的文件还在", !old.exists())
        assertEquals(listOf("新的"), SentLog.read(ctx, h, "还在用").map { it.text })
    }

    /** 压缩写到一半崩了会留个 .tmp —— 扫的时候顺手收掉，别把它当成一个会话 */
    @Test fun 扫的时候收掉半道上的tmp() {
        SentLog.add(ctx, h, "s1", "好的")
        val tmp = File(File(ctx.filesDir, "sent"), "x.jsonl.tmp")
        tmp.writeText("半截")
        SentLog.sweep(ctx)
        assertTrue(!tmp.exists())
        assertEquals(listOf("好的"), SentLog.read(ctx, h, "s1").map { it.text })
    }

    @Test fun 空话不记() {
        SentLog.add(ctx, h, "s1", "   ")
        assertTrue(SentLog.read(ctx, h, "s1").isEmpty())
    }
}
