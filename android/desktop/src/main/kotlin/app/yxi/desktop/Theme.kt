package app.yxi.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
 */
data class Tokens(
    val surface0: Color,      // 页面底
    val surface1: Color,      // 侧栏 / 次级 / composer 卡
    val surface2: Color,      // 面板（对话画布）
    val surface3: Color,      // 弹层
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,        // 手机端 Copper：主操作 / 链接 / 发送
    val onAccent: Color,      // OnCopper：accent 底上的字
    val userBubble: Color,    // 手机端 CopperContainer：「你的消息气泡」+「选中态」同源
    val userBubbleText: Color,// OnCopperContainer
    val danger: Color,
    val success: Color,       // 手机端 Teal
    val warning: Color,       // 手机端 Amber：只在「需要你动手」时出现
    val dark: Boolean,
) {
    val hover get() = textPrimary.copy(alpha = 0.05f)        // ghost hover 5%
    val selected get() = textPrimary.copy(alpha = 0.10f)     // 选中 10%
    val border get() = textPrimary.copy(alpha = 0.08f)

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
            surface0 = Color(0xFFF8FAFD), surface1 = Color(0xFFF0F4F9), surface2 = Color(0xFFFFFFFF), surface3 = Color(0xFFE9EEF6),
            textPrimary = Color(0xFF1F1F1F), textSecondary = Color(0xFF3C4043), textMuted = Color(0xFF5F6368),
            accent = Color(0xFF0B57D0), onAccent = Color(0xFFFFFFFF),
            userBubble = Color(0xFFD3E3FD), userBubbleText = Color(0xFF041E49),
            danger = Color(0xFFC5221F), success = Color(0xFF0B8043), warning = Color(0xFFE37400), dark = false,
        )
        val current: Tokens @Composable get() = LocalTokens.current
    }
}

val LocalTokens = staticCompositionLocalOf { Tokens.light }
// 手机端 YxiShapes：卡片 28 / 内嵌 22 / 小件 16 —— 桌面密度取中：基础 14、composer/大卡 22
val Radius = 14.dp            // 基础圆角
val RadiusComposer = 22.dp    // 输入框 / 大卡片
val Mono = FontFamily.Monospace

/** 主题偏好：system / light / dark（Store.pref("theme")）。 */
@Composable
fun YxiTheme(content: @Composable () -> Unit) {
    val pref = Store.pref("theme", "system")
    val dark = when (pref) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val t = if (dark) Tokens.darkTokens else Tokens.light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = t.surface0, surface = t.surface2, surfaceVariant = t.surface3, surfaceContainer = t.surface1,
        surfaceContainerHigh = t.surface3, onBackground = t.textPrimary, onSurface = t.textPrimary, onSurfaceVariant = t.textSecondary,
        primary = t.accent, onPrimary = t.onAccent,
        primaryContainer = t.userBubble, onPrimaryContainer = t.userBubbleText,
        secondary = t.success, tertiary = t.warning,
        error = t.danger, outline = t.textMuted, outlineVariant = t.border,
    )
    val body = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val type = Typography(
        bodyLarge = body, bodyMedium = body, bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontSize = 13.sp), labelMedium = TextStyle(fontSize = 12.sp), labelSmall = TextStyle(fontSize = 11.sp),
        titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    )
    val shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(Radius), medium = RoundedCornerShape(Radius), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(RadiusComposer))
    CompositionLocalProvider(LocalTokens provides t) { MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content) }
}
