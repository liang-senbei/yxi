package app.yxi.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.yxi.R
import kotlinx.coroutines.delay

/**
 * 开屏方案「笔触书写」：像一支毛笔从左往右把「Yunxi」写出来 —— 墨迹带着一点淡蓝的水光渗开，
 * 写完轻轻落定，停一拍，进 App。
 *
 * 时间线（系统动画开着时，共 1900ms）：
 *  · 0～1100ms    从左往右揭开字迹，前沿是柔的（像墨在渗），字底下一团淡蓝光晕跟着笔尖走
 *  · 1100～1500ms 整体 1.03 → 1.0 落定，光晕淡到 0.15
 *  · 1500～1900ms 停住，然后 [onDone]
 *
 * 系统关了动画：直接给最终画面，300ms 后 [onDone]。[onDone] 只会调一次。
 */
@Composable
fun SplashBrush(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // 跟 ThinkingGlow 一样的判法：用户在系统里关了动画，就别演，直接到位
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    // 调用方重组时可能换一个 onDone 进来；效应结束时拿最新的那个，别把首次那个捏死
    val done by rememberUpdatedState(onDone)

    // reveal：0 = 一笔没写，1 = 写完；settle：0 = 还浮着（1.03 倍、光晕 0.35），1 = 落定
    val reveal = remember { Animatable(0f) }
    val settle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (motion) {
            reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
            settle.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            delay(400)
        } else {
            reveal.snapTo(1f); settle.snapTo(1f)
            delay(300)
        }
        done()
    }

    val painter = painterResource(R.drawable.logo_wordmark)
    val ink = MaterialTheme.colorScheme.onSurface
    val glow = Color(0xFF9EC8F0)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // 只占上面 90% 高度再居中 → 字的中心正好落在 45% 高度处，不用换算 dp
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.9f), contentAlignment = Alignment.Center) {
            Image(
                painter, contentDescription = "Yunxi",
                colorFilter = ColorFilter.tint(ink),   // 素材是纯黑字，染成 onSurface 才能深浅主题都对
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    // ⚠️ 只定宽不定高的话 Image 不会按比例把高撑起来（会按素材原始像素画），要显式给比例
                    .aspectRatio(painter.intrinsicSize.run { width / height })
                    // 光晕画在离屏层外面（链上排在 graphicsLayer 前面）：它垫在字底下，且不能被下面的蒙版切
                    .drawBehind {
                        val p = reveal.value
                        // 半径比字高还大一圈；用 drawCircle 不用 drawRect —— 后者只填到本 layout 的框，光会被切成方的
                        val r = size.width * 0.45f
                        // 起笔时从 0 淡入（p 走到 1/3 时到满），不然第一帧凭空冒出一团光；落定后退到 0.15
                        val a = (0.35f - 0.20f * settle.value) * minOf(1f, p * 3f)
                        // 团心跟着笔尖从 25% 走到 75%：「轻轻移动」，不是从头扫到尾
                        val c = Offset(size.width * (0.25f + 0.5f * p), size.height * 0.5f)
                        drawCircle(
                            Brush.radialGradient(listOf(glow.copy(alpha = a), Color.Transparent), center = c, radius = r),
                            r, c,
                        )
                    }
                    // 离屏层：下面 drawWithContent 里的 DstIn 只作用于这一层（字），不会把背景和光晕也蒙掉
                    .graphicsLayer {
                        val s = 1.03f - 0.03f * settle.value   // 写的时候略大，落定回 1.0
                        scaleX = s; scaleY = s
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // 揭开 + 前沿柔化一步做完：一条横向 alpha 渐变（左边 1、前沿处 0，两头 Clamp）
                        // 以 DstIn 乘到字上 —— 前沿那一窄条渐渐透明，像墨正在渗出来，而不是一条硬切线。
                        // 没用「盖一条 surface 色条」的做法：那条会把底下的光晕也盖出一道硬边。
                        val f = size.width * 0.10f                 // 柔边宽度：字宽的一成
                        val x = (size.width + f) * reveal.value    // 前沿位置；多走一个 f，写完时柔边完全出画，末笔不发虚
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = x - f, endX = x),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
    }
}
