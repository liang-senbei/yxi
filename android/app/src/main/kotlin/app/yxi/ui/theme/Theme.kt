package app.yxi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.ui.Skin

// 圆角：卡片 28 / 内嵌 22 / 小件 16。控件的药丸形状在组件里单独用 100.dp
private val YxiShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(16.dp),
    medium     = RoundedCornerShape(22.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun schemeOf(p: Palette) = if (p.light) {
    lightColorScheme(
        primary = p.Copper, onPrimary = p.OnCopper,
        primaryContainer = p.CopperContainer, onPrimaryContainer = p.OnCopperContainer,
        secondary = p.Teal, onSecondary = p.OnCopper,
        tertiary = p.Amber, onTertiary = p.OnCopper,
        tertiaryContainer = p.SurfaceContainerHigh, onTertiaryContainer = p.OnSurface,
        background = p.Surface, onBackground = p.OnSurface,
        surface = p.Surface, onSurface = p.OnSurface,
        surfaceVariant = p.SurfaceContainerHigh, onSurfaceVariant = p.OnSurfaceVariant,
        surfaceContainerLowest = p.SurfaceContainerLowest,
        surfaceContainerLow = p.SurfaceContainerLow,
        surfaceContainer = p.SurfaceContainer,
        surfaceContainerHigh = p.SurfaceContainerHigh,
        surfaceContainerHighest = p.SurfaceContainerHighest,
        outline = p.Dim, outlineVariant = p.SurfaceContainerHigh,
        error = p.DiffDelFg, onError = p.OnCopper,
        errorContainer = p.DiffDelBg, onErrorContainer = p.DiffDelFg,
    )
} else {
    darkColorScheme(
        primary = p.Copper, onPrimary = p.OnCopper,
        primaryContainer = p.CopperContainer, onPrimaryContainer = p.OnCopperContainer,
        secondary = p.Teal, onSecondary = p.OnCopper,
        tertiary = p.Amber, onTertiary = p.OnCopper,
        tertiaryContainer = p.CopperContainer, onTertiaryContainer = p.OnCopperContainer,
        background = p.Surface, onBackground = p.OnSurface,
        surface = p.Surface, onSurface = p.OnSurface,
        surfaceVariant = p.SurfaceContainerHigh, onSurfaceVariant = p.OnSurfaceVariant,
        surfaceContainerLowest = p.SurfaceContainerLowest,
        surfaceContainerLow = p.SurfaceContainerLow,
        surfaceContainer = p.SurfaceContainer,
        surfaceContainerHigh = p.SurfaceContainerHigh,
        surfaceContainerHighest = p.SurfaceContainerHighest,
        outline = p.Dim, outlineVariant = p.SurfaceContainerHigh,
        error = p.DiffDelFg, onError = p.OnCopper,
        errorContainer = p.DiffDelBg, onErrorContainer = p.DiffDelFg,
    )
}

/**
 * 参考款那套的排版：**正文更大、行距更松**。
 *
 * ⚠️ 只有配色像还不够 —— 截图里最直观的差别其实是**呼吸感**：
 * 正文 16sp 但行高给到 26sp。照抄颜色不改行距，看着还是「另一个 app」。
 */
private val AiryType = Typography().let { d ->
    d.copy(
        bodyLarge = d.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = d.bodyMedium.copy(lineHeight = 22.sp),
    )
}

/**
 * ⚠️ **终端不跟着变浅。** ANSI 彩色输出按深底配的，浅底上黄/亮绿几乎看不见。
 * 所以浅色风格下切到终端会有一下明暗跳变 —— 自觉的取舍（见 [LightPalette]）。
 */
@Composable
fun YxiTheme(content: @Composable () -> Unit) {
    val p = Skin.style.palette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = schemeOf(p),
            shapes = YxiShapes,
            typography = if (p.light) AiryType else Typography(),
            content = content,
        )
    }
}
