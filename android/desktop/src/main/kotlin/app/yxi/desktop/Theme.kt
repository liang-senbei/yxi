package app.yxi.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题 token。⚠️ 老板 09-12：「Windows 的 UI 也用手机版同款」——取值整体搬自手机端
 * `ui/theme/Palette.kt`（深色 YxiPalette = 暖铜 + 暖黑终端气质；浅色 LightPalette = 参考款
 * 蓝灰卡片 + Google 蓝）。桌面组件只认 [Tokens]，换皮换的是这里的值，不是组件。
 *
 * 映射（手机名 → 桌面名）：Surface→surface2（对话画布）/ ContainerLow→surface1（侧栏·composer 卡）
 * / ContainerLowest→surface0 / ContainerHigh→surface3 / Copper→accent / Teal→success /
 * Amber→warning（「需要你动手」独占色相的规矩照旧）/ DiffDelFg→danger。
 *
 * 界面风格（[UiStyle]）也只换这里的值：经典只填前 15 个，后面的语义位默认取改动前的等价值，经典一个像素都不变；
 * Code 风格（design/ui-style-prd.md §5.2）把它们逐个覆盖。
 */
data class Tokens(
    val surface0: Color,      // 页面底
    val surface1: Color,      // 侧栏 / 次级 / composer 卡
    val surface2: Color,      // 面板（对话画布）
    val surface3: Color,      // 弹层
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,        // 手机端 Copper：主操作 / 链接（发送键走 brand，经典里两者同值）
    val onAccent: Color,      // OnCopper：accent 底上的字
    val userBubble: Color,    // 手机端 CopperContainer：「你的消息气泡」+「选中态」同源
    val userBubbleText: Color,// OnCopperContainer
    val danger: Color,
    val success: Color,       // 手机端 Teal
    val warning: Color,       // 手机端 Amber：只在「需要你动手」时出现
    val dark: Boolean,
    // ── 以下带默认值：默认 = 改动前的派生公式 / 借用的位，经典不传；Code 风格覆盖成实测的不透明色 ──
    val hover: Color = textPrimary.copy(alpha = 0.05f),     // ghost hover 5%
    val selected: Color = textPrimary.copy(alpha = 0.10f),  // 选中 10%
    val border: Color = textPrimary.copy(alpha = 0.08f),
    val sidebar: Color = surface1,            // 侧栏底；顶部条跟它走
    val codeBg: Color = surface1,             // Markdown 代码块底
    val diffAdd: Color = success,             // diff 的 + 行字色
    val diffDel: Color = danger,              // diff 的 - 行字色
    val brand: Color = accent,                // 品牌位：发送键（Code 风格用 Yxi 铜，不用参照的陶土色）
    val onBrand: Color = onAccent,
    val composer: Color = surface1,           // 输入卡底
    val composerRing: Color = border,         // 输入卡描边
    val composerRingFocused: Color = accent.copy(alpha = 0.6f),
    val menu: Color = surface1,               // 下拉菜单底（M3 surfaceContainer）
    val dialog: Color = surface3,             // 对话框底（M3 surfaceContainerHigh）
) {
    companion object {
        /** 手机端 `YxiPalette`：暖色深底 + 终端气质（PRD 附录 J.1）。 */
        val darkTokens = Tokens(
            surface0 = Color(0xFF100E0B), surface1 = Color(0xFF1E1B17), surface2 = Color(0xFF16130F), surface3 = Color(0xFF2D2925),
            textPrimary = Color(0xFFEBE1D9), textSecondary = Color(0xFFD0C4B8), textMuted = Color(0xFFA89B8F),
            accent = Color(0xFFFFB787), onAccent = Color(0xFF4D2600),
            userBubble = Color(0xFF6D3A10), userBubbleText = Color(0xFFFFDCC4),
            danger = Color(0xFFFFB4A6), success = Color(0xFF8FD8C6), warning = Color(0xFFFFC46B), dark = true,
        )
        /** 手机端 `LightPalette`：参考款——白底、蓝灰卡片 #F0F4F9、Google 蓝。 */
        val light = Tokens(
            surface0 = Color(0xFFFAFAF9), surface1 = Color(0xFFF3F3F1), surface2 = Color(0xFFFFFFFF), surface3 = Color(0xFFEAEAE7),
            textPrimary = Color(0xFF1F1F1F), textSecondary = Color(0xFF3C4043), textMuted = Color(0xFF5F6368),
            accent = Color(0xFF0B57D0), onAccent = Color(0xFFFFFFFF),
            userBubble = Color(0xFFECEDEB), userBubbleText = Color(0xFF242724),
            danger = Color(0xFFC5221F), success = Color(0xFF0B8043), warning = Color(0xFFE37400), dark = false,
        )
        /** Code 风格浅色（PRD §5.2）。surface2 取 #FFFFFF 而不是参照画布 #FCFCFB：卡片、设置导航选中行都画在 surface2 上，和页面底同色就看不出来了。 */
        val codeLight = Tokens(
            surface0 = Color(0xFFFCFCFB), surface1 = Color(0xFFF3F3F3), surface2 = Color(0xFFFFFFFF), surface3 = Color(0xFFEDECE8),
            textPrimary = Color(0xFF0B0B0B), textSecondary = Color(0xFF52514E), textMuted = Color(0xFF898781),
            accent = Color(0xFF184F95), onAccent = Color(0xFFFFFFFF),
            userBubble = Color(0xFFF0F0EF), userBubbleText = Color(0xFF0B0B0B),
            danger = Color(0xFF8E2626), success = Color(0xFF006300), warning = Color(0xFFE37400), dark = false,
            hover = Color(0xFFF0EFEC), selected = Color(0xFFEDECE8), border = Color(0xFFE2E2E1),
            sidebar = Color(0xFFFBFBF9), codeBg = Color(0xFFFFFFFF),
            diffAdd = Color(0xFF1E9E3C), diffDel = Color(0xFFCD2054),
            brand = Color(0xFFE08B57), onBrand = Color(0xFF4D2600),
            composer = Color(0xFFFFFFFF), composerRing = Color(0xFFDFDFDE), composerRingFocused = Color(0xFFBCBCBB),
            menu = Color(0xFFFFFFFF), dialog = Color(0xFFFFFFFF),
        )
        /** Code 风格深色（PRD §5.2）。surface2 取代码块的 #1A1A19，比页面底 #151515 亮一档，卡片才分得出来。 */
        val codeDark = Tokens(
            surface0 = Color(0xFF151515), surface1 = Color(0xFF212121), surface2 = Color(0xFF1A1A19), surface3 = Color(0xFF343434),
            textPrimary = Color(0xFFF0EFEC), textSecondary = Color(0xFFC3C2B7), textMuted = Color(0xFF898781),
            accent = Color(0xFF6DA7EC), onAccent = Color(0xFF151515),
            userBubble = Color(0xFF212121), userBubbleText = Color(0xFFF0EFEC),
            danger = Color(0xFFEC7E7E), success = Color(0xFF0CA30C), warning = Color(0xFFFFC46B), dark = true,
            hover = Color(0xFF222221), selected = Color(0xFF343434), border = Color(0xFF292929),
            sidebar = Color(0xFF111111), codeBg = Color(0xFF1A1A19),
            diffAdd = Color(0xFF32D74B), diffDel = Color(0xFFFF2C56),
            brand = Color(0xFFE08B57), onBrand = Color(0xFF4D2600),
            // 聚焦描边深色没实测：按 textPrimary 约 24% 叠在输入卡底上推的
            composer = Color(0xFF20201F), composerRing = Color(0xFF313131), composerRingFocused = Color(0xFF525250),
            menu = Color(0xFF20201F), dialog = Color(0xFF20201F),
        )
        val current: Tokens @Composable get() = LocalTokens.current
    }
}

