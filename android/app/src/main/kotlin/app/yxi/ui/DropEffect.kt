package app.yxi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * 出货特效 —— 星轨 / 光爆 / 冲击波 / 粒子 / 神圣光柱。
 * 规格：`logto_yxi/design/drop-effect.md`（cc-logto_yxi 2026-09-05，老板：「多学学米哈游」）。
 *
 * ## 核心是**颜色语言**，不是堆特效
 * 星轨的颜色**在落地之前**就告诉你这一抽出了什么 —— 悬念在划过来的那 0.6 秒里，
 * 不在最后揭晓那一瞬。所以 [Tier] 的颜色**四层全用同一组**：
 * ⚠️ **只染一层就是贴了个颜色，不叫「出红光」。**
 *
 * ## 两条硬约束
 *  1. **华丽只在过程里，落定那一帧必须干净。** [DropStage] 在 [DONE] 之后一个像素都不画 ——
 *     最后停住的画面只有完整立绘 + 几行普通字，没有还在飘的粒子、没有残留的光柱。
 *     （老板同一天说过「图片下面放几行普通文字就行，不用很设计感」，跟「特效华丽一点」不冲突：
 *     炸完要收得干净。**一直炫的东西看第二遍就烦。**）
 *  2. **光柱要软。** 硬边三角形像风车不像光 —— 见 [beams] 的注释。
 *
 * ⚠️ 关了动效走 [DropStage] 的 `reduced` 分支：跳过星轨 / 光柱 / 粒子，只留光爆一下。
 */

/** 一个档位的全套颜色和参数。⚠️ 值是 cc-logto_yxi 2026-09-05 定死的，别自己调近似色。 */
internal data class Tier(
    /** 主色：星轨头部之后的本体、光爆核心 */
    val hi: Color,
    /** 次色：拖尾末端、粒子 */
    val mid: Color,
    /** 光柱条数：越稀有越多 */
    val beams: Int,
) {
    companion object {
        /**
         * ⚠️ 红的次色**不是** `#ff9a5a`。那个值暗下来之后和金的次色都变成褐橙，
         * 会把**最稀有的红认成金** —— 最不该发生的混淆。`#ff7a6a` 偏红，分得开。
         */
        fun of(rarity: String): Tier = when (rank(rarity)) {
            4 -> Tier(Color(0xFFFF5A4E), Color(0xFFFF7A6A), 14)   // 红 · 角色
            3 -> Tier(Color(0xFFFFD76A), Color(0xFFFFB03A), 10)   // 金 · 曦光
            2 -> Tier(Color(0xFFA06BFF), Color(0xFF7B4AE0), 8)    // 紫 · 稀有装扮
            else -> Tier(Color(0xFF5AA8FF), Color(0xFF3A7AD0), 6) // 蓝 · 普通装扮
        }
    }
}

/** 整段特效演完的时刻（毫秒）。到这儿之后 [DropStage] 什么都不画 —— 「落定必须干净」。 */
internal const val DONE = 3000f

/** 粒子数。预生成在 [FloatArray] 里，每帧不分配对象。 */
private const val PARTICLES = 260

/**
 * 四层特效。[ms] 是这一次表演走到第几毫秒，从 0 开始。
 *
 * @param reduced 系统关了动效：只留光爆一下，别的都跳过（`design/STYLE.md` §2.4）。
 */
@Composable
internal fun DropStage(tier: Tier, ms: Float, reduced: Boolean = false, modifier: Modifier = Modifier) {
    if (ms >= DONE) return
    // 方向 / 速度 / 半径 / 白不白 —— 一次生成，之后每帧只读
    val seeds = remember {
        FloatArray(PARTICLES * 4) { i ->
            when (i % 4) {
                0 -> (i * 2.399963f) % 6.2831855f          // 黄金角散开，不用随机数也很均匀
                1 -> 0.35f + ((i * 37) % 100) / 100f * 0.9f // 速度
                2 -> 1.2f + ((i * 53) % 100) / 100f * 2.6f  // 半径
                else -> ((i * 71) % 100) / 100f             // <1/3 的画白色
            }
        }
    }
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.46f
        if (!reduced) {
            trail(tier, ms)
            beams(tier, ms, cx, cy)
        }
        burst(tier, ms, cx, cy, reduced)
        if (!reduced) {
            shockwave(tier, ms, cx, cy)
            particles(tier, ms, cx, cy, seeds)
        }
    }
}

/** ① 星轨 120–700ms：一道拖长尾的光沿抛物线划过，头部白热、尾部档位色。 */
private fun DrawScope.trail(tier: Tier, ms: Float) {
    val p = ((ms - 120f) / 580f)
    if (p <= 0f || p >= 1f) return
    val e = p * p * (3f - 2f * p)   // smoothstep：进场快、落地稳
    val w = size.width; val h = size.height
    // 左下 → 屏心，带一点抛物线的下坠感
    val x = -w * 0.18f + e * (w * 0.68f)
    val y = h * 1.02f - e * (h * 0.56f) + (e * e) * h * 0.06f
    val fade = if (p > 0.82f) 1f - (p - 0.82f) / 0.18f else 1f
    // 拖尾占路径三成，颜色从主色渐到次色 —— 尾巴也得是档位色，不能是白的
    repeat(18) { i ->
        val k = i / 18f
        val c = androidx.compose.ui.graphics.lerp(tier.hi, tier.mid, k)
        drawCircle(
            c, (11f - k * 9f) * density,
            Offset(x - k * w * 0.30f, y + k * h * 0.24f),
            alpha = (1f - k) * 0.9f * fade, blendMode = BlendMode.Plus,
        )
    }
    drawCircle(Color.White, 7.5f * density, Offset(x, y), alpha = fade, blendMode = BlendMode.Plus)
}

