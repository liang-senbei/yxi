package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「癫狂」背景 第四组 (style 16..20)
 * 16 樱花飘落 / 17 扫描线+故障 / 18 泡泡上升 / 19 低多边形碎片 / 20 涡旋
 */
object WildBgD {

    // ── 调色板 ──────────────────────────────────────────────
    private val SAKURA_PINK  = Color(0xFFFFB7C5)
    private val SAKURA_WHITE = Color(0xFFFFF0F5)
    private val SAKURA_BG    = Color(0xFF1A0A1E)
    private val SCAN_BG      = Color(0xFF08080C)
    private val SCAN_CYAN    = Color(0xFF00F0FF)
    private val SCAN_MAGENTA = Color(0xFFFF00C8)
    private val BUBBLE_BG    = Color(0xFF050B1A)
    private val BUBBLE_BODY  = Color(0xFF4FC3F7)
    private val BUBBLE_HIGH  = Color(0xFFE0F7FA)
    private val POLY_COLORS  = intArrayOf(
        0xFF7B61FF.toInt(), 0xFF2EC4F5.toInt(), 0xFFFF5CE1.toInt(),
        0xFF5CE65C.toInt(), 0xFFFF9F1C.toInt(), 0xFFFF3B5C.toInt()
    )
    private val VORTEX_COLORS = intArrayOf(
        0xFF7B61FF.toInt(), 0xFF2EC4F5.toInt(), 0xFFFF5CE1.toInt(),
        0xFFFFE733.toInt(), 0xFF5CE65C.toInt()
    )

    // ── 简易哈希（确定性伪随机，不分配对象） ────────────────
    private fun hash(seed: Int): Float {
        val x = ((seed * 1471343 + 338557) xor (seed * 7919)) and 0x7FFFFFFF
        return (x % 10000) / 10000f
    }

