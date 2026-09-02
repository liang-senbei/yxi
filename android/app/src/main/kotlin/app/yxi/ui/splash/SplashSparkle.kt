package app.yxi.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import app.yxi.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 开屏方案「星点闪现」：粒子不飞，各自在「Yunxi」上自己的位置闪现——像星星一颗颗亮起来，拼出字，再化成真字。
 *
 * 时间线（共 2200ms，全部从一个 0→1 的全局时钟换算）：
 *  · 0～1200：闪现。每个粒子有自己的出现时刻（左边先亮），出现时半径 0→1.6→1（过冲，220ms）、alpha 0→1，
 *    颜色是火花渐变（按 x：蓝→紫→粉），400ms 内褪成 onSurface。约 8% 是四角星 ✦，出现时转 45°，尺寸 2.5 倍。
 *  · 1300～1900：笔画尖端各闪一颗更大的反色 ✦（8～10dp，alpha 0→0.9→0，350ms，错开 80ms），下面垫一圈淡蓝光晕。
 *  · 1700～2100：粒子层淡出、真字淡入，位置完全重合；2200 到点 [onDone]。
 *  · 底部一片极淡的粉彩蓝径向光（alpha 0.12），前 500ms 亮起。
 *
 * 系统关了动画：直接画最终画面，300ms 后 [onDone]。[onDone] 只会调一次。
 * 性能：一次采样进 FloatArray；一个绘制层每帧几百个 drawCircle + 几十个 drawPath；
 * 四角星只有一个单位 Path，画的时候平移缩放；调色板预先算好查表；draw 里不分配对象。
 */
@Composable
fun SplashSparkle(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    // 系统动画开关：关了就不闪，直接给结果
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 采样用位图读 alpha（一次）；最终画面用 painter 染成 onSurface。两者是同一张 PNG、同一个矩形，所以重合。
    val bmp = remember { ImageBitmap.imageResource(ctx.resources, R.drawable.logo_wordmark) }
    val sparks = remember { sampleSparks(bmp) }
    val logo = painterResource(R.drawable.logo_wordmark)

    // 全局时钟：0→1 线性走完 TOTAL_MS。关了动画就直接停在 1（最终帧）。
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(TOTAL_MS.toInt(), easing = LinearEasing)) }
    // 调用方重组时可能换一个 onDone 进来；到点时拿最新的那个
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) { delay(if (motion) TOTAL_MS.toLong() else 300L); done() }

    val bg = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val flash = bg                          // 尖端 ✦ 是 onSurface 的反色：浅色主题就是白
    val inkFilter = ColorFilter.tint(ink)   // 字是黑色 PNG，染成 onSurface 才能深浅主题都对
    // 调色板：16 个 x 分桶 × 9 级褪色（火花渐变 → onSurface），画帧只查表
    val pal = remember(ink) { Array(16 * 9) { i -> lerp(spark((i / 9 + 0.5f) / 16f), ink, (i % 9) / 8f) } }

    Spacer(
        Modifier.fillMaxSize().background(bg).drawWithCache {
            // 底光的 Brush 只在尺寸变时重建：每帧 new 一个会连带 new 一个原生 Shader
            val glow = Brush.radialGradient(
                listOf(BLUE.copy(alpha = 0.12f), BLUE.copy(alpha = 0f)),
                center = Offset(size.width / 2f, size.height * 1.02f),
                radius = maxOf(size.width, size.height) * 0.6f,
            )
            onDrawBehind {
                val ms = clock.value * TOTAL_MS
                val w = size.width; val h = size.height
                // 字的显示矩形：宽 = 屏宽 58%，水平居中，中心在 45% 高。粒子和真字都用它。
                val scale = w * 0.58f / bmp.width
                val dw = bmp.width * scale; val dh = bmp.height * scale
                val left = (w - dw) / 2f; val top = h * 0.45f - dh / 2f
                val unit = scale * 4.2f      // 普通点半径 = rad × unit，跟着字的宽度走
                drawRect(glow, alpha = (ms / 500f).coerceIn(0f, 1f))

                // 交叉淡入：1700→2100ms，smoothstep；粒子层 1→0，真字 0→1
                val f0 = ((ms - FADE0_MS) / CROSS_MS).coerceIn(0f, 1f)
                val f = f0 * f0 * (3f - 2f * f0)
                val layer = 1f - f
                if (layer > 0f) {
                    for (i in 0 until sparks.n) {
                        val age = ms - sparks.t0[i]
                        if (age < 0f) continue
                        val x = left + sparks.ix[i] * scale; val y = top + sparks.iy[i] * scale
                        var r = sparks.rad[i] * unit
                        var deg = sparks.rot[i] + 45f
                        var a = layer
                        var c = ink
                        if (age < TINT_MS) {
                            // 正在闪现：半径 0→1.6→1（back-out 形状，220ms），转 45°；alpha 100ms 到 1；颜色 400ms 褪成墨色
                            val p = (age / POP_MS).coerceAtMost(1f)
                            if (p < 1f) {
                                r *= p * (8.7f + p * (-14.4f + 6.7f * p))
                                deg = sparks.rot[i] + 45f * (1f - (1f - p) * (1f - p))
                            }
                            if (age < 100f) a *= age / 100f
                            val level = if (age < 100f) 0 else ((age - 100f) / (TINT_MS - 100f) * 8f).toInt()
                            c = pal[sparks.col[i] * 9 + level]
                        }
                        if (sparks.star[i]) drawStar(x, y, r, deg, c, a) else drawCircle(c, r, Offset(x, y), alpha = a)
                    }
                    // 笔画尖端的 ✦：alpha 0→0.9→0，半径 8/10dp 从 55% 弹到 100%，慢慢转；下面垫一颗 1.7 倍的淡蓝光晕
                    for (k in 0 until sparks.g) {
                        val q = (ms - sparks.gT[k]) / GLINT_MS
                        if (q <= 0f || q >= 1f) continue
                        val a = layer * 0.9f * sin(q * PIF)
                        val rr = (8f + (k and 1) * 2f) * density * (0.55f + 0.45f * (1f - (1f - q) * (1f - q)))
                        val deg = q * 0.5f * 180f / PIF
                        val x = left + sparks.gx[k] * scale; val y = top + sparks.gy[k] * scale
                        drawStar(x, y, rr * 1.7f, deg, HALO, a * 0.35f)
                        drawStar(x, y, rr, deg, flash, a)
                    }
                }
                if (f > 0f) withTransform({ translate(left, top) }) {
                    with(logo) { draw(Size(dw, dh), alpha = f, colorFilter = inkFilter) }
                }
            }
        },
    )
}

