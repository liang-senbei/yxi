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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.yxi.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 开屏方案「星尘汇聚」：几百粒发光的星尘各自绕着自己在「Yunxi」上的落点旋转收拢，拼出字形后
 * 褪色成墨点、一道高光扫过，再化成真正的毛笔字。
 *
 * 时间线（共 2500ms，全部由一个 0→1 的全局时钟换算，和 SplashParticles 同一套骨架）：
 *  · 0～1600：飘入。每粒星尘 = 落点 + 一个逐渐收紧的旋转偏移（半径 0.3～0.73 倍字宽缩到 0，
 *    转 0.6～1.2 圈；方向、起始角、0～350ms 的起飞延迟各自随机）。颜色按落点从左到右取蓝→紫→粉。
 *  · 1600～2100：墨化。光斑褪色、缩小，实心 onSurface 墨点浮现；同时一道白色窄高光从左扫到右，只叠在粒子上。
 *  · 2100～2500：粒子层淡出、真字淡入。两者用同一个矩形算位置，完全重合。到点 [onDone]。
 *
 * 系统关了动画：直接画最终画面（那张 wordmark），300ms 后 [onDone]。
 * 性能：光斑是一张 17 格贴图（16 个色阶 + 1 个白色高光）建一次，每帧按格裁切 drawImage 几百次；
 * 粒子数据全放 FloatArray，画帧不分配对象；固定随机种子，每次开屏一模一样。
 */
@Composable
fun SplashStardust(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    // 系统动画开关（跟 SplashParticles 同一套判法）：关了就不飘，直接给结果
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 位图解码一次、采样一次，最终画面也直接 drawImage 这张位图：落点和画出来的字是同一批像素
    val bmp = remember { ImageBitmap.imageResource(ctx.resources, R.drawable.logo_wordmark) }
    val dust = remember { sampleDust(bmp, density) }
    val sheet = remember { makeSheet() }

    // 全局时钟：0→1 线性走完 TOTAL_MS。关了动画就直接停在 1（最终帧），不起动画。
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(TOTAL_MS.toInt(), easing = LinearEasing)) }
    LaunchedEffect(Unit) { delay(if (motion) TOTAL_MS.toLong() else 300L); onDone() }

    val bg = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val inkFilter = ColorFilter.tint(ink)   // 字是黑色 PNG，染成 onSurface 才能深浅主题都对

    Canvas(
        Modifier.fillMaxSize().background(bg).drawWithCache {
            // 底部一片极淡的粉彩蓝径向光：圆心在屏幕底缘略下方。只在尺寸变化时重建，画帧不分配
            val haze = Brush.radialGradient(
                listOf(HAZE.copy(alpha = 0.12f), HAZE.copy(alpha = 0f)),
                center = Offset(size.width / 2f, size.height * 1.02f),
                radius = max(size.width, size.height) * 0.6f,
            )
            onDrawBehind { drawRect(haze) }
        },
    ) {
        val ms = clock.value * TOTAL_MS
        val w = size.width; val h = size.height
        // 字的显示矩形：宽 = 屏宽 58%，水平居中，中心在 45% 高。粒子落点和真字都用它。
        val scale = w * 0.58f / bmp.width
        val dw = bmp.width * scale; val dh = bmp.height * scale
        val left = (w - dw) / 2f; val top = h * 0.45f - dh / 2f
        // 粒子层整体透明度：2100→2500 从 1 褪到 0；真字的透明度正好是它的补
        val layer = 1f - FastOutSlowInEasing.transform(((ms - INK_MS) / (TOTAL_MS - INK_MS)).coerceIn(0f, 1f))
        // 墨化进度：1600→2100 从 0 到 1
        val k2 = FastOutSlowInEasing.transform(((ms - FLY_MS) / (INK_MS - FLY_MS)).coerceIn(0f, 1f))

        if (layer > 0.005f) {
            val sc = (dw / density / 190f).coerceIn(0.75f, 2f)   // 光斑尺寸跟着字宽缩放（网页版同款系数）
            val fly = ms / FLY_MS
            // 1) 位置：落点 + 收紧的旋转偏移。各自扣掉延迟后归一化，再过 FastOutSlowIn —— 先快后缓，像落定
            for (i in 0 until dust.n) {
                val d = dust.lag[i]
                val e = FastOutSlowInEasing.transform(((fly - d) / (1f - d)).coerceIn(0f, 1f))
                val rad = dust.orb[i] * dw * (1f - e)
                val ang = dust.a0[i] + dust.spin[i] * e
                dust.x[i] = left + dust.ix[i] * scale + rad * cos(ang)
                dust.y[i] = top + dust.iy[i] * scale + rad * sin(ang)
                dust.prog[i] = e
            }
            // 2) 彩色光斑：起飞后前 20% 进度淡入，带一点闪烁；墨化时整体褪色、从 1.5 倍缩到 0.6 倍
            val ga = (1f - k2) * layer
            if (ga > 0.005f) {
                val grow = sc * (1.5f - 0.9f * k2)
                for (i in 0 until dust.n) {
                    val a = min(1f, dust.prog[i] * 5f) * (0.7f + 0.3f * sin(ms * 0.01f + dust.ph[i])) * ga
                    if (a <= 0.01f) continue
                    glow(sheet, dust.cell[i], dust.x[i], dust.y[i], dust.sz[i] * grow, a)
                }
            }
            // 3) 实心墨点：墨化时浮现，颜色就是 onSurface，交叉淡入开始时已和真字同色
            if (k2 > 0.005f) {
                val r = sc * 0.55f
                for (i in 0 until dust.n) {
                    drawCircle(ink, dust.sz[i] * r, Offset(dust.x[i], dust.y[i]), alpha = k2 * layer)
                }
            }
            // 4) 高光：一道半宽 7% 字宽的白色窄带从字左外侧扫到右外侧，只点亮带内的粒子
            if (ms > FLY_MS && ms < INK_MS) {
                val band = dw * 0.07f
                val sx = left - band + (dw + 2f * band) * (ms - FLY_MS) / (INK_MS - FLY_MS)
                for (i in 0 until dust.n) {
                    val dist = abs(dust.x[i] - sx)
                    if (dist >= band) continue
                    val f = 1f - dist / band
                    glow(sheet, WHITE_X, dust.x[i], dust.y[i], dust.sz[i] * sc * 1.8f, f * f * 0.9f)
                }
            }
        }
        if (layer < 1f) drawImage(
            bmp,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            alpha = 1f - layer, colorFilter = inkFilter,
        )
    }
}

