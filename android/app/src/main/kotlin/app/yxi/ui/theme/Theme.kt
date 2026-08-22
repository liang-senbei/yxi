package app.yxi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// 圆角：卡片 28 / 内嵌 22 / 小件 16。控件的药丸形状在组件里单独用 100.dp
private val YxiShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(16.dp),
    medium     = RoundedCornerShape(22.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val YxiDark = darkColorScheme(
    primary = Copper, onPrimary = OnCopper,
    primaryContainer = CopperContainer, onPrimaryContainer = OnCopperContainer,
    secondary = Teal, onSecondary = OnCopper,
    tertiary = Amber, onTertiary = OnCopper,
    background = Surface, onBackground = OnSurface,
    surface = Surface, onSurface = OnSurface,
    surfaceVariant = SurfaceContainerHigh, onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = Dim, outlineVariant = SurfaceContainerHigh,
    error = DiffDelFg, onError = OnCopper,
)

/**
 * 只有深色。终端必然是深色（浅底读 ANSI 彩色输出很吃力），
 * 做浅色对话 + 深色终端来回切会闪眼 —— 见 PRD 附录 J.1。
 * [darkTheme] 参数留着是为了将来真要做浅色时不用改签名。
 */
@Composable
fun YxiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = YxiDark, shapes = YxiShapes, content = content)
}
