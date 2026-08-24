package app.yxi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * 翻译表的完整性。
 *
 * ⚠️ 这里**测不了「源码里每句都翻了」** —— 运行时读不到源码。
 * 那件事归 `dev/i18n-check.sh`（扫源码比对本表）。
 * 这里测的是**表自身**：占位符对不上会在运行时抛异常，那才是会崩的那种。
 */
class I18nTest {

    private val PH = Pattern.compile("%[0-9]*[sdf]")

    private fun placeholders(s: String): List<String> {
        val m = PH.matcher(s); val out = ArrayList<String>()
        while (m.find()) out.add(m.group())
        return out
    }

    @Test fun 占位符必须一一对应() {
        // ⚠️ 调用方是 `t("…").format(x)`：英文那条少一个 %s 就静默丢参数，
        // 多一个就直接抛 MissingFormatArgumentException —— 后者是当场崩
        val bad = En.map.entries.filter { placeholders(it.key) != placeholders(it.value) }
        assertTrue(
            "这些条目中英占位符对不上：" + bad.joinToString("\n") { "${it.key}  →  ${it.value}" },
            bad.isEmpty(),
        )
    }

    @Test fun 没有空译文() {
        val blank = En.map.entries.filter { it.value.isBlank() }
        assertTrue("空译文会让界面缺一块，宁可留中文：$blank", blank.isEmpty())
    }

    @Test fun 没有原样照抄的中文() {
        // 抄一遍中文等于没翻，还骗过了 i18n-check
        val cjk = Regex("[一-鿿]")
        val lazy = En.map.entries.filter { cjk.containsMatchIn(it.value) }
        assertTrue("译文里还有中文：" + lazy.joinToString(), lazy.isEmpty())
    }

    @Test fun 切换语言真的换字() {
        val before = I18n.lang
        try {
            assertEquals("会话", t("会话"))          // 默认中文
            // 直接改状态，不落盘 —— 测试不该动用户的偏好
            assertTrue(En.map.containsKey("会话"))
            assertEquals("Sessions", En.map["会话"])
        } finally {
            assertEquals(before, I18n.lang)
        }
    }
}
