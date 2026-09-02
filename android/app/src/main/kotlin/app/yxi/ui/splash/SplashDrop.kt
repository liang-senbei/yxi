package app.yxi.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.yxi.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏方案「Y 落下，字展开」—— 毛笔 Y 从天而降砸出一圈涟漪，落稳之后「unxi」从它身后向右铺开。
 *
 * 时间线（毫秒）：
 *  · 0～450    Y 从最终位置上方 45% 屏高处加速落下（重力曲线），同时 −12° 转正、alpha 0.3→1
 *  · 450       触地。位移交给 spring 接着跑（带着落地速度先下沉再回弹两下，约 700 停稳），
 *              同一帧在 Y 底部起一圈粉彩蓝涟漪（500ms 扩到整字宽的 60%，alpha 0.35→0）
 *  · 700～1150 wordmark 用 clipRect 从 Y 的右缘向右展开；logo_y 同步淡出（wordmark 自带 Y，不能叠成双份）
 *  · 1250～1900 整体 1.0→1.02→1.0 呼吸一下，停住
 *  · 2000      onDone()
 *
 * 全程只画一张 Canvas：底色 + 底光 + 涟漪 + 两张图，动画值都在 draw 阶段读，不触发重组。
 * 系统关了动画（ANIMATOR_DURATION_SCALE == 0）：所有值从终值起步、不跑时间线，300ms 后 onDone()。
 */
@Composable
fun SplashDrop(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) { delay(if (motion) TOTAL_MS else 300L); done() }

    // 关了动画就全部从终值起步 —— 静态画面和动画版的最后一帧完全一致
    val glow = remember { Animatable(if (motion) 0f else GLOW_A) }       // 底光 alpha
    val drop = remember { Animatable(if (motion) -FALL else 0f) }        // Y 的纵向位移，单位屏高倍数；负 = 在上方
    val tilt = remember { Animatable(if (motion) -12f else 0f) }         // Y 的旋转角
    val fade = remember { Animatable(if (motion) 0.3f else 1f) }         // Y 落下过程的 alpha
    val ripple = remember { Animatable(0f) }                              // 涟漪进度 0→1
    val reveal = remember { Animatable(if (motion) 0f else 1f) }         // 字展开进度 0→1，同时驱动 Y 淡出
    val breath = remember { Animatable(1f) }                              // 整体缩放

    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        launch { glow.animateTo(GLOW_A, tween(1200)) }
        launch {
            // 落下用 tween 走重力曲线，触地后把落地速度交给 spring 做回弹（animateTo 默认沿用当前速度）。
            // 不直接用 spring 从上方拉：StiffnessMedium 从 45% 屏高起步 60ms 就砸到底、250ms 停稳，没有「落」的过程；
            // 换软弹簧则回弹深达落差的 16%（一百多像素）而且 700ms 时还在晃，展开那一刻 Y 和 wordmark 对不齐。
            drop.animateTo(0f, tween(FALL_MS, easing = Gravity))
            launch { ripple.animateTo(1f, tween(500, easing = LinearOutSlowInEasing)) }   // 触地同帧起涟漪
            // 停稳阈值换成约 1px：默认 0.01 在「屏高倍数」这个单位里是二十多像素，收尾会跳一下
            drop.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium, visibilityThreshold = 0.0005f))
        }
        launch { tilt.animateTo(0f, tween(FALL_MS, easing = FastOutSlowInEasing)) }   // 触地时刚好转正
        launch { fade.animateTo(1f, tween(350)) }
        delay(700)
        launch { reveal.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        delay(550)
        breath.animateTo(1.02f, tween(300, easing = FastOutSlowInEasing))
        breath.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
    }

    val wordmark = painterResource(R.drawable.logo_wordmark)
    val yGlyph = painterResource(R.drawable.logo_y)
    val surface = MaterialTheme.colorScheme.surface
    val tint = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)   // 黑字按主题染色，深色主题下变白

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        // 版面：整字宽 = 58% 屏宽，居中，中心在 45% 屏高。Y 跟整字同高、同左缘，宽占 218/564 —— 两张图正好重合
        val wmW = w * 0.58f
        val wmH = wmW * 349f / 564f
        val yW = wmW * 218f / 564f
        val left = (w - wmW) / 2f
        val top = h * 0.45f - wmH / 2f

        drawRect(surface)
        // 底光：屏底中点为圆心、半径一屏宽的粉彩蓝径向渐变，慢慢亮起来
        drawRect(
            Brush.radialGradient(
                listOf(Pastel.copy(alpha = glow.value), Pastel.copy(alpha = 0f)),
                center = Offset(w / 2f, h), radius = w,
            ),
        )
        // 涟漪：圆心 = Y 的落点（底边中点），半径 0→整字宽的 60%，alpha 0.35→0；画在字下面
        val rp = ripple.value
        if (rp > 0f && rp < 1f) drawCircle(
            Pastel, radius = rp * wmW * 0.6f, center = Offset(left + yW / 2f, top + wmH),
            alpha = 0.35f * (1f - rp), style = Stroke(2.dp.toPx()),
        )
        // 呼吸：以整字中心为轴缩放
        scale(breath.value, pivot = Offset(w / 2f, h * 0.45f)) {
            // 字：右边界从 Y 的右缘推到整字右缘。reveal == 0 时不画 —— Y 还在天上，不能提前露出 wordmark 里那个 Y
            val rv = reveal.value
            if (rv > 0f) clipRect(left, top, left + yW + rv * (wmW - yW), top + wmH) {
                translate(left, top) { with(wordmark) { draw(Size(wmW, wmH), colorFilter = tint) } }
            }
            // Y：位移 + 绕自身中心旋转。展开一开始就同步淡出，重合处始终只有 wordmark 那一份是实的
            val yA = fade.value * (1f - rv)
            if (yA > 0f) withTransform({
                translate(left, top + drop.value * h)
                rotate(tilt.value, pivot = Offset(yW / 2f, wmH / 2f))
            }) { with(yGlyph) { draw(Size(yW, wmH), alpha = yA, colorFilter = tint) } }
        }
    }
}

private const val TOTAL_MS = 2000L   // 总时长；关了动画时是 300
private const val FALL_MS = 450      // 落下用时 = 触地时刻
private const val GLOW_A = 0.18f     // 底光最亮时的 alpha
// 起点高度，屏高倍数。手机比例下第一帧会露出 Y 的下半截（alpha 0.3 的虚影）；想完全藏到屏外调到 0.55 左右
private const val FALL = 0.45f
private val Pastel = Color(0xFF9EC8F0)
// 重力：ease-in-quad。末速 = 2 倍平均速度，spring 接手时就用它，回弹深度约 2.8% 屏高
private val Gravity = CubicBezierEasing(0.11f, 0f, 0.5f, 0f)