    fun DrawScope.draw(style: Int, t: Float, beat: Float, energy: Float, calm: Boolean) {
        val w = size.width; val h = size.height
        when (style) {

            // ━━ 16 樱花飘落 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            16 -> {
                // 暗紫底 + 呼吸柔光
                drawRect(SAKURA_BG, Offset.Zero, Size(w, h))
                val glowA = 0.06f + 0.04f * sin(t * 0.8f) * (0.6f + 0.4f * beat)
                drawCircle(SAKURA_PINK.copy(alpha = glowA), h * 0.45f, Offset(w * 0.5f, h * 0.4f))

                val n = 40
                val petal = Path()
                for (i in 0 until n) {
                    val seed = i * 7 + 3
                    val baseX = hash(seed) * w
                    val speed = 0.04f + hash(seed + 1) * 0.06f          // 下落速度
                    val sway  = 0.012f + hash(seed + 2) * 0.015f        // 左右摆幅
                    val sz    = h * (0.012f + hash(seed + 3) * 0.016f)  // 花瓣大小
                    val phase = hash(seed + 4) * 6.28f

                    val yOff = ((t * speed * h) + hash(seed + 5) * h) % (h + sz * 4) - sz * 2
                    val xOff = baseX + sin(t * 1.2f + phase) * w * sway
                    val rot  = (t * (30f + hash(seed + 6) * 40f) + hash(seed + 7) * 360f)
                    val col  = if (i % 3 == 0) SAKURA_WHITE else SAKURA_PINK
                    val a    = (0.5f + 0.3f * beat) * (0.7f + 0.3f * hash(seed + 8))

                    // 花瓣 = 旋转的椭圆形 path（两个弧拼成叶片）
                    rotate(rot, Offset(xOff, yOff)) {
                        petal.reset()
                        petal.moveTo(xOff, yOff - sz)
                        petal.quadraticTo(xOff + sz * 0.7f, yOff, xOff, yOff + sz)
                        petal.quadraticTo(xOff - sz * 0.7f, yOff, xOff, yOff - sz)
                        drawPath(petal, col.copy(alpha = a))
                    }
                }
            }

            // ━━ 17 扫描线 + 故障 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            17 -> {
                drawRect(SCAN_BG, Offset.Zero, Size(w, h))

                // 细扫描线（半透明，慢慢往下走）
                val lineGap = h * 0.006f
                val lineH   = 1.2f
                val scanOff = (t * h * 0.04f) % lineGap
                var ly = scanOff
                val lineA = 0.12f + 0.06f * beat
                while (ly < h) {
                    drawRect(Color.White.copy(alpha = lineA), Offset(0f, ly), Size(w, lineH))
                    ly += lineGap
                }

                // 主扫描亮带（宽一点，慢速下移）
                val bandY = (t * h * 0.06f) % h
                val bandA = 0.08f + 0.05f * energy
                drawRect(Color.White.copy(alpha = bandA), Offset(0f, bandY), Size(w, h * 0.03f))

                // 故障色块：calm 时不出现；否则频率 ≤ 2Hz
                if (!calm) {
                    val glitchSlot = floor(t * 1.8f).toInt()
                    // 每 slot 用哈希决定是否出现（约 40% 概率）
                    if (hash(glitchSlot * 31) < 0.4f) {
                        val phase01 = (t * 1.8f) - glitchSlot  // 0..1 slot 内进度
                        val ga = (0.6f - phase01).coerceIn(0f, 0.5f)  // 快速淡出

                        val gy = hash(glitchSlot * 37 + 1) * h
                        val gw = w * (0.2f + hash(glitchSlot * 37 + 2) * 0.5f)
                        val gh = h * (0.01f + hash(glitchSlot * 37 + 3) * 0.04f)
                        val gx = hash(glitchSlot * 37 + 4) * (w - gw)

                        // 青 / 品红分离
                        val shift = w * 0.02f * (0.5f - hash(glitchSlot * 37 + 5))
                        drawRect(SCAN_CYAN.copy(alpha = ga * 0.7f), Offset(gx + shift, gy), Size(gw, gh))
                        drawRect(SCAN_MAGENTA.copy(alpha = ga * 0.5f), Offset(gx - shift, gy + gh * 0.4f), Size(gw, gh))
                    }
                }
            }

            // ━━ 18 泡泡上升 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            18 -> {
                // 深蓝底 + 渐变叠层
                drawRect(BUBBLE_BG, Offset.Zero, Size(w, h))
                drawRect(Color(0xFF0A1A3A).copy(alpha = 0.4f), Offset(0f, h * 0.5f), Size(w, h * 0.5f))