private const val TOTAL_MS = 2200f
private const val GLINT0_MS = 1300f     // 第一颗尖端 ✦ 出现，之后每颗错开 80ms
private const val GLINT_MS = 350f       // 每颗尖端 ✦ 的时长
private const val FADE0_MS = 1700f      // 粒子层开始淡出、真字开始淡入
private const val CROSS_MS = 400f       // 交叉淡入时长
private const val POP_MS = 220f         // 出现时的弹出时长
private const val TINT_MS = 400f        // 出现后褪成墨色的时长
private const val GRID = 5              // 采样网格步长（像素）：留 42%，落在 600～900 个点
private const val PIF = 3.1415927f
private val BLUE = Color(0xFF4285F4)
private val PURPLE = Color(0xFF9B72CB)
private val PINK = Color(0xFFD96570)
private val HALO = Color(0xFF8AB4F8)

/** 火花渐变：按 x（0～1）取色，蓝 → 紫 → 粉。 */
private fun spark(fx: Float): Color =
    if (fx < 0.5f) lerp(BLUE, PURPLE, fx * 2f) else lerp(PURPLE, PINK, fx * 2f - 1f)

/** 单位四角星 ✦：外接半径 1、圆心在原点，四个尖角，腰部由略过圆心的控制点收进去（腰 ≈ 0.31）。 */
private val STAR: Path = Path().apply {
    moveTo(1f, 0f)
    for (i in 1..4) {
        val am = (i - 0.5f) * PIF / 2f; val ai = i * PIF / 2f
        quadraticTo(-0.08f * cos(am), -0.08f * sin(am), cos(ai), sin(ai))
    }
    close()
}

