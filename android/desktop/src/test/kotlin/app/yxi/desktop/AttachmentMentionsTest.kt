package app.yxi.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // ── G1：ASCII 标点落点 ──

    @Test
    fun `ascii punctuation after a mention is a clean boundary`() {
        val a = attach("s1.png", true, "a"); val b = attach("s2.png", true, "b")
        // 半角逗号/句号+空格/右括号/冒号都算干净边界：死引用删除、存活引用重编号、标点原样保留
        val draft = edited("先@图片1, 后@图片2. 收尾@图片2)；注@图片2:尾")
        val next = draftAfterAttachmentRemoval(draft, listOf(a, b), a)
        assertEquals("先, 后@图片1. 收尾@图片1)；注@图片1:尾", next.text)
        assertEquals(next.text.length, next.selection.end)
    }

    @Test
    fun `period glued to letters stays a filename tail not a boundary`() {
        val a = attach("s1.png", true, "a"); val b = attach("s2.png", true, "b")
        // `@图片1.png` 读起来像文件名尾巴：句号后紧跟字母数字 → 绝不当边界改写；后面正常引用照常重编号
        val next = draftAfterAttachmentRemoval(edited("见@图片1.png 与@图片2"), listOf(a, b), a)
        assertEquals("见@图片1.png 与@图片1", next.text)
    }

    @Test
    fun `unknown number before ascii punctuation stays verbatim`() {
        val a = attach("s1.png", true, "a")
        // 不存在的编号即使落在 ASCII 标点前也原样保留（不改写也不删除别人的引用）
        val next = draftAfterAttachmentRemoval(edited("引用@附件9, 无此物"), listOf(a), a)
        assertEquals("引用@附件9, 无此物", next.text)
    }

    // ── G3：弹层键盘语义（handleMentionKey 纯函数） ──

    private fun tap(
        key: Key, type: KeyEventType = KeyEventType.KeyDown, shift: Boolean = false,
        count: Int = 2, composition: Boolean = false, current: Int = 0,
        move: (Int) -> Unit = {}, accept: () -> Unit = {}, dismiss: () -> Unit = {},
    ) = handleMentionKey(type, key, shift, count, composition, current, move, accept, dismiss)

    @Test
    fun `shift enter hands the newline back to the field`() {
        var accepted = 0
        // Shift+Enter 不接受候选：事件放行给输入框插换行
        assertFalse(tap(Key.Enter, shift = true, accept = { accepted++ }))
        assertFalse(tap(Key.NumPadEnter, shift = true, accept = { accepted++ }))
        assertEquals(0, accepted)
        // 普通 Enter 与小键盘 Enter 仍然接受
        assertTrue(tap(Key.Enter, accept = { accepted++ }))
        assertTrue(tap(Key.NumPadEnter, accept = { accepted++ }))
        assertEquals(2, accepted)
    }

    @Test
    fun `arrows wrap selection and other keys pass through`() {
        var moved: Int? = null; var dismissed = false
        assertTrue(tap(Key.DirectionDown, current = 0, count = 3, move = { moved = it }))
        assertEquals(1, moved)
        assertTrue(tap(Key.DirectionDown, current = 2, count = 3, move = { moved = it }))
        assertEquals(0, moved, "末尾再向下环绕回首项")
        assertTrue(tap(Key.DirectionUp, current = 0, count = 3, move = { moved = it }))
        assertEquals(2, moved, "首项向上环绕到末项")
        assertTrue(tap(Key.Escape, dismiss = { dismissed = true }))
        assertTrue(dismissed)
        // 非处理键、KeyUp、IME 组字、无候选：一律放行
        assertFalse(tap(Key.A))
        assertFalse(tap(Key.Enter, type = KeyEventType.KeyUp))
        assertFalse(tap(Key.Enter, composition = true, accept = { error("组字中不接受") }))
        assertFalse(tap(Key.Enter, count = 0, accept = { error("无候选不接受") }))
    }
}