                val n = 35
                for (i in 0 until n) {
                    val seed = i * 13 + 7
                    val baseX  = hash(seed) * w
                    val radius = h * (0.012f + hash(seed + 1) * 0.03f)
                    val speed  = 0.03f + hash(seed + 2) * 0.04f
                    val wobble = hash(seed + 3) * 3f

                    val yy = h - ((t * speed * h + hash(seed + 4) * h) % (h + radius * 4)) + radius * 2
                    val xx = baseX + sin(t * 0.8f + wobble) * w * 0.02f

                    // 泡泡呼吸（跟拍）
                    val r = radius * (0.9f + 0.15f * beat)
                    val bodyA = 0.18f + 0.08f * beat

                    // 泡泡体
                    drawCircle(BUBBLE_BODY.copy(alpha = bodyA), r, Offset(xx, yy))
                    // 细边
                    drawCircle(BUBBLE_BODY.copy(alpha = bodyA + 0.12f), r, Offset(xx, yy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f))
                    // 高光：偏左上小圆
                    drawCircle(BUBBLE_HIGH.copy(alpha = bodyA * 0.6f), r * 0.28f,
                        Offset(xx - r * 0.3f, yy - r * 0.3f))
                }
            }

            // ━━ 19 低多边形碎片 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            19 -> {
                drawRect(Color(0xFF0C0C14), Offset.Zero, Size(w, h))

                // 用网格 + 微扰生成三角形拼片
                val cols = 8; val rows = 12
                val cw = w / cols; val rh = h / rows
                val tri = Path()

                // 为每个网格交叉点生成微扰坐标
                // 然后每个格子分成两个三角形
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val seed = r * 100 + c
                        // 四个角坐标 + 微扰
                        fun ptX(cr: Int, rr: Int): Float {
                            val base = cr * cw
                            return if (cr == 0 || cr == cols) base
                            else base + (hash(rr * 100 + cr * 7 + 1) - 0.5f) * cw * 0.35f
                        }
                        fun ptY(cr: Int, rr: Int): Float {
                            val base = rr * rh
                            return if (rr == 0 || rr == rows) base
                            else base + (hash(rr * 100 + cr * 7 + 2) - 0.5f) * rh * 0.35f
                        }

                        val x0 = ptX(c, r);     val y0 = ptY(c, r)
                        val x1 = ptX(c + 1, r); val y1 = ptY(c + 1, r)
                        val x2 = ptX(c + 1, r + 1); val y2 = ptY(c + 1, r + 1)
                        val x3 = ptX(c, r + 1); val y3 = ptY(c, r + 1)

                        // 两个三角形
                        for (half in 0..1) {
                            val tidx = seed * 2 + half
                            val baseCol = Color(POLY_COLORS[tidx % POLY_COLORS.size])
                            // 呼吸明暗：每片各自节奏
                            val breathPhase = hash(tidx * 3 + 10) * 6.28f
                            val breathSpeed = 0.4f + hash(tidx * 3 + 11) * 0.6f
                            val breath = sin(t * breathSpeed + breathPhase)
                            val a = (0.25f + 0.15f * breath + 0.1f * beat).coerceIn(0.1f, 0.6f)

                            tri.reset()
                            if (half == 0) {
                                tri.moveTo(x0, y0); tri.lineTo(x1, y1); tri.lineTo(x3, y3); tri.close()
                            } else {
                                tri.moveTo(x1, y1); tri.lineTo(x2, y2); tri.lineTo(x3, y3); tri.close()
                            }
                            drawPath(tri, baseCol.copy(alpha = a))
                        }
                    }
                }
            }

            // ━━ 20 涡旋 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            20 -> {
                drawRect(Color(0xFF06060E), Offset.Zero, Size(w, h))
                val cx = w * 0.5f; val cy = h * 0.5f
                val maxR = sqrt(w * w + h * h) * 0.55f
                val arms = 5
                val dotsPerArm = 90

                val rotSpeed = if (calm) 12f else 25f + 15f * energy

                for (arm in 0 until arms) {
                    val armAngleOff = arm * (360f / arms)
                    val armCol = Color(VORTEX_COLORS[arm % VORTEX_COLORS.size])

                    for (j in 0 until dotsPerArm) {
                        val k = j / dotsPerArm.toFloat()  // 0..1 from center out
                        val r = k * maxR
                        // 螺旋角 = 臂基角 + 展开角 + 旋转
                        val spiralAngle = armAngleOff + k * 720f + t * rotSpeed
                        val rad = Math.toRadians(spiralAngle.toDouble())
                        val px = cx + r * cos(rad).toFloat()
                        val py = cy + r * sin(rad).toFloat()

                        // 沿臂渐变 alpha：中心亮、外缘淡
                        val aBase = (1f - k * 0.7f) * (0.35f + 0.25f * beat)
                        val dotR = h * (0.004f + 0.008f * k) * (0.85f + 0.3f * beat)
                        drawCircle(armCol.copy(alpha = aBase), dotR, Offset(px, py))
                    }
                }

                // 中心亮核：跟拍呼吸
                val coreR = h * (0.025f + 0.02f * beat)
                val coreA = 0.5f + 0.3f * beat
                drawCircle(Color.White.copy(alpha = coreA * 0.3f), coreR * 2.5f, Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = coreA * 0.5f), coreR * 1.4f, Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = coreA), coreR, Offset(cx, cy))
            }
        }
    }
}
