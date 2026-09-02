package app.yxi.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.yxi.R
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏方案「墨迹落定」—— 一笔墨从空中落到纸上、渗开、定住，最后一道光掠过笔画。
 *
 * 时间线（系统开着动画时，共 1900ms）：
 *  · 0～650    字从 1.22 倍、全透明收到 1 倍、不透明。缩放走欠阻尼弹簧，会略缩过头再弹回，像落纸弹一下。
 *  · 60～950   字后面叠一层同一张图的「墨晕」：淡（0.18）、大 6%、同一个弹簧但晚 60ms 起步 ——
 *              它总比字慢半拍、总比字大一圈，看起来就是墨在纸上渗开；字落定（650）后 300ms 内淡掉。
 *  · 800～1300 一道窄斜光从字的左下扫到右上，只在笔画内可见（离屏合成 + SrcAtop）。
 *  · 1300～1900 停住，然后 onDone()。
 *
 * 系统关了动画：直接给终态，300ms 后 onDone()。onDone 只会被调一次。
 */
@Composable
fun SplashInk(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // 跟 ThinkingGlow 同一套判断：用户在开发者选项里把动画时长调成 0，就一步到位
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 父层可能在动画中途换 lambda；到点时调最新的那个
    val done by rememberUpdatedState(onDone)

    // 关了动画就直接站在终态上；开着才从「空中」起步
    val inkScale = remember { Animatable(if (motion) 1.22f else 1f) }
    val inkAlpha = remember { Animatable(if (motion) 0f else 1f) }
    val haloScale = remember { Animatable(1.22f * 1.06f) }
    val haloAlpha = remember { Animatable(0f) }
    // 0 = 整条光带还在字的左下角外面，1 = 已经扫出右上角；两头都不可见
    val sheen = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (!motion) {
            delay(300)
            done()
            return@LaunchedEffect
        }
        // 落纸的那一下：欠阻尼弹簧，先缩过头一点再弹回 1.0
        val settle = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
        launch { inkScale.animateTo(1f, settle) }
        launch { inkAlpha.animateTo(1f, tween(400)) }
        // 墨晕：同一个弹簧，晚 60ms 起步 —— 这 60ms 的滞后就是「渗开」
        launch {
            delay(60)
            haloScale.animateTo(1.06f, settle)
        }
        launch {
            delay(60)
            haloAlpha.animateTo(0.18f, tween(340))   // 跟字的 alpha 一起在 400ms 到齐
            delay(250)                                // 停到 650：字落定了
            haloAlpha.animateTo(0f, tween(300))      // 950 前淡没
        }
        // 高光：800→1300 匀速扫过去（缓动会让它在字的右半边磨蹭）
        launch {
            delay(800)
            sheen.animateTo(1f, tween(500, easing = LinearEasing))
        }
        delay(1900)
        done()
    }

    val wordmark = painterResource(R.drawable.logo_wordmark)
    val paper = MaterialTheme.colorScheme.surface
    // 素材是黑字透明底，着色成 onSurface 才能深浅主题都对
    val tint = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(paper)
            // 纸面顶部压一点粉彩蓝、往下消失：静止的，只是让「纸」不那么死白
            .background(Brush.verticalGradient(0f to WASH.copy(alpha = 0.10f), 1f to WASH.copy(alpha = 0f))),
    ) {
        // 字宽 = 屏宽 58%，按原图 564×349 定高；水平居中，中心抬到 45% 高度处
        val w = maxWidth * 0.58f
        val place = Modifier
            .align(Alignment.Center)
            .offset(y = -maxHeight * 0.05f)
            .size(w, w * (349f / 564f))

        // 墨晕：画在字的后面。缩放 / 透明度放在 graphicsLayer 里读，只重绘不重组
        Image(
            painter = wordmark,
            contentDescription = null,
            modifier = place.graphicsLayer {
                scaleX = haloScale.value
                scaleY = haloScale.value
                alpha = haloAlpha.value
            },
            colorFilter = tint,
        )
        // 字 + 高光。Offscreen：先把字画进一块离屏缓冲，再用 SrcAtop 画光带 ——
        // 光只落在字有 alpha 的地方，字本身保留。（SrcIn 会把光带之外的字一并擦掉，所以不用它）
        Image(
            painter = wordmark,
            contentDescription = "Yunxi",
            modifier = place
                .graphicsLayer {
                    scaleX = inkScale.value
                    scaleY = inkScale.value
                    alpha = inkAlpha.value
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val t = sheen.value
                    if (t > 0f && t < 1f) drawSheen(t, paper)
                },
            colorFilter = tint,
        )
    }
}

/** 背景那层极淡的粉彩蓝 */
private val WASH = Color(0xFF9EC8F0)

// 光带从竖直往右下倒 20°（像反斜杠），沿它的法线从字的左下角外扫到右上角外。
// 法线 (NX, NY) 指向右上；屏幕坐标 y 朝下，所以 NY 取负。
private val NX = cos(Math.toRadians(20.0)).toFloat()
private val NY = -sin(Math.toRadians(20.0)).toFloat()

/** 在当前绘制区域上画一道斜高光，t 从 0 到 1 是它从左下扫到右上的进度。坐标是字本身的像素坐标（缩放在外层 layer 上）。 */
private fun DrawScope.drawSheen(t: Float, color: Color) {
    val half = size.width * 0.125f                 // 半带宽：整条带 ≈ 字宽 25%
    // 四个角在法线上的投影：最小是左下角 (0, h)，最大是右上角 (w, 0)。
    // 各再让出半带宽，保证 t = 0 和 t = 1 时整条带都在字外，不用另做显隐开关
    val lo = size.height * NY - half
    val hi = size.width * NX + half
    val p = lo + (hi - lo) * t                     // 带子中心在法线上的位置
    val c = Offset(NX * p, NY * p)                 // 法线上取一点即可：带子沿长边无限延伸，只看法向分量
    val d = Offset(NX * half, NY * half)
    drawRect(
        // 两端用同色 alpha 0，别用 Color.Transparent：透明黑插值到浅色中间会发灰
        brush = Brush.linearGradient(
            0f to color.copy(alpha = 0f),
            0.5f to color.copy(alpha = 0.55f),
            1f to color.copy(alpha = 0f),
            start = c - d,
            end = c + d,
        ),
        blendMode = BlendMode.SrcAtop,
    )
}