/** 界面风格偏好（Store.pref("uiStyle")）。认不出的值（老版本写的、手改坏的）一律按经典。 */
enum class UiStyle(val key: String, val label: String) {
    Classic("classic", "经典"), Code("code", "Code 风格");

    companion object {
        const val PREF = "uiStyle"
        fun from(key: String?): UiStyle = entries.firstOrNull { it.key == key } ?: Classic
    }
}

/** 外壳尺寸。M1 接侧栏宽、顶部条、会话头三处；对话列宽、列表行高留给 M2 的 CodeShell，经典填 [Dp.Unspecified]。 */
@Immutable
data class Metrics(
    val sidebarWidth: Dp,
    val titleBarHeight: Dp,
    val headerHeight: Dp,       // 会话头：对话 / 终端 / 文件 / 改动那一排
    val messageMaxWidth: Dp,
    val rowHeight: Dp,
)

/** 一种（风格 × 明暗）的全套取值。四份都是预先算好的常量，[YxiTheme] 只挑不算。 */
@Immutable
data class ThemeSpec(
    val style: UiStyle,
    val tokens: Tokens,
    val colors: ColorScheme,
    val typography: Typography,
    val shapes: Shapes,
    val radius: Dp,
    val radiusComposer: Dp,
    val mono: FontFamily,
    val body: TextStyle,        // 对话正文（Markdown.kt 的 BodyStyle）
    val code: TextStyle,        // 代码 / 命令 / 工具输出（CodeStyle）
    val metrics: Metrics,
) {
    companion object {
        val classicLight = classicSpec(Tokens.light)
        val classicDark = classicSpec(Tokens.darkTokens)
        val codeLight = codeSpec(Tokens.codeLight)
        val codeDark = codeSpec(Tokens.codeDark)

        fun of(style: UiStyle, dark: Boolean): ThemeSpec = when (style) {
            UiStyle.Classic -> if (dark) classicDark else classicLight
            UiStyle.Code -> if (dark) codeDark else codeLight
        }

        /** 经典：值原样搬自改动前的 Theme.kt / Markdown.kt，一个都不改（M1 验收①逐像素一致的前提）。 */
        private fun classicSpec(t: Tokens): ThemeSpec {
            val body = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
            // 手机端 YxiShapes：卡片 28 / 内嵌 22 / 小件 16 —— 桌面密度取中：基础 14、composer/大卡 22
            val radius = 14.dp
            val radiusComposer = 22.dp
            val mono = FontFamily.Monospace
            return ThemeSpec(
                style = UiStyle.Classic, tokens = t, colors = colorScheme(t),
                typography = Typography(
                    bodyLarge = body, bodyMedium = body, bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    labelLarge = TextStyle(fontSize = 13.sp), labelMedium = TextStyle(fontSize = 12.sp), labelSmall = TextStyle(fontSize = 11.sp),
                    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
                ),
                shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(radius), medium = RoundedCornerShape(radius), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(radiusComposer)),
                radius = radius, radiusComposer = radiusComposer, mono = mono,
                // 手机端的呼吸感（手机 Theme.kt AiryType 的注释）：字号大一点、行距松一点——光换色不改行距，看着还是「另一个 app」
                body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
                code = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 19.sp),
                metrics = Metrics(sidebarWidth = 288.dp, titleBarHeight = 40.dp, headerHeight = 48.dp, messageMaxWidth = Dp.Unspecified, rowHeight = Dp.Unspecified),
            )
        }

        /** Code 风格（PRD §5.3、规格表 §1.7～1.8）：正文 14 / 20，圆角收到 5～12。M1 只换值不换结构，所以设置里挂「预览」。 */
        private fun codeSpec(t: Tokens): ThemeSpec {
            val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
            val mono = FontFamily.Monospace
            return ThemeSpec(
                style = UiStyle.Code, tokens = t, colors = colorScheme(t),
                typography = Typography(
                    bodyLarge = body, bodyMedium = body, bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    labelLarge = body, labelMedium = TextStyle(fontSize = 12.sp), labelSmall = TextStyle(fontSize = 11.sp),
                    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
                ),
                // 浮层 / 菜单 10（extraSmall 也管输入框，参照的输入框是 5～6，留给 M3 单独给）；chips 5；卡片、代码块 8；弹窗 12
                shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(5.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(12.dp)),
                radius = 10.dp, radiusComposer = 10.dp, mono = mono,
                body = body,
                code = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 17.sp),
                metrics = Metrics(sidebarWidth = 288.dp, titleBarHeight = 36.dp, headerHeight = 48.dp, messageMaxWidth = 768.dp, rowHeight = 26.4.dp),
            )
        }
    }
}

