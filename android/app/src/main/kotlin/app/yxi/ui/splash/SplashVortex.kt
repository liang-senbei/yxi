package app.yxi.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import app.yxi.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 开屏方案「旋涡」：几百个彩色小点从屏幕外沿对数螺旋旋进来，在「Yunxi」上落定，再化成真正的毛笔字。
 *
 * 时间线（共 2300ms，编排照网页版 particles-vortex.html，所有分段都从一个 0→1 的全局时钟换算）：
 *  · 0～1500：飞行。每个粒子以自己的终点为极点，从 0.7×对角线远处沿对数螺旋旋进，半径指数衰减到 0，
 *    总共转 1.2～1.8 圈、全体同向，缓动 FastOutSlowIn；各自再拖 0～300ms 起飞，最晚 1680 落定。
 *    颜色全体同步沿色相 210→180→120→60→30→330 流动（HSL 饱和 0.62、亮度 0.62），身后跟 3 个渐淡的拖尾点。
 *  · 落定瞬间：半径 1.0→1.5→1.0 过冲 150ms，300ms 内从粉彩粉褪成 onSurface。
 *  · 1800～2200：交叉淡入。粒子层 1→0，真字 0→1；两者用同一个矩形算位置，所以完全重合。
 *  · 2300：[onDone]。
 * 背面一片极淡的粉彩蓝径向光（圆心 alpha 0.10），随旋涡收紧从 0.6×对角线收到字宽的 35%。
 *
 * 系统关了动画：直接画最终画面（那张 wordmark），300ms 后 [onDone]。
 * 性能：一张 Canvas、每帧几百个点 × 4 层 drawCircle；位图解码和采样只做一次；粒子数据全放 FloatArray，
 * 画帧里不分配对象（径向光的 Brush 一帧一个小对象，可忽略）。
 * 拖尾没用环形缓冲存「前 3 帧」的位置，而是按「16 / 32 / 48ms 前的时刻」解析地重算同一条螺旋：
 * 画帧是时钟的纯函数，不用在 draw 里改状态、不怕一帧画两次，60Hz 和 120Hz 上拖尾一样长。
 */
@Composable
fun SplashVortex(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    // 系统动画开关（跟 SplashParticles / ThinkingGlow 同一套判法）：关了就不转，直接给结果
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 采样只做一次：读 wordmark 的 alpha 通道定每个粒子的终点（位图像素坐标）。
    // 最终画面用 painterResource 画同一张图；两者用同一个显示矩形换算位置，落定的字和真字完全重合。
    val field = remember { sample(ImageBitmap.imageResource(ctx.resources, R.drawable.logo_wordmark)) }
    val logo = painterResource(R.drawable.logo_wordmark)

    // 全局时钟：0→1 线性走完 TOTAL_MS。关了动画就直接停在 1（最终帧），不起动画。
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(TOTAL_MS.toInt(), easing = LinearEasing)) }
    LaunchedEffect(Unit) { delay(if (motion) TOTAL_MS.toLong() else 300L); onDone() }

    val bg = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val inkFilter = ColorFilter.tint(ink)   // 字是黑色 PNG，染成 onSurface 才能深浅主题都对

    Canvas(Modifier.fillMaxSize().background(bg)) {
        val ms = clock.value * TOTAL_MS
        val w = size.width; val h = size.height
        // 字的显示矩形：宽 = 屏宽 58%，水平居中，中心在 45% 高。粒子终点、真字、径向光的圆心都用它。
        val dw = w * 0.58f; val scale = dw / field.bw; val dh = field.bh * scale
        val left = (w - dw) / 2f; val top = h * 0.45f - dh / 2f
        val cx = w / 2f; val cy = h * 0.45f
        val diag = hypot(w, h)
        val r0 = 0.7f * diag                                             // 螺旋起点离终点的距离：屏外一圈
        val dotR = (dw * 0.0065f).coerceIn(1.1f * density, 4f * density)
        // 交叉淡入：1800→2200ms 平滑地从 0 走到 1；粒子层 alpha = 1-u，真字 alpha = u
        val u = ((ms - FADE_IN_MS) / (FADE_OUT_MS - FADE_IN_MS)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val la = 1f - u

        if (la > 0f) {
            // 粉彩蓝径向光：随旋涡收紧从 0.6×对角线收到字宽的 35%
            val gr = dw * 0.35f + (diag * 0.6f - dw * 0.35f) *
                spiral(FastOutSlowInEasing.transform(((ms - 150f) / FLY_MS).coerceIn(0f, 1f)))
            drawCircle(Brush.radialGradient(GLOW, Offset(cx, cy), gr), gr, Offset(cx, cy), alpha = la)

            // 飞行层：4 层 = 3 个拖尾（先画最淡的）+ 头。最后一只在 FLY + 0.6×DELAY 落定，之后整层跳过
            if (ms < FLY_MS + MAX_DELAY_MS * 0.6f) {
                val col = flyColor(ms)
                for (k in 3 downTo 0) {
                    val layerA = if (k == 0) la else la * TRAIL_ALPHA[k - 1]
                    val rad = if (k == 0) dotR else dotR * 0.75f
                    for (i in 0 until field.n) {
                        val span = field.t1[i] - field.t0[i]
                        val p0 = (ms - field.t0[i]) / span
                        if (p0 <= 0f || p0 >= 1f) continue          // 没起飞 / 已落定：整只不在飞行层
                        val p = p0 - k * TRAIL_MS / span            // 第 k 个拖尾 = 这只粒子 k×16ms 前的位置
                        if (p <= 0f) continue                       // 那会儿还没出发（起点在屏外）
                        val s = FastOutSlowInEasing.transform(p)
                        val rr = r0 * spiral(s)
                        val th = field.th0[i] + field.dth[i] * s
                        val x = left + field.ix[i] * scale + rr * cos(th)
                        val y = top + field.iy[i] * scale + rr * sin(th)
                        if (x < -8f || y < -8f || x > w + 8f || y > h + 8f) continue
                        drawCircle(col, rad, Offset(x, y), alpha = layerA)
                    }
                }
            }
            // 落定层：150ms 半径过冲 1.0→1.5→1.0，300ms 内从粉彩粉褪成 onSurface
            for (i in 0 until field.n) {
                val a = ms - field.t1[i]
                if (a < 0f) continue
                val rad = if (a < OVER_MS) dotR * (1f + 0.5f * sin(a / OVER_MS * PI.toFloat())) else dotR
                val col = if (a < TINT_MS) lerp(PINK, ink, a / TINT_MS) else ink
                drawCircle(col, rad, Offset(left + field.ix[i] * scale, top + field.iy[i] * scale), alpha = la)
            }
        }
        if (u > 0f) translate(left, top) { with(logo) { draw(Size(dw, dh), alpha = u, colorFilter = inkFilter) } }
    }
}