private const val FLY_MS = 1600f        // 0～1600：旋转飘入
private const val INK_MS = 2100f        // 1600～2100：褪色成墨点 + 高光扫过
private const val TOTAL_MS = 2500f      // 2100～2500：粒子层淡出、真字淡入；到点 onDone
private const val MAX_DELAY_MS = 350f   // 各粒子起飞延迟上限
private const val GRID = 6              // 采样网格步长（像素）：564×349 的图约 1300 个墨点，丢一半剩 ~660
private const val TAU = 6.2831855f
private const val SS = 64               // 光斑贴图每格边长（px）
private const val NC = 16               // 彩色格数；第 NC 格是白色高光
private const val WHITE_X = NC * SS     // 白色格在贴图上的 x 偏移
private val CELL = IntSize(SS, SS)
// Gemini 火花渐变：蓝 → 紫 → 粉，按粒子在字上的 x 从左到右取
private val SPARK_BLUE = Color(0xFF4285F4)
private val SPARK_PURPLE = Color(0xFF9B72CB)
private val SPARK_PINK = Color(0xFFD96570)
private val HAZE = Color(0xFF6EA0FF)    // 底部那片粉彩蓝，画的时候 alpha 0.12

/** 采样一次的结果。全 FloatArray，画帧时只读固定项、写当前项，不分配。数组比 [n] 长一截，只用到 n。 */
private class StardustDust(
    val n: Int,
    val ix: FloatArray, val iy: FloatArray,   // 落点：位图像素坐标，画的时候乘 scale 加偏移
    val orb: FloatArray,                      // 起始绕行半径，占字宽的比例（0.3～0.73）
    val a0: FloatArray, val spin: FloatArray, // 起始角、总转角（rad，±0.6～1.2 圈）
    val lag: FloatArray,                      // 起飞延迟，已换算成飞行段的比例（0～0.22）
    val sz: FloatArray,                       // 基础半径 px（2～4dp），画的时候再乘各阶段系数
    val cell: IntArray,                       // 所用贴图格的 x 偏移（按落点 x 选色）
    val ph: FloatArray,                       // 闪烁相位
) {
    // 每帧算出来的当前位置和飞行进度，复用
    val x = FloatArray(n); val y = FloatArray(n); val prog = FloatArray(n)
}

