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
 * 主题 token（design/desktop-reference.md §4.9：取 Claude Desktop 的暖灰）。
 * 面板颜色一律从 [Tokens] 拿（`Tokens.current` 或 MaterialTheme.colorScheme），别在面板里写死颜色。
 */
data class Tokens(
    val surface0: Color,      // 页面底
    val surface1: Color,      // 侧栏 / 次级
    val surface2: Color,      // 面板
    val surface3: Color,      // 弹层
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val dark: Boolean,
) {
    val hover get() = textPrimary.copy(alpha = 0.05f)        // ghost hover 5%
    val selected get() = textPrimary.copy(alpha = 0.10f)     // 选中 10%
    val userBubble get() = textPrimary.copy(alpha = 0.05f)   // 用户气泡 = 一层 5%
    val border get() = textPrimary.copy(alpha = 0.08f)

    companion object {
        val light = Tokens(
            surface0 = Color(0xFFF9F9F7), surface1 = Color(0xFFFCFCFB), surface2 = Color(0xFFFFFFFF), surface3 = Color(0xFFFFFFFF),
            textPrimary = Color(0xFF0B0B0B), textSecondary = Color(0xFF52514E), textMuted = Color(0xFF898781),
            accent = Color(0xFF2A78D6), danger = Color(0xFF8E2626), success = Color(0xFF006300), warning = Color(0xFF98801F), dark = false,
        )
        val darkTokens = Tokens(
            surface0 = Color(0xFF0B0B0B), surface1 = Color(0xFF151515), surface2 = Color(0xFF1A1A19), surface3 = Color(0xFF20201F),
            textPrimary = Color(0xFFF0EFEC), textSecondary = Color(0xFFC3C2B7), textMuted = Color(0xFF898781),
            accent = Color(0xFF6DA7EC), danger = Color(0xFFEC7E7E), success = Color(0xFF0CA30C), warning = Color(0xFFFFD014), dark = true,
        )
        val current: Tokens @Composable get() = LocalTokens.current
    }
}

val LocalTokens = staticCompositionLocalOf { Tokens.light }
val Radius = 8.dp            // 基础圆角
val RadiusComposer = 14.dp   // 输入框
val Mono = FontFamily.Monospace

/** 主题偏好：system / light / dark（Store.pref("theme")）。 */
@Composable
fun YxiTheme(content: @Composable () -> Unit) {
    val pref = Store.pref("theme", "system")
    val dark = when (pref) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val t = if (dark) Tokens.darkTokens else Tokens.light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = t.surface0, surface = t.surface2, surfaceVariant = t.surface1, surfaceContainer = t.surface1,
        surfaceContainerHigh = t.surface3, onBackground = t.textPrimary, onSurface = t.textPrimary, onSurfaceVariant = t.textSecondary,
        primary = t.accent, onPrimary = if (dark) Color(0xFF0B0B0B) else Color.White, error = t.danger, outline = t.border, outlineVariant = t.border,
    )
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val type = Typography(
        bodyLarge = body, bodyMedium = body, bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontSize = 13.sp), labelMedium = TextStyle(fontSize = 12.sp), labelSmall = TextStyle(fontSize = 11.sp),
        titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    )
    val shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(Radius), medium = RoundedCornerShape(Radius), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(RadiusComposer))
    CompositionLocalProvider(LocalTokens provides t) { MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content) }
}
