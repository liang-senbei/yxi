package app.yxi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 配色的**取值口**。真正的值在 [Palette] 里，这里只是转发。
 *
 * ⚠️ **为什么是 `@Composable get()` 而不是 `val`。**
 * 原来这些是顶层常量，全 app 两百多处直接写 `Copper` / `Dim`。
 * 换成读当前 [LocalPalette] 的取值器之后，**那两百多处一个字都不用改**，
 * 换风格时还自动重组。
 *
 * ⚠️ 代价：**这些名字只能在 composable 里用了**。
 * 真有非 composable 要用颜色的地方（比如通知的 `setColor`），
 * 直接写字面量，别绕道这里。
 */
val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Surface
val SurfaceContainerLowest: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.SurfaceContainerLowest
val SurfaceContainerLow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.SurfaceContainerLow
val SurfaceContainer: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.SurfaceContainer
val SurfaceContainerHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.SurfaceContainerHigh
val SurfaceContainerHighest: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.SurfaceContainerHighest

val OnSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.OnSurface
val OnSurfaceVariant: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.OnSurfaceVariant
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Muted
val Dim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Dim

val Copper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Copper
val OnCopper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.OnCopper
val CopperContainer: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.CopperContainer
val OnCopperContainer: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.OnCopperContainer
val Teal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Teal
/** ⚠️ 只在「需要你动手」时出现，别挪作它用 —— 这条规矩跟配色无关，换皮也得守。 */
val Amber: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Amber

val DiffAddFg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.DiffAddFg
val DiffAddBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.DiffAddBg
val DiffDelFg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.DiffDelFg
val DiffDelBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.DiffDelBg

/** 终端永远深底 —— ANSI 彩色输出在浅底上读不了。见 [LightPalette] 的注释。 */
val TerminalBg = Color(0xFF100E0B)
val TerminalFg = Color(0xFFEBE1D9)
