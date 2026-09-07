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
 * 「癫狂」背景 A 组（style 1..5）——精修版。
 * 签名与 WildBg 对齐；style 1~4 是原四种的精修，5 是新增棋盘格透视。
 */
object WildBgA {
    private val RAINBOW = intArrayOf(
        0xFFFF3B5C.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE733.toInt(),
        0xFF5CE65C.toInt(), 0xFF2EC4F5.toInt(), 0xFF7B61FF.toInt(), 0xFFFF5CE1.toInt()
    )
    private fun rb(i: Int, a: Float = 1f) =
        Color(RAINBOW[((i % RAINBOW.size) + RAINBOW.size) % RAINBOW.size]).copy(alpha = a)

    fun DrawScope.draw(style: Int, t: Float, beat: Float, energy: Float, calm: Boolean) {
        val w = size.width; val h = size.height
        when (style) {
            1 -> drawHalftone(w, h, t, beat, energy, calm)
            2 -> drawDualWheel(w, h, t, beat, energy, calm)
            3 -> drawSpiralTrails(w, h, t, beat, energy, calm)
            4 -> drawColorCutSweep(w, h, t, beat, energy, calm)
            5 -> drawCheckerPerspective(w, h, t, beat, energy, calm)
        }
    }

    // ── 1 黑白网点：同心波纹排布，大小渐变，慢漂移 ──────────────────────
    private fun DrawScope.drawHalftone(
        w: Float, h: Float, t: Float, beat: Float, energy: Float, calm: Boolean
    ) {
        drawRect(Color(0xFFF0F0F0), Offset.Zero, Size(w, h))
        val step = h * 0.05f
        val cx = w / 2f; val cy = h / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        val ox = (t * step * 0.4f) % step
        val oy = (t * step * 0.25f) % step
        val rippleSpd = if (calm) 0.3f else 0.8f + 0.5f * energy
        var py = -step
        while (py < h + step) {
            var px = -step
            while (px < w + step) {
                val dx = px + ox - cx; val dy = py + oy - cy
                val dist = sqrt(dx * dx + dy * dy)
                val norm = dist / maxDist
                val ripple = 0.5f + 0.5f * sin(norm * 12f - t * rippleSpd * 4f)
                val breathe = 0.8f + 0.4f * (1f - beat)
                val r = step * (0.15f + 0.25f * norm) * ripple * breathe
                drawCircle(
                    Color(0xFF111111).copy(alpha = 0.7f + 0.3f * ripple),
                    r, Offset(px + ox, py + oy)
                )
                px += step
            }
            py += step
        }
    }

