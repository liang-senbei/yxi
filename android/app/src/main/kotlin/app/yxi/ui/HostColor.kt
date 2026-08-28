package app.yxi.ui

import androidx.compose.ui.graphics.Color

/**
 * 每台主机一个**稳定强调色**（从 id 派生）—— 同一台机器在看板/主机列表里始终一个颜色，
 * 多机时一眼辨得出「这是哪台」。饱和度/明度压住，深浅两个主题下都不刺眼。
 */
fun hostColor(hostId: String): Color {
    val hue = ((hostId.hashCode().toLong() and 0xFFFFFFFFL).toFloat()) % 360f
    return Color.hsv(hue, 0.5f, 0.72f)
}
