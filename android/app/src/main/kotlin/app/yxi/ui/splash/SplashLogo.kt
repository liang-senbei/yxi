package app.yxi.ui.splash

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yxi.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 新品牌标记（两片花瓣）的三支开屏动效 —— **翻面 / 聚合 / 显影**。
 *
 * 这三支是先在实验室里做成网页给用户挑、用户从更多方案里留下的这三支，这里是**照着定稿逐帧移植**：
 * 时间线、缓动曲线、每一段的起止都跟实验室那版一致，改动只有一处（见 [SplashFlip] 里闪光方向的注释）。
 *
 * ⚠️ **播哪一支由主题决定**，不是随机乱挑（用户 2026-09-04 定的）：
 * **深色主题只播「显影」**（它是照着近黑底设计的，浅底上完全不成立）；
 * **浅色主题在「翻面」和「聚合」之间随机**。规则写在 [Splash.chosen]。
 *
 * ⚠️ 老的四支（粒子聚合 / 星尘 / 旋涡 / 星点）画的是**手写 Yunxi 字**，是上一版品牌。
 * 换 logo 之后它们已经不在轮播里了，留着是因为那套采样骨架还有参考价值。
 */

/** 系统「减弱动效」开关。关了就不动，直接给最后一帧 —— 全 App 一套判法（见 design/STYLE.md §2.4）。 */
internal fun motionOn(ctx: Context): Boolean = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)

/**
 * 浅色开屏的底色 —— **不是 surface 的纯白**，是启动器图标那个 `#F4F6FA`。
 * ⚠️ 纯白会吃掉左边那片浅蓝花瓣的边缘（design/STYLE.md §1.5 记的就是这件事，图标底色也是为此定的）。
 */
private val LightBg = Color(0xFFF4F6FA)

// ────────────────────────────────────────────────────────────── 翻面

/**
 * 「翻面」：logo 像一张卡片从侧面翻正，落定后一道斜光顺着 logo 的形状扫过，底下随之压出一小片影子。
 *
 * 时间线（共 1850ms）：
 *  · 60～1060  卡片翻正。绕 Y 轴 −96° → +9°（过冲）→ 0°，同时轻微绕 Z 转、从 0.86 放大到 1.02 再回 1。
 *  · 800～1560 闪光扫过。
 *  · 200～1100 影子从窄到宽淡入。
 *  · 1560～1850 停住。
 */
@Composable
fun SplashFlip(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember { motionOn(ctx) }
    val bmp = remember { ImageBitmap.imageResource(ctx.resources, R.drawable.logo_mark) }
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(FLIP_TOTAL.toInt(), easing = LinearEasing)) }
    LaunchedEffect(Unit) { delay(if (motion) FLIP_TOTAL.toLong() else 300L); onDone() }

    BoxWithConstraints(Modifier.fillMaxSize().background(LightBg), contentAlignment = Alignment.Center) {
        val stage = if (maxWidth < maxHeight) maxWidth * 0.66f else maxHeight * 0.66f
        val ms = clock.value * FLIP_TOTAL

        // ── 卡片：两段关键帧，**每段各自过一遍缓动**（CSS 的 animation-timing-function 就是按段生效的）
        val u = ((ms - 60f) / 1000f).coerceIn(0f, 1f)
        val rotY: Float; val rotZ: Float; val sc: Float
        if (u <= 0.72f) {
            val k = FLIP_EASE.transform(u / 0.72f)
            rotY = -96f + k * 105f; rotZ = -8f + k * 9f; sc = 0.86f + k * 0.16f
        } else {
            val k = FLIP_EASE.transform((u - 0.72f) / 0.28f)
            rotY = 9f - k * 9f; rotZ = 1f - k * 1f; sc = 1.02f - k * 0.02f
        }
        val cardAlpha = (u / 0.55f).coerceIn(0f, 1f)

        // ── 影子：椭圆一小片，从窄拉开
        val sh = ((ms - 200f) / 900f).coerceIn(0f, 1f)
        if (sh > 0f) Canvas(
            Modifier.width(stage * 0.76f).height(stage * 0.08f)
                .offset(y = stage * 0.52f)
                .graphicsLayer { alpha = sh; scaleX = 0.4f + sh * 0.6f },
        ) {
            drawOval(
                Brush.radialGradient(
                    listOf(Color(0x595A6EA0), Color(0x005A6EA0)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.width / 2f,
                ),
            )
        }

        Box(
            Modifier.size(stage).graphicsLayer {
                // CSS 那边是 `perspective: 900px`；Compose 的 cameraDistance 单位是像素，换算一下就是它
                cameraDistance = 900.dp.toPx()
                rotationY = rotY; rotationZ = rotZ
                scaleX = sc; scaleY = sc; alpha = cardAlpha
            },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawLogo(bmp, size.minDimension) }

            // ── 闪光：一条 45° 亮带，**只在 logo 形状里**扫。
            //    做法是离屏图层里先画 logo 当遮罩，再用 SrcIn 把亮带裁进它的轮廓 ——
            //    早先做成一条矩形光带，在正方形边界上一刀切断，看着像「一个方块在闪」（用户指出来的）。
            //    ⚠️ 方向按用户要的**左上 → 右下**。实验室那版用 CSS background-position 推动，
            //    位置 0%→100% 实际是把背景往左上拉、亮带反而是右下往左上跑；这里直接按要求写死方向。
            val sw = ((ms - 800f) / 760f).coerceIn(0f, 1f)
            if (sw > 0f && sw < 1f) {
                val k = SHEEN_EASE.transform(sw)
                val a = when {
                    sw < 0.16f -> sw / 0.16f
                    sw > 0.70f -> 1f - (sw - 0.70f) / 0.30f
                    else -> 1f
                }
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        this.alpha = a
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
                ) {
                    val s = size.minDimension
                    drawLogo(bmp, s)
                    // 亮带中心沿对角线从屏外左上走到屏外右下；带宽约 logo 的 21%
                    val c = -0.25f + k * 1.5f
                    val bw = s * 0.21f
                    drawRect(
                        Brush.linearGradient(
                            0f to Color(0x00FFFFFF), 0.5f to Color(0xF2FFFFFF), 1f to Color(0x00FFFFFF),
                            start = Offset(c * s - bw, c * s - bw),
                            end = Offset(c * s + bw, c * s + bw),
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                }
            }
        }
    }
}