private const val FLY_MS = 1500f         // 0～1500：飞行（各自再拖 0～MAX_DELAY 起飞）
private const val MAX_DELAY_MS = 300f    // 起飞延迟上限；落定时刻 = FLY + 0.6×延迟，最晚 1680
private const val FADE_IN_MS = 1800f     // 1800～2200：粒子淡出、真字淡入
private const val FADE_OUT_MS = 2200f
private const val TOTAL_MS = 2300f
private const val TRAIL_MS = 16f         // 相邻拖尾点的时间差（≈ 60Hz 的一帧）
private const val OVER_MS = 150f         // 落定过冲时长
private const val TINT_MS = 300f         // 落定后褪成 onSurface 的时长
private const val K = 4f                 // 螺旋半径衰减指数：r = R0 × exp(-K·s)，再平移使 s=1 时恰为 0
private const val GRID = 6               // 采样网格步长（像素）：564×349 的图约 1300 个墨点，丢一半剩 ~660
private val EK = exp(-K)
private val TAU = (2 * PI).toFloat()
private val TRAIL_ALPHA = floatArrayOf(0.5f, 0.3f, 0.15f)
private val HUES = floatArrayOf(210f, 180f, 120f, 60f, 30f, -30f)   // 飞行中的色相路径，-30 即 330
private val PINK = Color.hsl(330f, 0.62f, 0.62f)                    // 落定那一刻的颜色 = 路径终点
private val GLOW = listOf(Color(0x1A78AAFF), Color(0x0078AAFF))     // 粉彩蓝，圆心 alpha 0.10 → 边缘 0

/** 对数螺旋的半径系数：s=0 时 1，s=1 时恰好 0。 */
private fun spiral(s: Float): Float = (exp(-K * s) - EK) / (1f - EK)

/** 飞行中的颜色：整段 FLY_MS 内色相沿 [HUES] 分 5 段线性流动，过了终点就停在粉。 */
private fun flyColor(ms: Float): Color {
    val s = (ms / FLY_MS * 5f).coerceIn(0f, 4.999f)
    val i = s.toInt()
    val hue = (HUES[i] + (HUES[i + 1] - HUES[i]) * (s - i) + 360f) % 360f
    return Color.hsl(hue, 0.62f, 0.62f)
}

/** 采样一次的结果。全 FloatArray，画帧时只读、不分配。数组比 [n] 长一截，只用到 n。 */
private class VortexField(
    val n: Int,
    val bw: Int, val bh: Int,                 // 位图尺寸，画帧时用来把像素坐标换算到显示矩形
    val ix: FloatArray, val iy: FloatArray,   // 终点：位图像素坐标
    val th0: FloatArray, val dth: FloatArray, // 起始极角、总转角（弧度，全体同向）
    val t0: FloatArray, val t1: FloatArray,   // 起飞 / 落定时刻（ms）
)

/**
 * 从 wordmark 的 alpha 通道采粒子终点：每 [GRID] 像素看一个点，alpha 过半才算在墨上；再随机丢一半、
 * 位置抖 ±2px —— 不然是一张整齐的网格纸，不像墨聚起来。固定种子：每次开屏一模一样，方便对着调。
 */
private fun sample(bmp: ImageBitmap): VortexField {
    val px = bmp.toPixelMap()
    val rnd = Random(20260902)
    val cap = (bmp.width / GRID + 1) * (bmp.height / GRID + 1)
    val ix = FloatArray(cap); val iy = FloatArray(cap)
    val th0 = FloatArray(cap); val dth = FloatArray(cap)
    val t0 = FloatArray(cap); val t1 = FloatArray(cap)
    var n = 0
    for (y in 0 until bmp.height step GRID) {
        for (x in 0 until bmp.width step GRID) {
            if (px[x, y].alpha < 0.5f || rnd.nextBoolean()) continue
            ix[n] = x + rnd.nextFloat() * 4f - 2f
            iy[n] = y + rnd.nextFloat() * 4f - 2f
            th0[n] = rnd.nextFloat() * TAU
            dth[n] = TAU * (1.2f + rnd.nextFloat() * 0.6f)
            val dl = rnd.nextFloat() * MAX_DELAY_MS
            t0[n] = dl; t1[n] = FLY_MS + dl * 0.6f
            n++
        }
    }
    return VortexField(n, bmp.width, bmp.height, ix, iy, th0, dth, t0, t1)
}
