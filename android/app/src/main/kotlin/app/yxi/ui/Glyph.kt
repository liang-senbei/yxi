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

    /** 对话气泡：回它一句（会话卡上的快捷回复） */
    const val Chat = "M5,4h14a2,2 0 0,1 2,2v8a2,2 0 0,1 -2,2H11l-4,3v-3H5a2,2 0 0,1 -2,-2V6a2,2 0 0,1 2,-2z"

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

    // ── 设置页每一节的引导图标（参考款那种线性小图）──────────────
    /** 版本 / 关于：信息圆圈 */
    const val Info = "M11,7h2v2h-2z M11,11h2v6h-2z M12,2a10,10 0 1,0 0,20a10,10 0 0,0 0,-20z " +
        "M12,4a8,8 0 1,1 0,16a8,8 0 0,1 0,-16z"
    /** 公钥：钥匙 */
    const val Key = "M14,6a4,4 0 0,1 3.9,5H21v2h-2v3h-2v-3h-1.1A4,4 0 1,1 14,6z " +
        "M8,10a2,2 0 1,0 0,4a2,2 0 0,0 0,-4z"
    /** 语言：地球 */
    const val Globe = "M12,2a10,10 0 1,0 0,20a10,10 0 0,0 0,-20z M12,4c1.4,0 3.3,2.3 3.8,6H8.2C8.7,6.3 10.6,4 12,4z " +
        "M4.3,10h2.9a19,19 0 0,0 0,4H4.3a8,8 0 0,1 0,-4z M8.2,16h7.6c-0.5,3.7-2.4,6-3.8,6S8.7,19.7 8.2,16z " +
        "M16.8,14a19,19 0 0,0 0,-4h2.9a8,8 0 0,1 0,4z"
    /** 界面风格：调色板 */
    const val Palette = "M12,3a9,9 0 0,0 0,18c1.7,0 2-1.3 1.2-2.2c-0.8-0.9-0.3-2.3 1-2.3H16a5,5 0 0,0 5,-5C21,6.6 17,3 12,3z " +
        "M7.5,10a1.3,1.3 0 1,1 0,2.6a1.3,1.3 0 0,1 0,-2.6z M12,6.5a1.3,1.3 0 1,1 0,2.6a1.3,1.3 0 0,1 0,-2.6z " +
        "M16,10a1.3,1.3 0 1,1 0,2.6a1.3,1.3 0 0,1 0,-2.6z"
    /** 音游：八分音符（两个符头 + 符干 + 连线）*/
    const val Music = "M9,18a2.5,2.5 0 1,1 0,-5a2.5,2.5 0 0,1 0,5z M18,16a2.5,2.5 0 1,1 0,-5a2.5,2.5 0 0,1 0,5z " +
        "M11.5,15.5V6.6l8,-2.1v8.9h-2V7l-4,1.05v7.45z"
    /** 开发者：扳手 */
    const val Wrench = "M15,3a5,5 0 0,0 -4.5,7.2L3.3,17.4l2.3,2.3l7.2,-7.2A5,5 0 1,0 15,3z " +
        "M15,5a3,3 0 1,1 0,6a3,3 0 0,1 0,-6z"
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
