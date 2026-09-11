package app.yxi.desktop

import app.yxi.agent.Attachments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 附件线的协议锁（core 的 Attachments 从手机端搬过来之后，桌面这边也依赖它）：
 * 头格式 / 引用解析 / 重排编号 / 路径安全，哪条变了手机和桌面会同时坏 —— 钉死在这里。
 */
class AttachmentsTest {

    @Test
    fun `发送头按编号带路径`() {
        val staged = listOf(
            Attachments.Staged("图片1", "/root/src/tmp/demo/a.png", true, ext = "png"),
            Attachments.Staged("附件1", "/root/src/tmp/demo/b.pdf", false, ext = "pdf"),
        )
        assertEquals("[图片1.png] /root/src/tmp/demo/a.png\n[附件1.pdf] /root/src/tmp/demo/b.pdf\n", Attachments.header(staged))
    }

    @Test
    fun `parseRefs 把头从正文里摘出来且按扩展名认图`() {
        val text = "[图片1.png] /root/src/tmp/demo/a.png\n[附件1.pdf] /root/src/tmp/demo/b.pdf\n看这张图"
        val (refs, body) = Attachments.parseRefs(text)
        assertEquals(2, refs.size)
        assertTrue(refs[0].isImage)
        assertEquals(false, refs[1].isImage)
        assertEquals("/root/src/tmp/demo/a.png", refs[0].path)
        assertEquals("a.png", refs[0].name)
        assertEquals("看这张图", body)
    }

    @Test
    fun `头落在两段正文中间也认（连发两条被合成一条的教训）`() {
        val text = "先看这个\n[图片1.png] /root/src/tmp/demo/a.png\n再说一句"
        val (refs, body) = Attachments.parseRefs(text)
        assertEquals(1, refs.size)
        assertEquals("先看这个\n再说一句", body)
    }

    @Test
    fun `renumber 按列表位置重排不按传完的顺序`() {
        val staged = listOf(
            Attachments.Staged("附件9", "/x/only.pdf", false, ext = "pdf"),
            Attachments.Staged("图片7", "/x/first.png", true, ext = "png"),
        )
        val out = Attachments.renumber(staged)
        assertEquals("附件1", out[0].label)
        assertEquals("图片1", out[1].label)
        assertEquals("附件1.pdf", out[0].display)
    }

    @Test
    fun `项目目录认不出的一律进misc不让路径跑出暂存区`() {
        assertEquals("/root/src/tmp/demo", Attachments.dirFor("cc-demo"))
        assertEquals("/root/src/tmp/misc", Attachments.dirFor("cc-"))
        // safeName 禁的是 shell 元字符和 /（../../etc 带 / → misc），空格这类是允许的
        assertEquals("/root/src/tmp/misc", Attachments.dirFor("../../etc"))
        assertEquals("/root/src/tmp/misc", Attachments.dirFor("a;b"))
        assertEquals("/root/src/tmp/a b", Attachments.dirFor("a b"))
    }

    @Test
    fun `remotePath 清洗文件名并加时间戳`() {
        val p = Attachments.remotePath("cc-demo", "粘贴 图片(1).PNG", "1789143904-01")
        assertTrue(p.startsWith("/root/src/tmp/demo/1789143904-01-"), p)
        assertTrue(p.endsWith(".PNG"), p)
        // 非ASCII和空格一律清洗成下划线（服务器端路径安全）
        assertTrue(p.none { it == ' ' || it.code > 127 }, p)
    }
}