    // ── 2 双层彩虹射线轮 + 中心软光圈 ──────────────────────────────────
    private fun DrawScope.drawDualWheel(
        w: Float, h: Float, t: Float, beat: Float, energy: Float, calm: Boolean
    ) {
        drawRect(Color(0xFF07070B), Offset.Zero, Size(w, h))
        val cx = w / 2f; val cy = h / 2f; val rr = maxOf(w, h)
        val spd = 30f + 50f * energy
        // 外层 28 条顺时针
        rotate(t * spd, Offset(cx, cy)) {
            for (i in 0 until 28) {
                val a0 = Math.toRadians((i * 360.0 / 28))
                val a1 = Math.toRadians((i * 360.0 / 28 + 360.0 / 28 * 0.5))
                val p = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx + rr * cos(a0).toFloat(), cy + rr * sin(a0).toFloat())
                    lineTo(cx + rr * cos(a1).toFloat(), cy + rr * sin(a1).toFloat())
                    close()
                }
                drawPath(p, rb(i, 0.45f + 0.2f * beat))
            }
        }
        // 内层 20 条逆时针，错 3 色
        val innerR = rr * 0.55f
        rotate(-t * spd * 0.7f, Offset(cx, cy)) {
            for (i in 0 until 20) {
                val a0 = Math.toRadians((i * 360.0 / 20))
                val a1 = Math.toRadians((i * 360.0 / 20 + 360.0 / 20 * 0.45))
                val p = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx + innerR * cos(a0).toFloat(), cy + innerR * sin(a0).toFloat())
                    lineTo(cx + innerR * cos(a1).toFloat(), cy + innerR * sin(a1).toFloat())
                    close()
                }
                drawPath(p, rb(i + 3, 0.35f + 0.25f * beat))
            }
        }
        // 中心软光圈：多层半透明叠出柔光
        val g = h * (0.08f + 0.04f * beat)
        drawCircle(Color(0xFF07070B).copy(alpha = 0.3f), g * 2.5f, Offset(cx, cy))
        drawCircle(Color(0xFF07070B).copy(alpha = 0.5f), g * 1.8f, Offset(cx, cy))
        drawCircle(Color(0xFF07070B).copy(alpha = 0.7f), g * 1.3f, Offset(cx, cy))
        drawCircle(Color(0xFF07070B), g, Offset(cx, cy))
        drawCircle(rb((t * 0.5f).toInt(), 0.15f + 0.1f * beat), g * 1.1f, Offset(cx, cy))
    }

    // ── 3 螺旋点阵 + 尾迹 + 渐变色 ────────────────────────────────────
    private fun DrawScope.drawSpiralTrails(
        w: Float, h: Float, t: Float, beat: Float, energy: Float, calm: Boolean
    ) {
        drawRect(Color(0xFF0A0A12), Offset.Zero, Size(w, h))
        val cx = w / 2f; val cy = h / 2f
        val n = 180; val reach = maxOf(w, h) * 0.75f
        val sm = if (calm) 0.5f else 1f
        val trailAlpha = floatArrayOf(0.85f, 0.45f, 0.2f)
        val trailScale = floatArrayOf(1f, 0.7f, 0.4f)
        for (i in 0 until n) {
            for (tr in 0..2) {
                val tOff = t * sm - tr * 0.04f
                var k = ((i / n.toFloat()) + tOff * 0.12f) % 1f
                if (k < 0f) k += 1f
                val ang = i * 0.55f + tOff * 1.4f
                val rad = k * reach
                val x = cx + rad * cos(ang)
                val y = cy + rad * sin(ang) * 0.62f
                val dotR = h * (0.005f + 0.014f * k) * (0.8f + 0.4f * beat) * trailScale[tr]
                drawCircle(rb(i / 6 + tr, trailAlpha[tr]), dotR, Offset(x, y))
            }
        }
    }

    // ── 4 纯色硬切 + 对比色横带扫过 ────────────────────────────────────
    private fun DrawScope.drawColorCutSweep(
        w: Float, h: Float, t: Float, beat: Float, energy: Float, calm: Boolean
    ) {
        val freq = if (calm) 0.5f else 2.5f
        val phase = t * freq
        val i = phase.toInt()
        val frac = phase - i
        drawRect(rb(i), Offset.Zero, Size(w, h))
        // 换色后 0.15 秒内，对比色横带扫过
        val sweepFrac = 0.15f * freq // 占一个色周期的比例
        if (frac < sweepFrac) {
            val progress = frac / sweepFrac
            val bandH = h * 0.08f
            val bandY = progress * (h + bandH) - bandH
            drawRect(rb(i + 3, 1f - progress * 0.3f), Offset(0f, bandY), Size(w, bandH))
        }
        // 常驻次色条带
        drawRect(rb(i + 3, 0.4f), Offset(0f, h * 0.36f), Size(w, h * 0.28f))
    }

    // ── 5 棋盘格透视滚动（retro 风）─────────────────────────────────────
    private fun DrawScope.drawCheckerPerspective(
        w: Float, h: Float, t: Float, beat: Float, energy: Float, calm: Boolean
    ) {
        drawRect(Color(0xFF0C0618), Offset.Zero, Size(w, h))
        val horizon = h * 0.38f
        val scrollSpd = if (calm) 0.3f else 0.6f + 0.4f * energy
        val rows = 18; val baseCols = 12
        val scrollOff = (t * scrollSpd) % 2f
        for (row in 0 until rows) {
            val rowNorm = row.toFloat() / rows
            val persp = rowNorm * rowNorm
            val rowTop = horizon + (h - horizon) * (row.toFloat() / rows)
            val rowBot = horizon + (h - horizon) * ((row + 1f) / rows)
            val fog = 0.15f + 0.85f * persp
            val shift = (scrollOff * (1f + persp * 2f)).toInt()
            val colW = w / (baseCols - 4f * (1f - persp))
            val numCols = (w / colW + 2).toInt()
            for (col in 0 until numCols) {
                val light = (col + row + shift) % 2 == 0
                val base = if (light) Color(0xFF2A1B4E) else Color(0xFF150D2A)
                val a = fog * (0.85f + if (light) beat * 0.15f * fog else 0f)
                drawRect(base.copy(alpha = a), Offset(col * colW - colW * 0.5f, rowTop), Size(colW + 1f, rowBot - rowTop + 1f))
            }
        }
        // 地平线辉光
        val ga = 0.3f + 0.15f * beat
        drawRect(Color(0xFF7B61FF).copy(alpha = ga), Offset(0f, horizon - 2f), Size(w, 4f))
        drawRect(Color(0xFF7B61FF).copy(alpha = ga * 0.4f), Offset(0f, horizon - 6f), Size(w, 12f))
        // 透视消失线
        val vx = w / 2f
        for (i in 0..baseCols) {
            val bx = i * w / baseCols
            val tx = vx + (bx - vx) * 0.3f
            drawPath(Path().apply { moveTo(tx, horizon); lineTo(bx, h) },
                Color(0xFF7B61FF).copy(alpha = 0.08f + 0.04f * beat))
        }
    }
}
