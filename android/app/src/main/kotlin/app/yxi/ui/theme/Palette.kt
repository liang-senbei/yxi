package app.yxi.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 一套配色。**换风格换的就是这个对象。**
 *
 * ⚠️ 字段名跟原来 `Color.kt` 里那些顶层常量**一模一样**，这是有意的：
 * 全 app 有两百多处直接写着 `Copper` / `Dim` / `SurfaceContainerLow`，
 * 把常量改成「读当前 Palette 的 `@Composable get()`」之后，
 * **那两百多处一个字都不用动**，还自动跟着切换重组。
 * （跟 i18n 那次把中文原文当 key 是同一个思路：让改动落在一个地方。）
 */
data class Palette(
    val Surface: Color,
    val SurfaceContainerLowest: Color,
    val SurfaceContainerLow: Color,
    val SurfaceContainer: Color,
    val SurfaceContainerHigh: Color,
    val SurfaceContainerHighest: Color,
    val OnSurface: Color,
    val OnSurfaceVariant: Color,
    val Muted: Color,
    val Dim: Color,
    val Copper: Color,
    val OnCopper: Color,
    val CopperContainer: Color,
    val OnCopperContainer: Color,
    val Teal: Color,
    val Amber: Color,
    val DiffAddFg: Color, val DiffAddBg: Color,
    val DiffDelFg: Color, val DiffDelBg: Color,
    /** 亮色底才需要 —— M3 的 darkColorScheme/lightColorScheme 要选对，否则控件默认色全错 */
    val light: Boolean,
)

/** 原来那套：暖色深底 + 终端气质。取值见 PRD 附录 J.1。 */
val YxiPalette = Palette(
    Surface = Color(0xFF16130F),
    SurfaceContainerLowest = Color(0xFF100E0B),
    SurfaceContainerLow = Color(0xFF1E1B17),
    SurfaceContainer = Color(0xFF221F1B),
    SurfaceContainerHigh = Color(0xFF2D2925),
    SurfaceContainerHighest = Color(0xFF383430),
    OnSurface = Color(0xFFEBE1D9),
    OnSurfaceVariant = Color(0xFFD0C4B8),
    Muted = Color(0xFFA89B8F),
    Dim = Color(0xFF8A7D72),
    Copper = Color(0xFFFFB787),
    OnCopper = Color(0xFF4D2600),
    CopperContainer = Color(0xFF6D3A10),
    OnCopperContainer = Color(0xFFFFDCC4),
    Teal = Color(0xFF8FD8C6),
    Amber = Color(0xFFFFC46B),
    DiffAddFg = Color(0xFF9CE0A8), DiffAddBg = Color(0xFF1F3B2C),
    DiffDelFg = Color(0xFFFFB4A6), DiffDelBg = Color(0xFF3D2320),
    light = false,
)

/**
 * 照着 Gemini 手机版调的浅色。
 *
 * ⚠️ **这套是按用户给的截图取的**，几个特征值：
 *   · 纯白页面底，卡片/气泡是那种偏蓝的浅灰 `#F0F4F9`（Gemini 的招牌色）
 *   · 行内代码比卡片再深一档 `#E9EEF6`，圆角药丸 —— 截图里最显眼的就是这个
 *   · 强调色是 Google 蓝 `#0B57D0`
 *
 * ⚠️ **终端不跟着变浅。** ANSI 彩色输出是按深底配的，浅底上黄色/亮绿几乎看不见。
 * 所以终端永远深底（见 [app.yxi.ui.Workspace] 里传给 `TerminalView` 的那两个颜色），
 * 代价是浅色主题下切到终端会有一下明暗跳变 —— 这是自觉的取舍，不是 bug。
 */
val GeminiPalette = Palette(
    Surface = Color(0xFFFFFFFF),
    SurfaceContainerLowest = Color(0xFFF8FAFD),
    SurfaceContainerLow = Color(0xFFF0F4F9),
    SurfaceContainer = Color(0xFFF0F4F9),
    SurfaceContainerHigh = Color(0xFFE9EEF6),
    SurfaceContainerHighest = Color(0xFFDDE3EA),
    OnSurface = Color(0xFF1F1F1F),
    OnSurfaceVariant = Color(0xFF3C4043),
    // ⚠️ 这两个别照抄 Google 在**白底**上的取值。卡片底是 #F0F4F9，
    // 原来给 Dim 用的 #80868B 落在上面对比度只有 2.9:1 —— 提示文字直接糊了。
    // 实测调到下面这两档才读得清。**浅色主题最容易翻车的就是次要文字。**
    Muted = Color(0xFF3C4043),
    Dim = Color(0xFF5F6368),
    Copper = Color(0xFF0B57D0),                 // 主操作 = Google 蓝
    OnCopper = Color(0xFFFFFFFF),
    // ⚠️ 这个色同时是「你的消息气泡」和「选中的胶囊」。
    // 取成跟卡片一样的 #F0F4F9 的话，**选中态就消失了** —— 截图上两个胶囊长得一模一样。
    // Gemini 选中态用的正是这个浅蓝，一举两得。
    CopperContainer = Color(0xFFD3E3FD),
    OnCopperContainer = Color(0xFF041E49),
    Teal = Color(0xFF0B8043),
    Amber = Color(0xFFE37400),                  // 「需要你」仍然独占一个色相
    DiffAddFg = Color(0xFF0B8043), DiffAddBg = Color(0xFFE6F4EA),
    DiffDelFg = Color(0xFFC5221F), DiffDelBg = Color(0xFFFCE8E6),
    light = true,
)

/** ⚠️ `static`：配色不会一帧一帧地变，用 static 版省掉大量无谓重组。 */
val LocalPalette = staticCompositionLocalOf { YxiPalette }