private const val FLIP_TOTAL = 1850f
private val FLIP_EASE = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1.02f)
private val SHEEN_EASE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

// ────────────────────────────────────────────────────────────── 聚合

/**
 * 「聚合」：几千个带原色的小方块从屏幕外飞进来拼成 logo，拼完换成清晰的原图。
 *
 * 时间线（共 2100ms）：
 *  · 0～1200+延迟  飞行。每个方块按 `(序号 % 13) × 18ms` 错开起飞，各自 1−(1−p)⁴ 缓入。
 *  · 1350～1850    交叉：方块淡出、清晰原图淡入。
 *  · 1850～2100    停住。
 *
 * ⚠️ **必须交叉淡出**：只淡入原图、方块留着，两层叠在一起边缘全是锯齿
 * （用户当时的原话是「logo 像素是不是太糊了，有很多锯齿」——真因不是分辨率，是两层没让开）。
 */
@Composable
fun SplashConverge(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember { motionOn(ctx) }
    val bmp = remember { ImageBitmap.imageResource(ctx.resources, R.drawable.logo_mark) }
    val dust = remember { sampleDust(bmp) }
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(CONV_TOTAL.toInt(), easing = LinearEasing)) }
    LaunchedEffect(Unit) { delay(if (motion) CONV_TOTAL.toLong() else 300L); onDone() }

    Canvas(Modifier.fillMaxSize().background(LightBg)) {
        val ms = clock.value * CONV_TOTAL
        val w = size.width; val h = size.height
        val s = min(w, h) * 0.60f
        val x0 = (w - s) / 2f; val y0 = (h - s) / 2f
        val cell = s / GRID
        val q = ((ms - 1350f) / 500f).coerceIn(0f, 1f)
        val fade = 1f - q

        if (fade > 0f) {
            val cx = w / 2f; val cy = h / 2f
            val ring = if (w > h) w else h
            for (n in 0 until dust.n) {
                val p = ((ms - (n % 13) * 18f) / 1200f).coerceIn(0f, 1f)
                val e = 1f - (1f - p).pow(4)
                val r = ring * dust.rad[n]
                val sx = cx + dust.cs[n] * r; val sy = cy + dust.sn[n] * r
                val tx = x0 + dust.gx[n] * cell; val ty = y0 + dust.gy[n] * cell
                drawRect(
                    Color(dust.argb[n]),
                    topLeft = Offset(sx + (tx - sx) * e, sy + (ty - sy) * e),
                    size = androidx.compose.ui.geometry.Size(cell * 1.05f, cell * 1.05f),
                    alpha = min(1f, e * 1.4f) * fade,
                )
            }
        }
        if (q > 0f) drawImage(
            bmp,
            dstOffset = IntOffset(x0.roundToInt(), y0.roundToInt()),
            dstSize = IntSize(s.roundToInt(), s.roundToInt()),
            alpha = q,
        )
    }
}

private const val CONV_TOTAL = 2100f
private const val GRID = 96f

