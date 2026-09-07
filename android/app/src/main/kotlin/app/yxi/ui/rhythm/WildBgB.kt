package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 癫狂背景 #6–#10（WildBg 的续集，同一套调色板与签名）。
 */
object WildBgB {
    private val RB = intArrayOf(
        0xFFFF3B5C.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE733.toInt(),
        0xFF5CE65C.toInt(), 0xFF2EC4F5.toInt(), 0xFF7B61FF.toInt(), 0xFFFF5CE1.toInt()
    )

    private fun rb(i: Int, a: Float = 1f): Color =
        Color(RB[((i % RB.size) + RB.size) % RB.size]).copy(alpha = a.coerceIn(0f, 1f))

    /**
     * @param style 6 隧道 / 7 星海跃迁 / 8 边缘均衡器 / 9 六角蜂巢脉冲 / 10 波浪条纹
     * @param calm  同 WildBg：自闪 ≤0.5 Hz，抖动归零
     */
    fun DrawScope.draw(style: Int, t: Float, beat: Float, energy: Float, calm: Boolean) {
        val w = size.width; val h = size.height
        val b = if (calm) beat * 0.3f else beat
        val e = if (calm) energy * 0.5f else energy

        when (style) {

            6 -> {                                      // ── 隧道：同心圆环 + 圆角方框从中心扩散 ──
                drawRect(Color(0xFF06061A), Offset.Zero, Size(w, h))
                val cx = w / 2f; val cy = h / 2f
                val maxR = maxOf(w, h) * 0.85f
                val n = 16
                val spd = if (calm) 0.1f else 0.25f + 0.15f * e
                val rotSpd = if (calm) 3f else 12f
                for (i in 0 until n) {
                    val phase = ((i.toFloat() / n + t * spd) % 1f)
                    val r = phase * maxR
                    if (r < 2f) continue
                    val fade = 1f - phase
                    val thick = h * (0.004f + 0.014f * b) * (0.3f + 0.7f * fade)
                    val alpha = fade * (0.25f + 0.4f * e)
                    val ci = i + (t * 1.5f + e * 3f).toInt()
                    if (i % 2 == 0) {                   // 圆环 + 柔光层
                        drawCircle(rb(ci, alpha * 0.25f), r, Offset(cx, cy), style = Stroke(thick * 5f))
                        drawCircle(rb(ci, alpha), r, Offset(cx, cy), style = Stroke(thick))
                    } else {                             // 圆角方框 + 微旋转
                        val side = r * 1.35f; val cr = side * 0.18f
                        val tl = Offset(cx - side / 2f, cy - side / 2f)
                        val rectSz = Size(side, side); val corner = CornerRadius(cr, cr)
                        rotate(t * rotSpd + i * 8f, Offset(cx, cy)) {
                            drawRoundRect(rb(ci, alpha * 0.25f), tl, rectSz, corner, style = Stroke(thick * 5f))
                            drawRoundRect(rb(ci, alpha), tl, rectSz, corner, style = Stroke(thick))
                        }
                    }
                }
                drawCircle(rb((t * 2f).toInt(), 0.3f + 0.25f * b), h * 0.012f, Offset(cx, cy))
            }

            7 -> {                                      // ── 星海跃迁：星点从中心飞出拉成线 ──
                drawRect(Color(0xFF040410), Offset.Zero, Size(w, h))
                val cx = w / 2f; val cy = h / 2f
                // 薄雾星云底
                drawCircle(Color(0xFF1A2266).copy(alpha = 0.07f), h * 0.28f, Offset(cx * 0.6f, cy * 0.5f))
                drawCircle(Color(0xFF661A44).copy(alpha = 0.05f), h * 0.2f, Offset(cx * 1.4f, cy * 0.7f))
                drawCircle(Color(0xFF1A5566).copy(alpha = 0.05f), h * 0.25f, Offset(cx * 0.85f, cy * 1.4f))
                val spd = if (calm) 0.1f else 0.15f + 0.4f * e
                val n = 180; val diag = maxOf(w, h) * 0.72f
                for (i in 0 until n) {
                    val ang = i * 2.39996f                          // 黄金角散布
                    val k = ((i * 0.00556f + t * spd) % 1f)        // 错开相位
                    val d = k * k * diag                            // 二次方 → 近慢远快
                    val x = cx + d * cos(ang); val y = cy + d * sin(ang)
                    val trail = d * (0.03f + 0.1f * spd) * k
                    val x0 = cx + (d - trail).coerceAtLeast(0f) * cos(ang)
                    val y0 = cy + (d - trail).coerceAtLeast(0f) * sin(ang)
                    val a = (k * k * (0.3f + 0.6f * e)).coerceIn(0f, 1f)
                    val sw = h * (0.0008f + 0.005f * k * k)
                    val c = if (i % 11 == 0) Color.White else Color(0xFFCCDDFF)
                    drawLine(c.copy(alpha = a), Offset(x0, y0), Offset(x, y), strokeWidth = sw)
                }
                // 中心辉光
                drawCircle(Color(0xFF3355BB).copy(alpha = 0.12f + 0.08f * b), h * 0.06f, Offset(cx, cy))
                drawCircle(Color(0xFF5577DD).copy(alpha = 0.06f + 0.04f * b), h * 0.12f, Offset(cx, cy))
            }

            8 -> {                                      // ── 边缘均衡器：四边渐变彩柱随拍起伏 ──
                drawRect(Color(0xFF0A0A14), Offset.Zero, Size(w, h))
                val n = 16
                val maxV = h * 0.22f; val barW = w / n
                val maxS = w * 0.12f; val barH = h / n
                val osc = if (calm) 2.5f else 10f       // ~0.4 / ~1.6 Hz
                // 下
                for (i in 0 until n) {
                    val p = (sin(i * 0.85f + t * osc + b * 3f) + 1f) / 2f
                    val len = maxV * (0.12f + 0.88f * p * e)
                    val c = rb(i, 0.55f + 0.35f * b); val cd = rb(i, 0.08f)
                    drawRect(Brush.verticalGradient(listOf(cd, c), startY = h - len, endY = h),
                        Offset(i * barW + barW * 0.08f, h - len), Size(barW * 0.84f, len))
                }
                // 上
                for (i in 0 until n) {
                    val p = (sin(i * 0.85f + t * osc + b * 3f + 2f) + 1f) / 2f
                    val len = maxV * (0.12f + 0.88f * p * e)
                    val c = rb(i + 3, 0.55f + 0.35f * b); val cd = rb(i + 3, 0.08f)
                    drawRect(Brush.verticalGradient(listOf(c, cd), startY = 0f, endY = len),
                        Offset(i * barW + barW * 0.08f, 0f), Size(barW * 0.84f, len))
                }
                // 左
                for (i in 0 until n) {
                    val p = (sin(i * 0.85f + t * osc + b * 3f + 4f) + 1f) / 2f
                    val len = maxS * (0.12f + 0.88f * p * e)
                    val c = rb(i + 1, 0.5f + 0.35f * b); val cd = rb(i + 1, 0.08f)
                    drawRect(Brush.horizontalGradient(listOf(c, cd), startX = 0f, endX = len),
                        Offset(0f, i * barH + barH * 0.08f), Size(len, barH * 0.84f))
                }
                // 右
                for (i in 0 until n) {
                    val p = (sin(i * 0.85f + t * osc + b * 3f + 6f) + 1f) / 2f
                    val len = maxS * (0.12f + 0.88f * p * e)
                    val c = rb(i + 5, 0.5f + 0.35f * b); val cd = rb(i + 5, 0.08f)
                    drawRect(Brush.horizontalGradient(listOf(cd, c), startX = w - len, endX = w),
                        Offset(w - len, i * barH + barH * 0.08f), Size(len, barH * 0.84f))
                }
            }

            9 -> {                                      // ── 六角蜂巢脉冲：hex 网格 + 中心向外亮波 ──
                drawRect(Color(0xFF08081A), Offset.Zero, Size(w, h))
                val cx = w / 2f; val cy = h / 2f
                val sz = h * 0.04f
                val colStep = sz * 1.5f; val rowStep = sz * 1.732f  // flat-top hex grid
                val cols = (w / colStep).toInt() + 2
                val rows = (h / rowStep).toInt() + 2
                val diag = sqrt((cx * cx + cy * cy).toDouble()).toFloat()
                val phase = if (calm) (t * 0.4f) % 1f else beat
                val front = phase * 1.8f
                val path = Path()
                for (col in -1..cols) {
                    val hx = col * colStep
                    val yOff = if (col and 1 != 0) rowStep * 0.5f else 0f
                    for (row in -1..rows) {
                        val hy = row * rowStep + yOff
                        if (hx < -sz || hx > w + sz || hy < -sz || hy > h + sz) continue
                        val ddx = (hx - cx).toDouble(); val ddy = (hy - cy).toDouble()
                        val dist = sqrt(ddx * ddx + ddy * ddy).toFloat()
                        val nd = dist / diag
                        val ring = front - nd
                        val bright = if (ring in 0f..0.22f) (1f - ring / 0.22f) * e else 0f
                        path.reset()
                        for (k in 0..5) {
                            val a = (PI.toFloat() / 3f * k)        // flat-top vertices
                            val px = hx + sz * 0.88f * cos(a)
                            val py = hy + sz * 0.88f * sin(a)
                            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        if (bright > 0.03f) {
                            val ci = ((dist / sz).toInt()) % RB.size
                            drawPath(path, rb(ci, bright * 0.6f))
                        }
                        drawPath(path, Color(0xFF1C1C3A), style = Stroke(1.2f))
                    }
                }
            }

            else -> {                                   // ── 10 波浪条纹：正弦条带 × 2 层（海浪 / 极光） ──
                drawRect(Color(0xFF080816), Offset.Zero, Size(w, h))
                val n = 10; val seg = 24; val tau = PI.toFloat() * 2f
                val path = Path()
                for (layer in 0..1) {
                    val aBase = if (layer == 0) 0.18f else 0.38f
                    val ampM = if (layer == 0) 1.4f else 1f
                    val tM = if (layer == 0) 0.6f else 1f
                    for (i in 0 until n) {
                        val baseY = h * (i + 0.5f) / n
                        val amp = h * 0.03f * ampM * (0.5f + 0.5f * e)
                        val freq = 1.2f + i * 0.35f
                        val ph = t * (0.7f + i * 0.12f) * tM
                        val thick = h / n * 0.5f
                        path.reset()
                        for (s in 0..seg) {
                            val x = w * s / seg
                            val y = baseY - thick / 2f + amp * sin(freq * x / w * tau + ph)
                            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        for (s in seg downTo 0) {
                            val x = w * s / seg
                            val y = baseY + thick / 2f + amp * sin(freq * x / w * tau + ph + 1.2f)
                            path.lineTo(x, y)
                        }
                        path.close()
                        drawPath(path, rb(i + layer * 3, aBase + 0.2f * b))
                    }
                }
            }
        }
    }
}