/** M3 配色从 [Tokens] 推：RadioButton / Switch / 菜单 / 对话框这些 Material 组件跟着风格走，不用逐个改调用点。 */
private fun colorScheme(t: Tokens): ColorScheme = (if (t.dark) darkColorScheme() else lightColorScheme()).copy(
    background = t.surface0, surface = t.surface2, surfaceVariant = t.surface3, surfaceContainer = t.menu,
    surfaceContainerHigh = t.dialog, onBackground = t.textPrimary, onSurface = t.textPrimary, onSurfaceVariant = t.textSecondary,
    surfaceContainerHighest = t.surface3, surfaceContainerLow = t.surface1, surfaceContainerLowest = t.surface2,
    primary = t.accent, onPrimary = t.onAccent,
    primaryContainer = t.userBubble, onPrimaryContainer = t.userBubbleText,
    secondary = t.success, tertiary = t.warning,
    secondaryContainer = t.surface3, onSecondaryContainer = t.textPrimary,
    tertiaryContainer = t.surface1, onTertiaryContainer = t.textPrimary,
    error = t.danger, outline = t.textMuted.copy(alpha = 0.7f), outlineVariant = t.border,
)

val LocalTokens = staticCompositionLocalOf { Tokens.light }
val LocalThemeSpec = staticCompositionLocalOf { ThemeSpec.classicLight }

// 原来是顶层常量，现在跟风格走；调用点写法不变，只是必须在 Composable 里取
val Radius: Dp @Composable @ReadOnlyComposable get() = LocalThemeSpec.current.radius                 // 基础圆角
val RadiusComposer: Dp @Composable @ReadOnlyComposable get() = LocalThemeSpec.current.radiusComposer // 输入框 / 大卡片
val Mono: FontFamily @Composable @ReadOnlyComposable get() = LocalThemeSpec.current.mono

/** 主题偏好：界面风格（Store.pref("uiStyle")）× 明暗 system / light / dark（Store.pref("theme")）。 */
@Composable
fun YxiTheme(content: @Composable () -> Unit) {
    val style = UiStyle.from(Store.pref(UiStyle.PREF, UiStyle.Classic.key))
    val pref = Store.pref("theme", "system")
    val dark = when (pref) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    YxiTheme(style, dark, content)
}

/** 显式参数版：测试和截图夹具只用这个，不读写用户的真实偏好。 */
@Composable
fun YxiTheme(style: UiStyle, dark: Boolean, content: @Composable () -> Unit) {
    val spec = ThemeSpec.of(style, dark)
    CompositionLocalProvider(LocalThemeSpec provides spec, LocalTokens provides spec.tokens) {
        MaterialTheme(colorScheme = spec.colors, typography = spec.typography, shapes = spec.shapes, content = content)
    }
}
