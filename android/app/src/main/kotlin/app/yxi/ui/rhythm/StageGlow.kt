package app.yxi.ui.rhythm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max
import kotlin.math.min

/**
 * 舞台底光 —— 对话页「模型在思考」那套光（[app.yxi.ui.ThinkingGlow]）的移植，老板 2026-09-06：
 * 「背景学习对话里模型在工作思考时那个流动的渐变」。规格见 design/rhythm-spec.md §3。
 *
 * 骨架照 ThinkingGlow.kt 抄：
 *  · 四团径向光各自独立相位：7.3 / 9.1 / 11.7 / 13.9 秒的三角波（周期互不成整数倍，不然是一块色板在平移）
 *  · 色相 6 秒一圈：蓝(210°)→青→绿→黄→橙→粉，**递减** 300°；四团各错 22°
 *  · 四团透明度 .34 / .26 / .20 / .18 × amount × gain
 * 按深底横屏改的两处（原数是浅色竖屏的，搬过来四团全叠在一起糊成一块灰，试验台截图看到的）：
 *  · 饱和 70% / 明度 58%（不是粉彩 .62 / .80）
 *  · 半径半个屏宽（不是整个宽度），四团在高度上散开
 *
 * 亮度语义 = App 自己的：待机 .55 → 思考 1.0，映射成连击（宿主算 amount）。跳档那下叠一层琥珀（warmK）。
 * ⚠️ 全部按**时间**算，不按帧（#TROUBLESHOOTING 衰减那条）。
 */
object StageGlow {
    private val AMBER = floatArrayOf(38f, 22f, 45f, 30f)

    /** 0→1→0 三角波，周期 [ms] 毫秒 */
    private fun tri(t: Float, ms: Float): Float { val p = (t * 1000f / ms) % 2f; return if (p < 1f) p else 2f - p }

    /** HSL(h, .70, .58) → Color。跟试验台 `hslA` 一个算法 */
    private fun hsl(h0: Float, a: Float): Color {
        val h = ((h0 % 360f) + 360f) % 360f
        val s = 0.70f; val l = 0.58f
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f); h < 120f -> Triple(x, c, 0f); h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c); h < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m, a.coerceIn(0f, 1f))
    }

    /**
     * @param t 秒（单调时钟）
     * @param amount 亮度 .55（待机）~ 1.0+（思考 + 跳档台阶 + 闪）
     * @param warmK 0..1，跳档那下琥珀叠加的强度
     * @param gain 老板调的「底光亮度」，默认 1.75
     */
    fun DrawScope.drawStageGlow(t: Float, amount: Float, warmK: Float, gain: Float) {
        val w = size.width; val h = size.height
        drawRect(Color(0xFF0B0D12), Offset.Zero, Size(w, h))
        val pa = tri(t, 7300f); val pb = tri(t, 9100f); val pc = tri(t, 11700f); val pd = tri(t, 13900f)
        val base = 210f - 300f * ((t / 6f) % 1f)
        // 四团：中心 x / 中心 y / 半径 / 透明度（照试验台抄）
        val blobs = arrayOf(
            floatArrayOf(w * (0.10f + 0.55f * pa), h * (0.10f + 0.45f * pc), w * (0.50f + 0.10f * pb), 0.34f),
            floatArrayOf(w * (0.95f - 0.60f * pb), h * (0.20f + 0.50f * pd), w * (0.44f + 0.10f * pc), 0.26f),
            floatArrayOf(w * (0.30f + 0.50f * pc), h * (0.35f + 0.45f * pa), w * (0.40f + 0.12f * pd), 0.20f),
            floatArrayOf(w * (0.70f - 0.55f * pd), h * (0.05f + 0.50f * pb), w * (0.42f + 0.10f * pa), 0.18f),
        )
        fun paint(hueOf: (Int) -> Float, k: Float) {
            if (k <= 0f) return
            blobs.forEachIndexed { i, b ->
                val a = (b[3] * amount * gain * k).coerceIn(0f, 1f)
                if (a <= 0.002f) return@forEachIndexed
                val hue = hueOf(i)
                drawRect(
                    Brush.radialGradient(listOf(hsl(hue, a), hsl(hue, 0f)), center = Offset(b[0], b[1]), radius = max(1f, b[2])),
                    Offset.Zero, Size(w, h),
                )
            }
        }
        paint({ i -> base + i * 22f }, 1f)                   // 底色照走
        paint({ i -> AMBER[i] }, min(1f, warmK * 1.3f))     // 跳档：琥珀**叠**上去闪一下（交叉淡出会跟青色混成灰）
        drawRect(Color(0xFF080A0E).copy(alpha = .25f), Offset.Zero, Size(w, h))   // 压一层，音符才跳得出来
    }
}
