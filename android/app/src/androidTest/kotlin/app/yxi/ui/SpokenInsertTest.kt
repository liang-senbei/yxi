package app.yxi.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音识别出来的话插到**光标那儿**（老板 2026-09-07 报的两条之一：
 * 「光标挪到中间再说话，它还是从尾巴上追加」）。
 *
 * ⚠️ 光标落在哪同样要盯：输入框最多 7 行、超了在框里滚，**滚到哪跟着光标走** ——
 * 光标不跟着插入内容走，新说的字就落在看不见的下面（老板同一条的前半）。
 */
class SpokenInsertTest {

    private fun v(text: String, at: Int) = TextFieldValue(text, TextRange(at))

    @Test fun 插在光标那儿而不是尾巴上() {
        val r = insertSpoken(v("开头 结尾", 2), "中间")
        assertEquals("开头 中间 结尾", r.text)
        // 光标停在插入内容之后，才轮得到「你继续说的字看得见」
        assertEquals(TextRange("开头 中间".length), r.selection)
    }

    @Test fun 空草稿不带前导空格() {
        val r = insertSpoken(v("", 0), " 你好 ")
        assertEquals("你好", r.text)
        assertEquals(TextRange(2), r.selection)
    }

    @Test fun 接在尾巴上只留一个空格() {
        // 原来那句 `(draft.trimEnd() + " " + said).trim()` 的行为，光标在末尾时要一模一样
        val r = insertSpoken(v("你好 ", 3), "世界")
        assertEquals("你好 世界", r.text)
        assertEquals(TextRange(5), r.selection)
    }

    @Test fun 选中一段时只插不删() {
        // ⚠️ 语音是补话不是替换 —— 把选中的字吃掉是不可逆的损失
        val r = insertSpoken(TextFieldValue("abcd", TextRange(1, 3)), "X")
        assertEquals("a Xbcd", r.text)
    }

    @Test fun 光标越界时夹回范围内() {
        // 草稿被别处改短过（发送后清空、回填），选区可能还停在旧位置
        val r = insertSpoken(v("ab", 99), "c")
        assertEquals("ab c", r.text)
        assertEquals(TextRange(4), r.selection)
    }
}
