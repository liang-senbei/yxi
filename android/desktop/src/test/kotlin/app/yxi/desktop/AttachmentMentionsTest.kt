package app.yxi.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 附件提及纯函数定向测试：只测 attachmentLabels 与 draftAfterAttachmentRemoval（零网络、
 * 零上传、零 UI 组合）。钉住老板点名的边界：重复文件名仍按序号可寻址、删除首张后
 * @图片2→@图片1、指向已删文件的 @引用删除而非改指别人、非干净边界的 token 绝不改写。
 * （中文紧贴@触发与输入法组字中的抑制在 rememberAttachmentMentions 组合函数内：
 * 13d55ce 的 lookbehind `(?<![A-Za-z0-9_@])` 放行中文字符、`composition == null` 门
 * 拦输入法组字——静态确认，见报告。）
 */
class AttachmentMentionsTest {
    private fun attach(name: String, image: Boolean, stamp: String) =
        DraftAttach(name, image, size = 1L, stamp = stamp, open = { error("测试不读附件内容") })

    private fun edited(text: String, cursor: Int = text.length) =
        TextFieldValue(text, TextRange(cursor))

    @Test
    fun `labels stay distinct for duplicate filenames and split by kind`() {
        val items = listOf(
            attach("shot.png", true, "a"),
            attach("shot.png", true, "b"),
            attach("shot.png", true, "c"),
            attach("a.pdf", false, "d"),
        )
        // 标签按序号位置分配，与文件名无关：重名附件照样各自有唯一 @编号
        assertEquals(listOf("图片1", "图片2", "图片3", "附件1"), attachmentLabels(items))
        // 纯文件清单单独从附件1起编号，图片计数器不串味
        assertEquals(listOf("附件1", "附件2"), attachmentLabels(listOf(items[3], attach("b.pdf", false, "e"))))
    }

    @Test
    fun `removing first image deletes its mentions and renumbers the rest`() {
        val a = attach("s1.png", true, "a"); val b = attach("s2.png", true, "b"); val c = attach("s3.png", true, "c")
        val items = listOf(a, b, c)
        // 中文紧贴、空格、中文逗号、结尾四类落点都要处理
        val draft = edited("首张@图片1 次张@图片2，末张@图片3 结尾")
        val next = draftAfterAttachmentRemoval(draft, items, a)
        // 指向已删首张的引用整段删除（绝不让它改指别人），其余按新序号重写
        assertEquals("首张 次张@图片1，末张@图片2 结尾", next.text)
        assertEquals(next.text.length, next.selection.end)
    }

    @Test
    fun `duplicate filenames remain addressable across removal`() {
        val f1 = attach("a.pdf", false, "f1"); val f2 = attach("a.pdf", false, "f2")
        val next = draftAfterAttachmentRemoval(edited("先@附件1 后@附件2"), listOf(f1, f2), f1)
        // 重名不靠文件名区分、靠序号：删掉前一个，@附件2 重写为 @附件1 仍指向存留的那个文件
        assertEquals("先 后@附件1", next.text)
    }

    @Test
    fun `only clean mention tokens are rewritten`() {
        val a = attach("s1.png", true, "a"); val b = attach("s2.png", true, "b")
        // 移除 b：@图片2→删除；但字母紧跟/无编号/未知编号/普通@词都不许动
        val draft = edited("x@图片2y 在@图片2。参考@附件9、纯@文本")
        val next = draftAfterAttachmentRemoval(draft, listOf(a, b), b)
        assertEquals("x@图片2y 在。参考@附件9、纯@文本", next.text)
    }

    @Test
    fun `cursor follows the rewritten prefix when removal happens mid text`() {
        val a = attach("s1.png", true, "a"); val b = attach("s2.png", true, "b")
        val text = "首张@图片1 次张@图片2"
        // 光标停在「次张」之后、@图片2 之前（原下标 9）
        val next = draftAfterAttachmentRemoval(edited(text, cursor = 9), listOf(a, b), a)
        assertEquals("首张 次张@图片1", next.text)
        assertEquals(5, next.selection.end, "光标按重写后的前缀长度平移，不跳位")
    }
}