/** 在 (x, y) 画一颗外接半径 r、转 deg 度的四角星：单位星形平移缩放，不新建 Path。withTransform 是 inline，无分配。 */
private fun DrawScope.drawStar(x: Float, y: Float, r: Float, deg: Float, color: Color, alpha: Float) =
    withTransform({ translate(x, y); rotate(deg, Offset.Zero); scale(r, r, Offset.Zero) }) {
        drawPath(STAR, color, alpha = alpha)
    }

/** 采样一次的结果。全数组，画帧时只读、不分配。数组比 [n] 长一截，只用到 n。 */
private class SparkleSparks(
    val n: Int,
    val ix: FloatArray, val iy: FloatArray,   // 位置：位图像素坐标，画时乘 scale 加偏移
    val t0: FloatArray,                       // 出现时刻 ms（0～1200，左边先亮）
    val rad: FloatArray,                      // 半径系数（× unit）；四角星已含 2.5 倍
    val rot: FloatArray,                      // 四角星的自转（度）
    val star: BooleanArray,                   // 四角星还是圆点
    val col: IntArray,                        // 火花渐变的 x 分桶 0～15
    val g: Int,                               // 尖端 ✦ 个数（≤4）
    val gx: FloatArray, val gy: FloatArray,   // 尖端位置：位图像素坐标
    val gT: FloatArray,                       // 尖端 ✦ 起始时刻 ms
)

/**
 * 从 wordmark 的 alpha 通道采粒子：每 [GRID] 像素看一个点，alpha 过半才算在墨上；随机只留 42%、位置抖 ±2px。
 * 顺手记下 8 个方向（每 45°）上最远的墨点——笔画尖端就在其中，互相隔 ≥110px 的前 4 个当尖端 ✦ 的落点，
 * 按 x 排序从左到右错开。固定种子：每次开屏一模一样，方便对着调。
 */
private fun sampleSparks(bmp: ImageBitmap): SparkleSparks {
    val px = bmp.toPixelMap()
    val rnd = Random(20260902)
    val w = bmp.width.toFloat()
    val cap = (bmp.width / GRID + 1) * (bmp.height / GRID + 1)
    val ix = FloatArray(cap); val iy = FloatArray(cap); val t0 = FloatArray(cap)
    val rad = FloatArray(cap); val rot = FloatArray(cap); val star = BooleanArray(cap); val col = IntArray(cap)
    val ca = FloatArray(8) { cos(it * PIF / 4f) }; val sa = FloatArray(8) { sin(it * PIF / 4f) }
    val ex = FloatArray(8); val ey = FloatArray(8); val ev = FloatArray(8) { Float.NEGATIVE_INFINITY }
    var n = 0
    for (y in 0 until bmp.height step GRID) for (x in 0 until bmp.width step GRID) {
        if (px[x, y].alpha < 0.5f) continue
        for (k in 0 until 8) {
            val v = x * ca[k] + y * sa[k]
            if (v > ev[k]) { ev[k] = v; ex[k] = x.toFloat(); ey[k] = y.toFloat() }
        }
        if (rnd.nextFloat() >= 0.42f) continue
        ix[n] = x + rnd.nextFloat() * 4f - 2f
        iy[n] = y + rnd.nextFloat() * 4f - 2f
        star[n] = rnd.nextFloat() < 0.08f
        rad[n] = (0.75f + rnd.nextFloat() * 0.4f) * (if (star[n]) 2.5f else 1f)
        rot[n] = rnd.nextFloat() * 360f
        col[n] = (ix[n] / w * 16f).toInt().coerceIn(0, 15)
        t0[n] = ix[n] / w * 700f + rnd.nextFloat() * 500f
        n++
    }
    val tips = ArrayList<Offset>(4)
    for (k in 0 until 8) {
        val p = Offset(ex[k], ey[k])
        if (tips.size < 4 && tips.none { (it - p).getDistance() < 110f }) tips.add(p)
    }
    tips.sortBy { it.x }
    return SparkleSparks(
        n, ix, iy, t0, rad, rot, star, col,
        tips.size, FloatArray(tips.size) { tips[it].x }, FloatArray(tips.size) { tips[it].y },
        FloatArray(tips.size) { GLINT0_MS + it * 80f },
    )
}
