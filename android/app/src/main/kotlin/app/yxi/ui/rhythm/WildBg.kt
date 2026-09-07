package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「癫狂」难度的背景（老板 2026-09-07 傍晚给的 I Wanna 视频：黑白网点、彩虹射线轮、螺旋点阵、纯色硬切）。
 * 谱面 `stage` 关键帧里 `bg` 切到几号就画几号；0 = 平常的底光（StageGlow），这里只管 1 起。
 * 全部按时间算、不按帧；颜色是常量（舞台固定深色，#14）。
 */
object WildBg {
    private val RAINBOW = intArrayOf(0xFFFF3B5C.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE733.toInt(), 0xFF5CE65C.toInt(), 0xFF2EC4F5.toInt(), 0xFF7B61FF.toInt(), 0xFFFF5CE1.toInt())
    private fun rb(i: Int, a: Float = 1f) = Color(RAINBOW[((i % RAINBOW.size) + RAINBOW.size) % RAINBOW.size]).copy(alpha = a)

    /**
     * @param style 1 网点 / 2 射线轮 / 3 螺旋点阵 / 4 纯色闪切；[t] 秒；[beat] 拍相位 0..1（踩拍用）
     * @param calm 用户关了「闪屏 / 抖动」或系统减弱动效：会自闪的样式（4）放慢到 0.5Hz —— 这层闪是客户端自己产生的，谱面断言看不见，
     *             所以门槛钉在这里：默认 2.5Hz（光敏阈值 3Hz 以下），calm 时 0.5Hz（Entertainment 09-07 指出的）
     */
    fun DrawScope.drawWildBg(style: Int, t: Float, beat: Float, energy: Float, calm: Boolean = false) {
        val w = size.width; val h = size.height
        when (style) {
            1 -> {                                                  // 黑白网点：白底黑点，点的大小随拍子呼吸，整片慢慢平移
                drawRect(Color(0xFFF2F2F2), Offset.Zero, Size(w, h))
                val step = h * 0.055f
                val r0 = step * (0.22f + 0.16f * (1f - beat))
                val ox = (t * step * 0.6f) % step; val oy = (t * step * 0.35f) % step
                var y = -step
                while (y < h + step) {
                    var x = -step
                    while (x < w + step) { drawCircle(Color(0xFF111111), r0, Offset(x + ox, y + oy)); x += step }
                    y += step
                }
            }
            2 -> {                                                  // 彩虹射线轮：从中心放射的彩色楔子，整轮转，能量越高转越快
                drawRect(Color(0xFF07070B), Offset.Zero, Size(w, h))
                val n = 24; val cx = w / 2f; val cy = h / 2f; val rr = maxOf(w, h)
                rotate(t * (40f + 60f * energy), Offset(cx, cy)) {
                    for (i in 0 until n) {
                        val a0 = i * 360f / n; val a1 = a0 + 360f / n * 0.55f
                        val p = Path().apply {
                            moveTo(cx, cy)
                            lineTo(cx + rr * cos(Math.toRadians(a0.toDouble())).toFloat(), cy + rr * sin(Math.toRadians(a0.toDouble())).toFloat())
                            lineTo(cx + rr * cos(Math.toRadians(a1.toDouble())).toFloat(), cy + rr * sin(Math.toRadians(a1.toDouble())).toFloat())
                            close()
                        }
                        drawPath(p, rb(i, 0.55f + 0.25f * beat))
                    }
                }
                drawCircle(Color(0xFF07070B), h * (0.06f + 0.04f * beat), Offset(cx, cy))
            }
            3 -> {                                                  // 螺旋点阵：彩色点沿螺旋往外飞
                drawRect(Color(0xFF0A0A12), Offset.Zero, Size(w, h))
                val cx = w / 2f; val cy = h / 2f; val n = 260
                for (i in 0 until n) {
                    val k = ((i / n.toFloat()) + t * 0.12f) % 1f
                    val ang = i * 0.55f + t * 1.4f
                    val rad = k * maxOf(w, h) * 0.75f
                    val x = cx + rad * cos(ang); val y = cy + rad * sin(ang) * 0.62f
                    drawCircle(rb(i / 8, 0.85f), h * (0.006f + 0.016f * k) * (0.8f + 0.4f * beat), Offset(x, y))
                }
            }
            else -> {                                               // 纯色硬切：换色频率钉死 2.5Hz（不跟 BPM 走，别超光敏阈值）；calm 时 0.5Hz
                val i = (t * (if (calm) 0.5f else 2.5f)).toInt()
                drawRect(rb(i), Offset.Zero, Size(w, h))
                drawRect(rb(i + 3), Offset(0f, h * 0.36f), Size(w, h * 0.28f))
            }
        }
    }
}