/** ② 光爆 680–940ms：全屏径向白光，峰值 α .9。核心是白的，边上是档位色。 */
private fun DrawScope.burst(tier: Tier, ms: Float, cx: Float, cy: Float, reduced: Boolean) {
    // 关了动效时不等星轨，直接从 0 开始闪一下
    val from = if (reduced) 0f else 680f
    val p = ((ms - from) / 260f)
    if (p <= 0f || p >= 1f) return
    val a = (if (p < 0.35f) p / 0.35f else 1f - (p - 0.35f) / 0.65f) * 0.9f
    val r = size.maxDimension * (0.30f + p * 0.55f)
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = a), tier.hi.copy(alpha = a * 0.7f), Color.Transparent),
            center = Offset(cx, cy), radius = r,
        ),
        radius = r, center = Offset(cx, cy), blendMode = BlendMode.Plus,
    )
}

/** ③ 冲击波 680–1300ms：细环从中心扩到 0.67W，边扩边淡。 */
private fun DrawScope.shockwave(tier: Tier, ms: Float, cx: Float, cy: Float) {
    val p = ((ms - 680f) / 620f)
    if (p <= 0f || p >= 1f) return
    val r = p * size.width * 0.67f
    drawCircle(
        tier.hi, radius = r, center = Offset(cx, cy),
        alpha = (1f - p) * 0.55f, blendMode = BlendMode.Plus,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = (3.5f - p * 2.5f) * density),
    )
}

/** ④ 粒子 700–2200ms：四散 + 重力下坠，1/3 白其余档位色。 */
private fun DrawScope.particles(tier: Tier, ms: Float, cx: Float, cy: Float, seeds: FloatArray) {
    val p = ((ms - 700f) / 1500f)
    if (p <= 0f || p >= 1f) return
    val reach = size.maxDimension * 0.62f
    val fade = if (p > 0.55f) 1f - (p - 0.55f) / 0.45f else 1f
    for (i in 0 until PARTICLES) {
        val ang = seeds[i * 4]
        val spd = seeds[i * 4 + 1]
        val rad = seeds[i * 4 + 2]
        val d = p * spd * reach
        val x = cx + cos(ang) * d
        // 重力：越到后面掉得越快
        val y = cy + sin(ang) * d + p * p * size.height * 0.30f
        val c = if (seeds[i * 4 + 3] < 0.33f) Color.White else tier.mid
        drawCircle(c, rad * density * (1f - p * 0.5f), Offset(x, y), alpha = fade * 0.85f, blendMode = BlendMode.Plus)
    }
}

/**
 * ⑤ 神圣光柱 720–2600ms：一圈放射光条，慢转。
 *
 * ⚠️⚠️ **要软。** 第一版用 0.035 的窄角 + 0.30 不透明度，画出来是**硬边三角形，
 * 像风车不像光**（cc-logto_yxi 实测）。现在三层叠：
 *  · 先铺一层弥散的径向光当底 —— 没有这层，光条就是凭空长在黑底上的
 *  · 外层**宽而淡**（±0.16 rad，α .10）
 *  · 内层**窄而亮**（±0.055 rad，α .13），跟外层**错开 0.14 rad**
 * 三层都走 [BlendMode.Plus]（等价于 canvas 的 `lighter`），叠出来才是光而不是漆。
 */
private fun DrawScope.beams(tier: Tier, ms: Float, cx: Float, cy: Float) {
    val p = ((ms - 720f) / 1880f)
    if (p <= 0f || p >= 1f) return
    // 进场 0.18、退场 0.35，中间满 —— 退场留长一点，收得住
    val env = when {
        p < 0.18f -> p / 0.18f
        p > 0.65f -> 1f - (p - 0.65f) / 0.35f
        else -> 1f
    }
    if (env <= 0f) return
    val len = size.maxDimension * 0.95f

    // 底：弥散径向光
    drawCircle(
        Brush.radialGradient(
            listOf(tier.hi.copy(alpha = 0.16f * env), Color.Transparent),
            center = Offset(cx, cy), radius = len * 0.75f,
        ),
        radius = len * 0.75f, center = Offset(cx, cy), blendMode = BlendMode.Plus,
    )

    val spin = Math.toDegrees(((ms - 720f) * 0.00016f).toDouble()).toFloat()
    rotate(spin, Offset(cx, cy)) {
        val step = 6.2831855f / tier.beams
        for (i in 0 until tier.beams) {
            val a = i * step
            wedge(cx, cy, a, 0.16f, len, tier.mid, 0.10f * env)
            wedge(cx, cy, a + 0.14f, 0.055f, len, tier.hi, 0.13f * env)
        }
    }
}

/** 一根光柱：从中心张开 ±[half] 弧度的扇形，颜色向外淡出。 */
private fun DrawScope.wedge(cx: Float, cy: Float, a: Float, half: Float, len: Float, c: Color, alpha: Float) {
    val path = Path().apply {
        moveTo(cx, cy)
        lineTo(cx + cos(a - half) * len, cy + sin(a - half) * len)
        lineTo(cx + cos(a + half) * len, cy + sin(a + half) * len)
        close()
    }
    drawPath(
        path,
        Brush.radialGradient(
            listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.35f), Color.Transparent),
            center = Offset(cx, cy), radius = len,
        ),
        blendMode = BlendMode.Plus,
    )
}