/** 采样一次的结果：全 FloatArray/IntArray，画帧时只读不分配。 */
private class Dust(
    val n: Int,
    val gx: FloatArray, val gy: FloatArray,   // 终点：网格坐标，画的时候乘 cell
    val cs: FloatArray, val sn: FloatArray,   // 起点方向
    val rad: FloatArray,                      // 起点半径（屏幕长边的倍数）
    val argb: IntArray,                       // 那个像素的原色 —— logo 是渐变的，聚合过程要带着颜色飞
)

/**
 * 把 logo 采成 96×96 网格上的小方块，只留 alpha > 0.235 的格子（约四千个）。
 * ⚠️ 固定种子：每次开屏的飞行轨迹一模一样，方便对着调；也不会「这次好看下次难看」。
 */
private fun sampleDust(bmp: ImageBitmap): Dust {
    val g = GRID.toInt()
    val px = bmp.toPixelMap()
    val step = bmp.width / g.toFloat()
    val rnd = Random(20260904)
    val cap = g * g
    val gx = FloatArray(cap); val gy = FloatArray(cap)
    val cs = FloatArray(cap); val sn = FloatArray(cap)
    val rad = FloatArray(cap); val argb = IntArray(cap)
    var n = 0
    for (j in 0 until g) {
        for (i in 0 until g) {
            val x = (i * step).toInt().coerceAtMost(bmp.width - 1)
            val y = (j * step).toInt().coerceAtMost(bmp.height - 1)
            val c = px[x, y]
            if (c.alpha <= 0.235f) continue
            gx[n] = i.toFloat(); gy[n] = j.toFloat()
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            cs[n] = cos(a); sn[n] = sin(a)
            rad[n] = 0.5f + rnd.nextFloat() * 0.6f
            argb[n] = c.copy(alpha = 1f).toArgb()
            n++
        }
    }
    return Dust(n, gx, gy, cs, sn, rad, argb)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(),
)

// ────────────────────────────────────────────────────────────── 显影

/**
 * 「显影」：像相纸显影一样，logo 从底部那个尖端（花瓣的根）被一圈不断长大的光扫出来，扫过的地方才显形。
 *
 * ⚠️ **只在深色主题播**。它靠的是暗底上那圈蓝白色的亮边，浅底上既看不见亮边、
 * 「显影」这个隐喻也不成立（用户 2026-09-04 定的规则）。
 *
 * 时间线（共 2000ms）：
 *  · 0～1200     遮罩圆从根部长到盖满，边缘带一圈越来越淡的蓝白亮边。
 *  · 1150～1600  整体轻轻一亮（叠加混合，正弦进出，不刺眼）。
 *  · 1600～2000  停住。
 */
@Composable
fun SplashDevelop(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember { motionOn(ctx) }
    val bmp = remember { ImageBitmap.imageResource(ctx.resources, R.drawable.logo_mark) }
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(DEV_TOTAL.toInt(), easing = LinearEasing)) }
    LaunchedEffect(Unit) { delay(if (motion) DEV_TOTAL.toLong() else 300L); onDone() }

    // 深色皮肤的 surface —— 用 App 自己的底色，播完接上真界面时不会跳一下
    val bg = MaterialTheme.colorScheme.surface
    Canvas(Modifier.fillMaxSize().background(bg)) {
        val ms = clock.value * DEV_TOTAL
        val s = min(size.width, size.height) * 0.62f
        val x = (size.width - s) / 2f; val y = (size.height - s) / 2f
        val p = (ms / 1200f).coerceIn(0f, 1f)
        val e = 1f - (1f - p).pow(3)
        // 扫描圆心 = logo 底部靠中偏右那个尖端（花瓣收拢的根），不是几何中心
        val cx = x + s * 0.58f; val cy = y + s * 0.90f
        val r = e * s * 1.35f

        if (r > 0f) {
            clipPath(Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }) {
                drawImage(
                    bmp,
                    dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize(s.roundToInt(), s.roundToInt()),
                )
            }
            if (p < 1f) drawCircle(
                Color(0xFF96B4FF), r, Offset(cx, cy),
                alpha = 0.5f * (1f - p), style = Stroke(3f * density), blendMode = BlendMode.Plus,
            )
        }
        val q = ((ms - 1150f) / 450f).coerceIn(0f, 1f)
        if (q > 0f) drawImage(
            bmp,
            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
            dstSize = IntSize(s.roundToInt(), s.roundToInt()),
            alpha = 0.25f * sin(q * PI.toFloat()), blendMode = BlendMode.Plus,
        )
    }
}

private const val DEV_TOTAL = 2000f

/** 把 logo 按「短边的多少」居中画出来 —— 翻面那两层（本体 + 闪光遮罩）要用同一个矩形，否则遮罩会错位。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLogo(bmp: ImageBitmap, s: Float) {
    val x = (size.width - s) / 2f; val y = (size.height - s) / 2f
    drawImage(
        bmp,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(s.roundToInt(), s.roundToInt()),
    )
}
