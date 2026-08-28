package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真机抓下来的原始屏幕**盯住选择器解析。
 * ⚠️ 用户报过：「只有选择没有问题」——问题正文丢了；末尾的操作提示被当成了最后一项的说明。
 * 这两份样本是 2026-08-28 从真实 Claude Code（AskUserQuestion，两个问题、第二个多选）抓的。
 */
class PromptRealTest {

    private val single = listOf(
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "←  ☐ 名字  ☐ 配色  ✔ Submit  →",
        "",
        "给这个 App 起什么名字？",
        "",
        "❯ 1. AskTest",
        "     直接用项目目录名，简单明了",
        "  2. QuickAsk",
        "     突出快速提问的核心功能",
        "  3. AskFlow",
        "     强调提问的流畅体验",
        "  4. AskHub",
        "     定位为提问的中心枢纽",
        "  5. Type something.",
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "  6. Chat about this",
        "",
        "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    ).joinToString("\n")

    private val multi = listOf(
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "←  ☐ 名字  ☒ 配色  ✔ Submit  →",
        "",
        "主色调用哪个？",
        "",
        "❯ 1. [ ] 蓝色",
        "  专业、信任感，适合工具类产品",
        "  2. [✔] 绿色",
        "  清新、自然，适合轻量交互",
        "  3. [ ] 紫色",
        "  创意、高端，适合 AI 相关产品",
        "  4. [ ] Type something",
        "     Submit",
        "───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────",
        "  5. Chat about this",
        "",
        "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    ).joinToString("\n")

    @Test fun 单选_问题正文不能丢() {
        val p = Prompt.parse(single)!!
        assertEquals("给这个 App 起什么名字？", p.title)
    }

    @Test fun 单选_选项和说明都对() {
        val p = Prompt.parse(single)!!
        assertEquals(6, p.options.size)
        assertEquals("AskTest", p.options[0].label)
        assertEquals("直接用项目目录名，简单明了", p.options[0].description)
        assertEquals("Type something.", p.options[4].label)
        assertEquals("Chat about this", p.options[5].label)
        // ⚠️ 底部那行操作提示**不能**变成最后一项的说明（用户截图里就是这样）
        assertTrue("最后一项的说明串进了脚注: " + p.options[5].description,
            !p.options[5].description.contains("Enter to select"))
    }

    @Test fun 多选_认得出勾选状态() {
        val p = Prompt.parse(multi)!!
        assertEquals("主色调用哪个？", p.title)
        assertTrue("应该认成多选", p.multiSelect)
        assertEquals("蓝色", p.options[0].label)
        assertEquals(false, p.options[0].checked)
        assertEquals("绿色", p.options[1].label)
        assertEquals(true, p.options[1].checked)     // 屏幕上是 [✔]
    }

    /** 窄屏（手机开过终端后会话被缩窄）—— 用户报的那个 bug 就发生在这个宽度。 */
    private val narrow = listOf(
        "──────────────────────────────────────────────",
        "←  ☐ 名字  ☒ 配色  ✔ Submit  →",
        "",
        "给这个 App 起什么名字？",
        "",
        "❯ 1. AskTest",
        "     直接用项目目录名，简单明了",
        "  2. QuickAsk",
        "     突出快速提问的核心功能",
        "  3. AskFlow",
        "     强调提问的流畅体验",
        "  4. AskHub",
        "     定位为提问的中心枢纽",
        "  5. Type something.",
        "──────────────────────────────────────────────",
        "  6. Chat about this",
        "",
        "Enter to select · Tab/Arrow keys to navigate ·",
        "Esc to cancel",
        "",
    ).joinToString("\n")

    /** 多问题的「复核 / 提交」页。 */
    private val review = listOf(
        "──────────────────────────────────────────────",
        "←  ☐ 名字  ☒ 配色  ✔ Submit  →",
        "",
        "Review your answers",
        "",
        "⚠ You have not answered all questions",
        "",
        " ● 主色调用哪个？",
        "   → 绿色",
        "",
        "Ready to submit your answers?",
        "",
        "❯ 1. Submit answers",
        "  2. Cancel",
        "",
        "",
        "",
        "",
    ).joinToString("\n")

    @Test fun 窄屏_脚注换行不能变成最后一项的说明() {
        val p = Prompt.parse(narrow)!!
        val last = p.options.last()
        assertEquals("Chat about this", last.label)
        // ⚠️ 用户截图里这里是 "Enter to select · Tab/Arrow keys to"
        assertEquals("", last.description)
    }

    @Test fun 窄屏_问题正文照样在() {
        assertEquals("给这个 App 起什么名字？", Prompt.parse(narrow)!!.title)
    }

    @Test fun 认得出多问题的标签栏() {
        val p = Prompt.parse(narrow)!!
        assertEquals(3, p.tabs.size)                       // 名字 / 配色 / Submit
        assertEquals("名字", p.tabs[0].label)
        assertEquals(false, p.tabs[0].answered)            // ☐
        assertEquals("配色", p.tabs[1].label)
        assertEquals(true, p.tabs[1].answered)             // ☒ 已答
        assertTrue(p.tabs[2].submit)
    }

    @Test fun 认得出复核页() {
        val p = Prompt.parse(review)!!
        assertTrue("应认成复核/提交页", p.review)
        assertEquals("Submit answers", p.options[0].label)
    }
}
