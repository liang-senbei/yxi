package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「癫狂」背景 C 组（11–15）：电路板 / 万花筒 / 极光 / 字符雨 / 复古地平线网格。
 */
object WildBgC {

    /* 万花筒调色板 */
    private val KL = intArrayOf(
        0xFFFF3B7A.toInt(), 0xFF7B61FF.toInt(), 0xFF2EC4F5.toInt(),
        0xFFFFE733.toInt(), 0xFF5CE65C.toInt(), 0xFFFF9F1C.toInt()
    )
    private fun kc(i: Int, a: Float) =
        Color(KL[((i % KL.size) + KL.size) % KL.size]).copy(alpha = a)

    /* 极光调色板 */
    private val AU = intArrayOf(
        0xFF00FF87.toInt(), 0xFF00D4FF.toInt(), 0xFFBB66FF.toInt(), 0xFFFF6EB4.toInt()
    )

    fun DrawScope.draw(style: Int, t: Float, beat: Float, energy: Float, calm: Boolean) {
        val w = size.width; val h = size.height
        when (style) {

            11 -> { /* 电路板：暗底青光，走线 + 节点 + 流动光点 */
                drawRect(Color(0xFF080D12), Offset.Zero, Size(w, h))
                val step = h * 0.058f
                val tc = Color(0xFF0E3838); val gc = Color(0xFF00E5CC)
                val nr = (h / step).toInt(); val nc = (w / step).toInt()
                /* 走线（~50 % 行、~67 % 列亮） */
                for (r in 0..nr) {
                    if ((r * 7 + 3) % 4 < 2) continue
                    drawLine(tc, Offset(0f, r * step), Offset(w, r * step), 2f)
                }
                for (c in 0..nc) {
                    if ((c * 5 + 1) % 3 == 0) continue
                    drawLine(tc, Offset(c * step, 0f), Offset(c * step, h), 2f)
                }
                /* 节点小圆 */
                for (r in 0..nr) { if ((r * 7 + 3) % 4 < 2) continue
                    for (c in 0..nc) { if ((c * 5 + 1) % 3 == 0) continue
                        if ((r * 3 + c * 7) % 5 != 0) continue
                        drawCircle(gc.copy(alpha = 0.2f + 0.15f * beat), step * 0.12f,
                            Offset(c * step, r * step))
                    }
                }
                /* 光点沿走线流动 — 横 */
                val spd = if (calm) 40f else 80f + 200f * energy
                for (r in 0..nr) { if ((r * 7 + 3) % 4 < 2) continue
                    val ry = r * step
                    for (d in 0..2) {
                        val ph = ((r * 37 + d * 113) % 1000) / 1000f
                        val fx = ((t * spd * (0.6f + ph * 0.8f) + ph * w * 3f)
                            % (w * 1.3f)) - w * 0.15f
                        drawCircle(gc.copy(alpha = 0.55f + 0.35f * beat),
                            step * 0.09f, Offset(fx, ry))
                        drawCircle(gc.copy(alpha = 0.10f),
                            step * 0.35f, Offset(fx, ry))
                    }
                }
                /* 光点沿走线流动 — 纵 */
                for (c in 0..nc) { if ((c * 5 + 1) % 3 == 0) continue
                    val cx = c * step
                    for (d in 0..1) {
                        val ph = ((c * 53 + d * 79) % 1000) / 1000f
                        val fy = ((t * spd * 0.7f * (0.5f + ph) + ph * h * 2f)
                            % (h * 1.3f)) - h * 0.15f
                        drawCircle(gc.copy(alpha = 0.45f + 0.3f * beat),
                            step * 0.08f, Offset(cx, fy))
                        drawCircle(gc.copy(alpha = 0.08f),
                            step * 0.3f, Offset(cx, fy))
                    }
                }
            }

            12 -> { /* 万花筒：10 扇区旋转镜像，几何图形脉动 */
                drawRect(Color(0xFF08081A), Offset.Zero, Size(w, h))
                val cx = w / 2f; val cy = h / 2f
                val ns = 10; val sa = 360f / ns
                val rot = t * (if (calm) 5f else 12f)
                val pulse = 0.85f + 0.3f * beat
                /* 扇区分界线（极淡白线） */
                for (s in 0 until ns) {
                    val ang = Math.toRadians((s * sa + rot).toDouble())
                    val ex = cx + h * 0.8f * cos(ang).toFloat()
                    val ey = cy + h * 0.8f * sin(ang).toFloat()
                    drawLine(Color.White.copy(alpha = 0.04f),
                        Offset(cx, cy), Offset(ex, ey), 1f)
                }
                /* 每扇区的图形 */
                for (s in 0 until ns) {
                    rotate(s * sa + rot, Offset(cx, cy)) {
                        /* 内三角 */
                        val r1 = h * 0.12f * pulse; val r2 = h * 0.25f * pulse
                        val tri = Path().apply {
                            moveTo(cx, cy + r1)
                            lineTo(cx - h * 0.025f, cy + r2)
                            lineTo(cx + h * 0.025f, cy + r2); close()
                        }
                        drawPath(tri, kc(s, 0.35f))
                        /* 中圆点 */
                        drawCircle(kc(s + 2, 0.5f),
                            h * 0.012f * pulse, Offset(cx, cy + h * 0.2f * pulse))
                        /* 外菱形 */
                        val r3 = h * 0.35f * pulse; val ds = h * 0.018f
                        val dm = Path().apply {
                            moveTo(cx, cy + r3 - ds)
                            lineTo(cx + ds * 0.7f, cy + r3)
                            lineTo(cx, cy + r3 + ds)
                            lineTo(cx - ds * 0.7f, cy + r3); close()
                        }
                        drawPath(dm, kc(s + 4, 0.3f))
                        /* 外环圆点对 */
                        val or4 = h * 0.42f * pulse
                        drawCircle(kc(s + 1, 0.4f), h * 0.008f,
                            Offset(cx + h * 0.015f, cy + or4))
                        drawCircle(kc(s + 3, 0.4f), h * 0.008f,
                            Offset(cx - h * 0.015f, cy + or4))
                    }
                }
                /* 中心辉光 */
                drawCircle(Color.White.copy(alpha = 0.06f + 0.05f * beat),
                    h * 0.08f, Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = 0.15f + 0.10f * beat),
                    h * 0.025f, Offset(cx, cy))
            }

            13 -> { /* 极光：上半屏彩色光带飘动，下半屏深色星空 */
                drawRect(Color(0xFF060610), Offset.Zero, Size(w, h))
                /* 星点 */
                for (i in 0 until 45) {
                    val sx = ((i * 127 + 53) % 1000) / 1000f * w
                    val sy = h * 0.45f + ((i * 89 + 31) % 1000) / 1000f * h * 0.55f
                    val tw = (sin(t * (if (calm) 0.5f else 1.5f) + i * 0.7f) + 1f) / 2f
                    drawCircle(Color.White.copy(alpha = 0.25f + 0.4f * tw),
                        1.5f + tw, Offset(sx, sy))
                }
                /* 极光带（正弦叠加 → 柔和飘动） */
                for (b in 0 until 4) {
                    val baseY = h * (0.08f + b * 0.085f)
                    val amp = h * (0.035f + 0.02f * sin(t * 0.3f + b * 1.5f))
                    val f1 = 0.003f + b * 0.001f
                    val f2 = 0.007f + b * 0.0015f
                    val p1 = t * (0.4f + b * 0.15f)
                    val p2 = t * (0.25f + b * 0.1f)
                    val ba = 0.12f + 0.08f * beat
                    val bandC = Color(AU[b])
                    for (s in 0 until 25) {
                        val x = w * s / 24f
                        val sy = baseY +
                            amp * sin(x * f1 + p1) +
                            amp * 0.6f * sin(x * f2 + p2)
                        drawCircle(bandC.copy(alpha = ba * 0.35f),
                            h * 0.11f, Offset(x, sy))
                        drawCircle(bandC.copy(alpha = ba),
                            h * 0.05f, Offset(x, sy))
                    }
                }
            }

            14 -> { /* 字符雨：竖列方块 / 短线下落，头亮尾暗 */
                drawRect(Color(0xFF0A0A0A), Offset.Zero, Size(w, h))
                val colW = w * 0.055f; val nc = (w / colW).toInt()
                val spd = if (calm) 80f else 150f + 300f * energy
                val hc = Color(0xFF00FFB0); val bc = Color(0xFF009966)
                for (c in 0 until nc) {
                    val cx = c * colW + colW * 0.5f
                    val cs = spd * (0.5f + ((c * 73 + 17) % 100) / 100f)
                    for (s in 0..1) {
                        val so = ((c * 41 + s * 197) % 1000) / 1000f * h * 2f
                        val headY = ((t * cs + so) % (h * 1.8f)) - h * 0.3f
                        val tl = 6 + ((c * 31 + s * 53) % 5)
                        val bh = colW * 0.65f; val gap = colW * 0.3f
                        /* 头部辉光 */
                        if (headY in 0f..h)
                            drawCircle(hc.copy(alpha = 0.15f), colW * 1.2f, Offset(cx, headY))
                        for (i in 0 until tl) {
                            val by = headY - i * (bh + gap)
                            if (by < -bh || by > h) continue
                            val fade = 1f - i / tl.toFloat()
                            val color = if (i == 0) hc.copy(alpha = 0.9f)
                                        else bc.copy(alpha = fade * 0.65f)
                            if ((c + i) % 3 == 0)
                                drawLine(color, Offset(cx, by),
                                    Offset(cx, by + bh * 0.8f), colW * 0.22f)
                            else
                                drawRect(color, Offset(cx - colW * 0.18f, by),
                                    Size(colW * 0.36f, bh * 0.55f))
                        }
                    }
                }
            }

            15 -> { /* 复古地平线网格（synthwave）：透视网格 + 太阳 + 紫粉青 */
                drawRect(Color(0xFF0D0015), Offset.Zero, Size(w, h))
                val hz = h * 0.48f; val depth = h - hz
                /* 天空渐层 */
                drawRect(Color(0xFF1A0035).copy(alpha = 0.5f), Offset.Zero, Size(w, hz))
                /* 星 */
                for (i in 0 until 25) {
                    val sx = ((i * 151 + 37) % 1000) / 1000f * w
                    val sy = ((i * 83 + 59) % 1000) / 1000f * hz * 0.85f
                    drawCircle(Color.White.copy(alpha = 0.4f + 0.3f * sin(t + i.toFloat())),
                        1.2f, Offset(sx, sy))
                }
                /* 太阳（外辉 → 主体 → 暖心） */
                val sunCx = w / 2f; val sunCy = hz - h * 0.07f
                val sunR = h * 0.13f + h * 0.008f * beat
                drawCircle(Color(0xFFFF2E6A).copy(alpha = 0.06f),
                    sunR * 2.5f, Offset(sunCx, sunCy))
                drawCircle(Color(0xFFFF2E6A).copy(alpha = 0.12f),
                    sunR * 1.6f, Offset(sunCx, sunCy))
                drawCircle(Color(0xFFFF6E4A), sunR, Offset(sunCx, sunCy))
                drawCircle(Color(0xFFFFAA44).copy(alpha = 0.5f),
                    sunR * 0.55f, Offset(sunCx, sunCy))
                /* 太阳横条纹（扫描线切割） */
                for (i in 0 until 7) {
                    val sy = sunCy + sunR * (-0.6f + i * 0.2f)
                    val dy = sy - sunCy; val d2 = sunR * sunR - dy * dy
                    if (d2 > 0f) {
                        val hw = sqrt(d2)
                        val sh = sunR * (0.025f + 0.03f * i / 7f)
                        drawRect(Color(0xFF0D0015),
                            Offset(sunCx - hw, sy - sh / 2f), Size(hw * 2f, sh))
                    }
                }
                /* 地面 + 地平线辉光 */
                drawRect(Color(0xFF0D0015), Offset(0f, hz), Size(w, depth))
                drawRect(Color(0xFFFF6EC7).copy(alpha = 0.12f + 0.06f * beat),
                    Offset(0f, hz - 3f), Size(w, 6f))
                /* 透视横线（带前滚） */
                val ga = 0.45f + 0.2f * beat
                val gridC = Color(0xFF9B30FF)
                val scrollSpd = if (calm) 15f else 40f + 80f * energy
                val scrollPh = ((t * scrollSpd) / depth) % 1f
                for (i in 0 until 18) {
                    val k = ((i / 18f + scrollPh) % 1f)
                    val ly = hz + depth * k * k
                    drawLine(gridC.copy(alpha = ga * (0.2f + 0.8f * k)),
                        Offset(0f, ly), Offset(w, ly), 1.2f + k)
                }
                /* 透视纵线（从灭点辐射） */
                val vn = 17
                for (i in 0 until vn) {
                    val bx = w * i / (vn - 1).toFloat()
                    drawLine(gridC.copy(alpha = ga * 0.6f),
                        Offset(w / 2f, hz), Offset(bx, h), 1f)
                }
            }
        }
    }
}
