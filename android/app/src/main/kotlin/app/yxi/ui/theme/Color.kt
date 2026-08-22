package app.yxi.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 方向 B · Material 3 深色（暖/铜色源）。取值见 PRD 附录 J.1。
 *
 * M3 的核心是【用面的明度分层代替描边】—— 全 app 不用 1px 边框，
 * 靠 surface 容器逐级提亮拉开层次。改配色只改这一个文件。
 */
// 面：逐级提亮
val Surface              = Color(0xFF16130F)   // 页面底
val SurfaceContainerLowest = Color(0xFF100E0B) // 终端底、代码块
val SurfaceContainerLow  = Color(0xFF1E1B17)   // 卡片
val SurfaceContainer     = Color(0xFF221F1B)   // 控件、输入框
val SurfaceContainerHigh = Color(0xFF2D2925)   // 行内代码、抬起
val SurfaceContainerHighest = Color(0xFF383430)

// 字
val OnSurface        = Color(0xFFEBE1D9)
val OnSurfaceVariant = Color(0xFFD0C4B8)
val Muted            = Color(0xFFA89B8F)
val Dim              = Color(0xFF8A7D72)

// 强调：三个同亮度同彩度、只变色相
val Copper           = Color(0xFFFFB787)   // 主操作、你的消息
val OnCopper         = Color(0xFF4D2600)
val CopperContainer  = Color(0xFF6D3A10)
val OnCopperContainer= Color(0xFFFFDCC4)
val Teal             = Color(0xFF8FD8C6)   // 干活中、Edit、SFTP
val Amber            = Color(0xFFFFC46B)   // ⚠️ 只在「需要你动手」时出现，别挪作它用

// diff / 状态
val DiffAddFg = Color(0xFF9CE0A8);  val DiffAddBg = Color(0xFF1F3B2C)
val DiffDelFg = Color(0xFFFFB4A6);  val DiffDelBg = Color(0xFF3D2320)