private fun spark(u: Float): Color =
    if (u < 0.5f) lerp(SPARK_BLUE, SPARK_PURPLE, u * 2f) else lerp(SPARK_PURPLE, SPARK_PINK, (u - 0.5f) * 2f)

/**
 * 光斑贴图：NC 个从蓝到粉的软光斑 + 1 个白色高光，横排在一张位图上，画帧时按格裁切 drawImage。
 * 每格是径向渐变：中心偏白 40% 的亮核 → 30% 处纯色 → 边缘透明。每帧几百个 Brush.radialGradient 太慢，贴图一次搞定。
 */
private fun makeSheet(): ImageBitmap {
    val sheet = ImageBitmap(SS * (NC + 1), SS)
    val canvas = BitmapCanvas(sheet)
    val paint = Paint()
    for (i in 0..NC) {
        val c = if (i < NC) spark(i / (NC - 1f)) else Color.White
        val center = Offset(i * SS + SS / 2f, SS / 2f)
        paint.shader = RadialGradientShader(
            center, SS / 2f,
            colors = listOf(lerp(c, Color.White, 0.4f), c.copy(alpha = 0.9f), c.copy(alpha = 0f)),
            colorStops = listOf(0f, 0.3f, 1f),
        )
        canvas.drawCircle(center, SS / 2f, paint)
    }
    return sheet
}

/** 在 (x, y) 画贴图里 x 偏移为 [cellX] 的那格光斑，半径 [r] px。 */
private fun DrawScope.glow(sheet: ImageBitmap, cellX: Int, x: Float, y: Float, r: Float, alpha: Float) {
    val d = (2f * r).roundToInt()
    drawImage(
        sheet, srcOffset = IntOffset(cellX, 0), srcSize = CELL,
        dstOffset = IntOffset((x - r).roundToInt(), (y - r).roundToInt()), dstSize = IntSize(d, d),
        alpha = alpha,
    )
}

/**
 * 从 wordmark 的 alpha 通道采星尘落点：每 [GRID] 像素看一个点，alpha 过半才算在墨上；再随机丢一半、
 * 位置抖 ±2px —— 不然是一张整齐的网格纸。起始角朝着字心的外侧（再抖 ±0.6 rad），一开始就散在字的四周，
 * 然后一圈圈收进来。固定种子：每次开屏一模一样，方便对着调。
 */
private fun sampleDust(bmp: ImageBitmap, density: Float): StardustDust {
    val px = bmp.toPixelMap()
    val rnd = Random(20260902)
    val cap = (bmp.width / GRID + 1) * (bmp.height / GRID + 1)
    val ix = FloatArray(cap); val iy = FloatArray(cap); val orb = FloatArray(cap)
    val a0 = FloatArray(cap); val spin = FloatArray(cap); val lag = FloatArray(cap)
    val sz = FloatArray(cap); val cell = IntArray(cap); val ph = FloatArray(cap)
    val hw = bmp.width / 2f; val hh = bmp.height / 2f
    var n = 0
    for (y in 0 until bmp.height step GRID) {
        for (x in 0 until bmp.width step GRID) {
            if (px[x, y].alpha < 0.5f || rnd.nextBoolean()) continue
            ix[n] = x + rnd.nextFloat() * 4f - 2f
            iy[n] = y + rnd.nextFloat() * 4f - 2f
            orb[n] = 0.3f + rnd.nextFloat() * 0.43f
            a0[n] = atan2(iy[n] - hh, ix[n] - hw) + (rnd.nextFloat() - 0.5f) * 1.2f
            spin[n] = (if (rnd.nextFloat() < 0.8f) 1f else -1f) * (0.6f + rnd.nextFloat() * 0.6f) * TAU
            lag[n] = rnd.nextFloat() * MAX_DELAY_MS / FLY_MS
            sz[n] = (2f + rnd.nextFloat() * 2f) * density
            cell[n] = (ix[n] / bmp.width * (NC - 1)).roundToInt().coerceIn(0, NC - 1) * SS
            ph[n] = rnd.nextFloat() * TAU
            n++
        }
    }
    return StardustDust(n, ix, iy, orb, a0, spin, lag, sz, cell, ph)
}
