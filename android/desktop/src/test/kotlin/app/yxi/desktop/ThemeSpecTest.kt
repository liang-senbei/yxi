package app.yxi.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlin.test.*

/** M1 界面风格：偏好值回退、spec 选择，以及经典取值钉死（PRD §4.3 验收①的纯数据部分）。 */
class ThemeSpecTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return hi / lo
    }

    @Test fun `unknown style values fall back to classic`() {
        assertEquals(UiStyle.Classic, UiStyle.from(null))
        assertEquals(UiStyle.Classic, UiStyle.from(""))
        assertEquals(UiStyle.Classic, UiStyle.from("xyz"))
        assertEquals(UiStyle.Classic, UiStyle.from("CODE"))   // 键区分大小写：手改坏的值不猜
        assertEquals(UiStyle.Classic, UiStyle.from("classic"))
        assertEquals(UiStyle.Code, UiStyle.from("code"))
        assertEquals("uiStyle", UiStyle.PREF)
        assertEquals(listOf("classic", "code"), UiStyle.entries.map { it.key })
    }

    /** 四份 spec 是预先算好的常量：切换只挑不算，也不会每次重组新造一份。 */
    @Test fun `spec selection returns the four precomputed specs`() {
        assertSame(ThemeSpec.classicLight, ThemeSpec.of(UiStyle.Classic, false))
        assertSame(ThemeSpec.classicDark, ThemeSpec.of(UiStyle.Classic, true))
        assertSame(ThemeSpec.codeLight, ThemeSpec.of(UiStyle.Code, false))
        assertSame(ThemeSpec.codeDark, ThemeSpec.of(UiStyle.Code, true))
        assertSame(Tokens.light, ThemeSpec.classicLight.tokens)
        assertSame(Tokens.darkTokens, ThemeSpec.classicDark.tokens)
        assertSame(Tokens.codeLight, ThemeSpec.codeLight.tokens)
        assertSame(Tokens.codeDark, ThemeSpec.codeDark.tokens)
        for (dark in listOf(false, true)) for (style in UiStyle.entries) {
            val spec = ThemeSpec.of(style, dark)
            assertEquals(style, spec.style)
            assertEquals(dark, spec.tokens.dark)
            assertEquals(spec.tokens.surface0, spec.colors.background)
            assertEquals(spec.tokens.accent, spec.colors.primary)
        }
    }

    /** 新加的语义位默认值 = 改动前的派生公式 / 借用的位：经典不传它们，一个像素都不该变。 */
    @Test fun `classic semantic slots equal the pre-M1 formulas`() {
        for (t in listOf(Tokens.light, Tokens.darkTokens)) {
            assertEquals(t.textPrimary.copy(alpha = 0.05f), t.hover)
            assertEquals(t.textPrimary.copy(alpha = 0.10f), t.selected)
            assertEquals(t.textPrimary.copy(alpha = 0.08f), t.border)
            assertEquals(t.surface1, t.sidebar)
            assertEquals(t.surface1, t.codeBg)
            assertEquals(t.surface1, t.composer)
            assertEquals(t.surface1, t.menu)
            assertEquals(t.surface3, t.dialog)
            assertEquals(t.success, t.diffAdd)
            assertEquals(t.danger, t.diffDel)
            assertEquals(t.accent, t.brand)
            assertEquals(t.onAccent, t.onBrand)
            assertEquals(t.border, t.composerRing)
            assertEquals(t.accent.copy(alpha = 0.6f), t.composerRingFocused)
        }
    }
    /** 经典的 M3 配色、字体、形状、尺寸原样钉死（值搬自改动前的 Theme.kt / Markdown.kt / App.kt / Shell.kt）。 */
    @Test fun `classic spec keeps pre-M1 values`() {
        assertEquals(Color(0xFFFAFAF9), Tokens.light.surface0)
        assertEquals(Color(0xFF0B57D0), Tokens.light.accent)
        assertEquals(Color(0xFF100E0B), Tokens.darkTokens.surface0)
        assertEquals(Color(0xFFFFB787), Tokens.darkTokens.accent)
        for (spec in listOf(ThemeSpec.classicLight, ThemeSpec.classicDark)) {
            val t = spec.tokens
            assertEquals(t.surface1, spec.colors.surfaceContainer)
            assertEquals(t.surface3, spec.colors.surfaceContainerHigh)
            assertEquals(t.surface2, spec.colors.surface)
            assertEquals(t.textMuted.copy(alpha = 0.7f), spec.colors.outline)
            assertEquals(t.textPrimary.copy(alpha = 0.08f), spec.colors.outlineVariant)
            val body = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
            assertEquals(body, spec.typography.bodyMedium)
            assertEquals(body, spec.typography.bodyLarge)
            assertEquals(TextStyle(fontSize = 12.sp, lineHeight = 17.sp), spec.typography.bodySmall)
            assertEquals(TextStyle(fontSize = 13.sp), spec.typography.labelLarge)
            assertEquals(TextStyle(fontSize = 20.sp, lineHeight = 26.sp), spec.typography.titleLarge)
            assertEquals(Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(22.dp)), spec.shapes)
            assertEquals(14.dp, spec.radius)
            assertEquals(22.dp, spec.radiusComposer)
            assertEquals(FontFamily.Monospace, spec.mono)
            assertEquals(TextStyle(fontSize = 15.sp, lineHeight = 24.sp), spec.body)
            assertEquals(TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), spec.code)
            assertEquals(288.dp, spec.metrics.sidebarWidth)
            assertEquals(40.dp, spec.metrics.titleBarHeight)
            assertEquals(48.dp, spec.metrics.headerHeight)
            assertTrue(spec.metrics.messageMaxWidth.isUnspecified)
            assertTrue(spec.metrics.rowHeight.isUnspecified)
        }
    }

    /** Code 风格取值（PRD §5.2～5.3）；语义位全部覆盖成实测的不透明色，漏覆盖会退回经典的半透明公式。 */
    @Test fun `code spec uses measured opaque values`() {
        for (spec in listOf(ThemeSpec.codeLight, ThemeSpec.codeDark)) {
            val t = spec.tokens
            assertEquals(TextStyle(fontSize = 14.sp, lineHeight = 20.sp), spec.body)
            assertEquals(spec.body, spec.typography.bodyMedium)
            assertEquals(TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp), spec.code)
            assertEquals(10.dp, spec.radius)
            assertEquals(10.dp, spec.radiusComposer)
            assertEquals(Metrics(sidebarWidth = 288.dp, titleBarHeight = 36.dp, headerHeight = 48.dp, messageMaxWidth = 768.dp, rowHeight = 26.4.dp), spec.metrics)
            assertEquals(t.menu, spec.colors.surfaceContainer)
            assertEquals(t.dialog, spec.colors.surfaceContainerHigh)
            val slots = listOf(t.hover, t.selected, t.border, t.sidebar, t.codeBg, t.diffAdd, t.diffDel, t.brand, t.onBrand,
                t.composer, t.composerRing, t.composerRingFocused, t.menu, t.dialog)
            slots.forEachIndexed { i, c -> assertEquals(1f, c.alpha, "第 $i 个语义位不是不透明色") }
        }
        assertEquals(Color(0xFFE08B57), Tokens.codeLight.brand)
        assertEquals(Color(0xFF4D2600), Tokens.codeLight.onBrand)
    }

    /** 发送键：品牌底上的图标四套都 ≥ 4.5:1（Code 的铜底配白字只有约 2.6:1，所以 onBrand 用深棕）。 */
    @Test fun `send button keeps readable contrast in every spec`() {
        for (spec in listOf(ThemeSpec.classicLight, ThemeSpec.classicDark, ThemeSpec.codeLight, ThemeSpec.codeDark)) {
            val ratio = contrast(spec.tokens.brand, spec.tokens.onBrand)
            assertTrue(ratio >= 4.5, "${spec.style}/${spec.tokens.dark}: $ratio")
        }
    }
}
