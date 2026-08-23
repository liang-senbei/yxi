package app.yxi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 界面里用到的图形，集中一处。
 *
 * ⚠️ **不用 emoji。** emoji 由系统字体渲染，后果有三个：
 *   · 各家手机长得不一样（华为的 🔔 和 Pixel 的 🔔 是两个东西）
 *   · 粗细、视觉重量跟界面其余部分对不上
 *   · **没法跟着主题变色** —— 深色界面里就是一块彩色贴纸，
 *     而且「开/关」两个状态只能靠换 emoji（🔔/🔕）表达，颜色帮不上忙
 *
 * ⚠️ `☑ ☐ ✕ ✓ ✛` 这类**单色几何符号不在此列** —— 它们跟着文字颜色走，
 * 各机型也一致，换成矢量纯属浪费。只有彩色 emoji 才需要。
 *
 * 路径统一按 24×24 画，画的时候按目标尺寸缩放。
 */
object Glyph {
    /** 加号：加附件 */
    const val Plus = "M11,5h2v14h-2z M5,11h14v2H5z"

    /** 话筒：语音输入 */
    const val Mic = "M12,3a3,3 0 0,1 3,3v6a3,3 0 0,1 -6,0V6a3,3 0 0,1 3,-3z " +
        "M5.5,11.5h1.6a4.9,4.9 0 0,0 9.8,0h1.6a6.5,6.5 0 0,1 -5.7,6.4V21h-1.6v-3.1a6.5,6.5 0 0,1 -5.7,-6.4z"

    /** 上箭头：发送 */
    const val Send = "M12,4l7,7l-1.5,1.5L13,8v12h-2V8l-4.5,4.5L5,11z"

    /** 图钉：置顶 */
    const val Pin = "M14,2l6,6l-2.2,0.6l-3.1,3.1l0.7,4.2l-1.6,1.6l-3.7,-3.7l-4.4,4.4l-1.1,-1.1" +
        "l4.4,-4.4l-3.7,-3.7l1.6,-1.6l4.2,0.7l3.1,-3.1z"

    /** 铃铛：这台机器要不要主动响 */
    const val Bell = "M12,2a1.7,1.7 0 0,1 1.7,1.7v0.6a5.6,5.6 0 0,1 4,5.4v3.9l1.6,2.3v1.1H4.7v-1.1" +
        "l1.6,-2.3V9.7a5.6,5.6 0 0,1 4,-5.4V3.7A1.7,1.7 0 0,1 12,2z " +
        "M9.8,18.3h4.4a2.2,2.2 0 0,1 -4.4,0z"

    /**
     * 铃铛加一道杠：静音。
     *
     * ⚠️ **杠必须画得够粗够长**。试过细杠，缩到 18dp 之后跟铃铛的轮廓糊在一起，
     * 开和关两个状态在手机上**看不出区别** —— 而这两个状态的后果差很远
     * （关着 = Claude 需要你时手机不会响，且没有任何提示）。
     */
    const val BellOff = Bell + " M4.3,3.1L20.9,19.7l-1.6,1.6L2.7,4.7z"
}

/** 把 24×24 的路径画成 [size] 大小，用 [tint] 上色。 */
@Composable
fun GlyphIcon(path: String, tint: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val p = PathParser().parsePathString(path).toPath()
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(p, tint) }
    }
}
