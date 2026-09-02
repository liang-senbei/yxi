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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.yxi.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 开屏方案「粒子聚合」：几百个小点从屏幕外四面八方飞进来，拼成「Yunxi」，再化成真正的毛笔字。
 *
 * 时间线（共 2200ms，所有分段都从一个 0→1 的全局时钟换算）：
 *  · 0～1400：飞行。每个粒子起点在屏幕外的一圈上、终点是字上采样的一个点，各自有 0～350ms 的起飞延迟。
 *  · 1400～1800：交叉淡入。粒子层 1→0，真字 0→1；两者用同一个矩形算位置，所以完全重合。
 *  · 1800～2200：停住，然后 [onDone]。
 *
 * 系统关了动画：直接画最终画面（那张 wordmark），300ms 后 [onDone]。
 * 性能：一张 Canvas、每帧几百次 drawCircle；位图解码和采样只做一次；粒子数据全放 FloatArray，画帧不分配对象。
 */
@Composable
fun SplashParticles(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    // 系统动画开关（跟 ThinkingGlow 同一套判法）：关了就不飞，直接给结果
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 位图解码一次、采样一次。最终画面直接 drawImage 这张位图（painterResource 对 PNG 内部就是它），
    // 采样出的终点和画出来的字保证是同一批像素。
    val bmp = remember { ImageBitmap.imageResource(ctx.resources, R.drawable.logo_wordmark) }
    val field = remember { sample(bmp, density) }

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
        // 字的显示矩形：宽 = 屏宽 58%，水平居中，中心在 45% 高。粒子终点和真字都用它。
        // 每帧在这里算（几次乘法），采样时只存位图像素坐标 —— 转屏 / 尺寸变了也不用重采样。
        val scale = w * 0.58f / bmp.width
        val dw = bmp.width * scale; val dh = bmp.height * scale
        val left = (w - dw) / 2f; val top = h * 0.45f - dh / 2f
        // 交叉淡入进度：1400→1800ms 从 0 走到 1
        val xf = ((ms - FLY_MS) / CROSS_MS).coerceIn(0f, 1f)

        if (xf < 1f) {
            val cx = w / 2f; val cy = h / 2f
            val ring = 0.6f * hypot(w, h)   // 起点圆：屏幕外接圆再大 20%，起飞前全在屏外，看不见「等待」的点
            val fly = (ms / FLY_MS).coerceIn(0f, 1f)
            // 颜色：前 40%（880ms）是品牌蓝，880→1400ms 渐变到 onSurface，交叉淡入开始时已和真字同色
            val dot = lerp(BRAND_BLUE, ink, ((ms - 0.4f * TOTAL_MS) / (FLY_MS - 0.4f * TOTAL_MS)).coerceIn(0f, 1f))
            for (i in 0 until field.n) {
                // 各自的进度：扣掉自己的延迟后归一化，再过 FastOutSlowIn —— 先快后缓，像落定
                val d = field.lag[i]
                val p = FastOutSlowInEasing.transform(((fly - d) / (1f - d)).coerceIn(0f, 1f))
                val sx = cx + field.cs[i] * ring; val sy = cy + field.sn[i] * ring
                val tx = left + field.ix[i] * scale; val ty = top + field.iy[i] * scale
                drawCircle(
                    dot, field.r[i], Offset(sx + (tx - sx) * p, sy + (ty - sy) * p),
                    alpha = (0.5f + 0.5f * p) * (1f - xf),   // 飞行中 0.5→1，淡出段再乘 1→0
                )
            }
        }
        if (xf > 0f) drawImage(
            bmp,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            alpha = xf, colorFilter = inkFilter,
        )
    }
}

private const val FLY_MS = 1400f        // 0～1400：飞行
private const val CROSS_MS = 400f       // 1400～1800：粒子淡出、真字淡入
private const val HOLD_MS = 400f        // 1800～2200：停住
private const val TOTAL_MS = FLY_MS + CROSS_MS + HOLD_MS
private const val MAX_DELAY_MS = 350f   // 各粒子起飞延迟上限
private const val GRID = 6              // 采样网格步长（像素）：564×349 的图约 1300 个墨点，丢一半剩 ~660
private val BRAND_BLUE = Color(0xFF0B57D0)

/** 采样一次的结果。全 FloatArray，画帧时只读、不分配。数组比 [n] 长一截，只用到 n。 */
private class Field(
    val n: Int,
    val ix: FloatArray, val iy: FloatArray,   // 终点：位图像素坐标，画的时候乘 scale 加偏移
    val cs: FloatArray, val sn: FloatArray,   // 起点方向的 cos / sin，起点 = 屏心 + 方向 × 圈半径
    val lag: FloatArray,                      // 起飞延迟，已换算成飞行段的比例（0～0.25）
    val r: FloatArray,                        // 半径 px（1.2～2.6dp）
)

/**
 * 从 wordmark 的 alpha 通道采粒子终点：每 [GRID] 像素看一个点，alpha 过半才算在墨上；再随机丢一半、
 * 位置抖 ±2px —— 不然是一张整齐的网格纸，不像墨聚起来。固定种子：每次开屏一模一样，方便对着调。
 */
private fun sample(bmp: ImageBitmap, density: Float): Field {
    val px = bmp.toPixelMap()
    val rnd = Random(20260902)
    val cap = (bmp.width / GRID + 1) * (bmp.height / GRID + 1)
    val ix = FloatArray(cap); val iy = FloatArray(cap)
    val cs = FloatArray(cap); val sn = FloatArray(cap)
    val lag = FloatArray(cap); val r = FloatArray(cap)
    var n = 0
    for (y in 0 until bmp.height step GRID) {
        for (x in 0 until bmp.width step GRID) {
            if (px[x, y].alpha < 0.5f || rnd.nextBoolean()) continue
            ix[n] = x + rnd.nextFloat() * 4f - 2f
            iy[n] = y + rnd.nextFloat() * 4f - 2f
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            cs[n] = cos(a); sn[n] = sin(a)
            lag[n] = rnd.nextFloat() * MAX_DELAY_MS / FLY_MS
            r[n] = (1.2f + rnd.nextFloat() * 1.4f) * density
            n++
        }
    }
    return Field(n, ix, iy, cs, sn, lag, r)
}
