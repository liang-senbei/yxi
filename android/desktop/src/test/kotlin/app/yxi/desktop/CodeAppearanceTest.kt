package app.yxi.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M4q：对话字号 / 对话宽度的偏好解析和取值表，设置弹窗导航的搜索（纯逻辑，不起界面）。 */
class CodeAppearanceTest {
    @Test fun `font size pref parses known keys and falls back to medium`() {
        assertEquals(ChatFontSize.Small, ChatFontSize.of("small"))
        assertEquals(ChatFontSize.Large, ChatFontSize.of("large"))
        for (bad in listOf(null, "", "Medium", "huge", " small")) assertEquals(ChatFontSize.Medium, ChatFontSize.of(bad))
        assertEquals("ui.chatFontSize", ChatFontSize.PREF)
        assertEquals(ChatFontSize.entries, ChatFontSize.entries.map { ChatFontSize.of(it.key) })   // 每档的 key 都能读回自己
    }

    @Test fun `medium is exactly the measured 14 over 20 and the scale is monotonic`() {
        // 默认档乘进 fontScale 的倍数必须正好是 1，现有截图才逐像素不变
        assertEquals(1f, ChatFontSize.Medium.scale)
        assertEquals(14f, ChatFontSize.Medium.size)
        assertEquals(20f, ChatFontSize.Medium.lineHeight)
        assertEquals(listOf("小", "中", "大"), ChatFontSize.entries.map { it.label })
        assertTrue(ChatFontSize.Small.scale < 1f && ChatFontSize.Large.scale > 1f)
        assertEquals(13f / 14f, ChatFontSize.Small.scale)
        assertEquals(16f / 14f, ChatFontSize.Large.scale)
        assertEquals(20f * 16f / 14f, ChatFontSize.Large.lineHeight, 1e-4f)
    }

    @Test fun `width pref parses known keys and falls back to narrow`() {
        assertEquals(ChatWidth.Medium, ChatWidth.of("medium"))
        assertEquals(ChatWidth.Wide, ChatWidth.of("wide"))
        for (bad in listOf(null, "", "WIDE", "1024")) assertEquals(ChatWidth.Narrow, ChatWidth.of(bad))
        assertEquals("ui.chatWidth", ChatWidth.PREF)
        assertEquals(ChatWidth.entries, ChatWidth.entries.map { ChatWidth.of(it.key) })
    }

    @Test fun `narrow is the measured 768 column and 627·2 bubble, wider steps keep the bubble ratio`() {
        assertEquals(768.dp, ChatWidth.Narrow.column)
        assertEquals(627.2.dp, ChatWidth.Narrow.bubbleMax)
        assertEquals(listOf(768.dp, 896.dp, 1024.dp), ChatWidth.entries.map { it.column })
        assertEquals(listOf("窄", "中", "宽"), ChatWidth.entries.map { it.label })
        for (w in ChatWidth.entries) {
            assertEquals(627.2f / 768f, w.bubbleMax.value / w.column.value, 1e-4f, "$w 的气泡比例")
            assertTrue(w.bubbleMax < w.column)
        }
        // 2048 宽的窗口、侧栏开着时主区 1760：最宽一档也放得下两侧各 40，不会被收窄
        assertTrue(ChatWidth.Wide.column.value <= 1760f - 80f)
    }

    @Test fun `settings search filters sections by name or by the settings inside`() {
        assertEquals(SETTINGS_SECTIONS, settingsMatches(""))
        assertEquals(SETTINGS_SECTIONS, settingsMatches("   "))
        assertEquals(listOf("外观"), settingsMatches("字号"))
        assertEquals(listOf("外观"), settingsMatches(" 对话宽度 "))
        assertEquals(listOf("外观"), settingsMatches("code 风格"))   // 不分大小写
        assertEquals(listOf("常规"), settingsMatches("托盘"))
        assertEquals(listOf("模型与线路"), settingsMatches("api"))
        assertEquals(listOf("关于"), settingsMatches("更新"))
        assertEquals(emptyList(), settingsMatches("没有这一项"))
        assertTrue("快捷键" in settingsMatches("快捷"))
        // 索引只收真有的分区，每个分区都有关键词
        assertEquals(SETTINGS_SECTIONS.toSet(), SETTINGS_KEYWORDS.keys)
        assertTrue(SETTINGS_JUMPS.all { it in SETTINGS_SECTIONS })
    }
}
