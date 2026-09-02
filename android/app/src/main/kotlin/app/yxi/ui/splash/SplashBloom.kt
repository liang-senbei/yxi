package app.yxi.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.yxi.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏方案「光晕绽放」—— 对话页那四团粉彩光从屏幕中心绽放开，毛笔 Y 先在光里弹出来，再化成整个 Yunxi。
 *
 * 时间线（毫秒）：
 *  · 0→700     四团光从中心绽放：半径从 0 长到屏宽的 0.9～1.15 倍，四个团心各自往不同方向漂开
 *  · 300→800   毛笔 Y 在中心长出来：scale 0.6→1、alpha 0→1，spring 带一点点过冲
 *  · 900→1250  整幅 Yunxi 淡入、Y 同时淡出 —— 两张图的 Y 画在同一个位置，看起来是 Y「长」成了整个字
 *  · 1500→1950 光晕整体压到 0.12，字停住
 *  · 2000      onDone（只调一次）
 *
 * 系统关了动画：直接给最终画面，300ms 后 onDone。
 * 播完所有量都停在最后一帧（实验室的播放器播完是停着看的，别复位）。
 */
@Composable
fun SplashBloom(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 五个量各自一个 Animatable，时间线靠 delay 排。关了动画就直接从最终值起步，一帧都不动。
    val bloom = remember { Animatable(if (motion) 0f else 1f) }        // 光晕绽放进度 0→1：半径和漂移都跟它
    val glow = remember { Animatable(if (motion) 1f else 0.12f) }      // 光晕整体亮度，收尾压到 0.12
    val yScale = remember { Animatable(if (motion) 0.6f else 1f) }
    val yAlpha = remember { Animatable(0f) }                            // 最终画面里 Y 单独那张是隐掉的
    val wordAlpha = remember { Animatable(if (motion) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (!motion) { delay(300); onDone(); return@LaunchedEffect }
        launch { bloom.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { delay(300); yAlpha.animateTo(1f, tween(300)) }
        // stiffness 150 + dampingRatio 0.6：约 620ms 处过冲到 1.04，800ms 前定住，赶在下面 900ms 的淡入之前
        launch { delay(300); yScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 150f)) }
        launch { delay(900); wordAlpha.animateTo(1f, tween(350)) }
        launch { delay(900); yAlpha.animateTo(0f, tween(350)) }
        launch { delay(1500); glow.animateTo(0.12f, tween(450)) }
        delay(2000)
        onDone()
    }

    val tint = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)   // 黑字素材按主题染色，深色主题就是白字
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // 字的位置：宽占屏宽 58%，水平居中，中心落在 45% 高度处（略偏上，视觉重心才是正中）。
        // Y 单独那张按同样的高度贴在 wordmark 左边缘 —— wordmark 里的 Y 就在最左边、宽 218 / 564。
        val wordW = maxWidth * 0.58f
        val wordH = wordW * (349f / 564f)
        val yW = wordH * (218f / 349f)
        val left = (maxWidth - wordW) / 2
        val top = maxHeight * 0.45f - wordH / 2

        // 光晕：四团径向渐变铺满整屏。团心 = 屏幕中心 + 各自的漂移 × 进度，半径 = 目标半径 × 进度。
        // 状态只在画的时候读，动画期间不重组。
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val center = Offset(w / 2, h / 2)
            val t = bloom.value; val amount = glow.value
            for (b in BLOBS) {
                drawRect(
                    Brush.radialGradient(
                        // 尾色用同色透明，不用 Color.Transparent：往透明黑插值会在浅色底上泛灰
                        listOf(b.color.copy(alpha = b.alpha * amount), b.color.copy(alpha = 0f)),
                        center = center + Offset(w * b.dx, h * b.dy) * t,
                        // Android 的 RadialGradient 半径必须大于 0，进度 0 那一帧兜个底
                        radius = (w * b.radius * t).coerceAtLeast(1f),
                    )
                )
            }
        }
        Image(
            painterResource(R.drawable.logo_wordmark), null,
            Modifier.offset(left, top).size(wordW, wordH).graphicsLayer { alpha = wordAlpha.value },
            colorFilter = tint,
        )
        // Y 绕自己的中心缩放（graphicsLayer 默认原点），长大的过程中位置不飘
        Image(
            painterResource(R.drawable.logo_y), null,
            Modifier.offset(left, top).size(yW, wordH).graphicsLayer {
                alpha = yAlpha.value; scaleX = yScale.value; scaleY = yScale.value
            },
            colorFilter = tint,
        )
    }
}

/** 一团光：颜色、漂移方向（dx 按屏宽、dy 按屏高）、目标半径（按屏宽）、中心透明度 */
private class Blob(val color: Color, val dx: Float, val dy: Float, val radius: Float, val alpha: Float)

// 四团的方向、距离、半径、透明度全都不一样 —— 一样的话看起来是一块色板在放大，不是光在绽放
private val BLOBS = listOf(
    Blob(Color(0xFF9EC8F0), -0.30f, -0.22f, 1.15f, 0.33f),   // 蓝，往左上，最大最亮
    Blob(Color(0xFFB4D6F5), 0.36f, -0.05f, 1.00f, 0.28f),    // 浅蓝，往右
    Blob(Color(0xFFA8D8E8), -0.12f, 0.26f, 0.95f, 0.30f),    // 蓝青，往左下
    Blob(Color(0xFF8FD8C6), 0.28f, 0.18f, 0.90f, 0.25f),     // 青，往右下，最小最淡
)
