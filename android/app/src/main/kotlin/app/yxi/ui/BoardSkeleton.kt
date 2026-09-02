package app.yxi.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 还没连上、也没有缓存时的看板骨架。
 *
 * ⚠️ **为什么不是一个居中转圈。** 用户报的原话是「刚进去的时候会显示这个很奇怪啊，
 * 其实是启动的」—— 他看到的是**一块空白**加一行「连接中…」，
 * 十几个明明跑着的会话一个不见，第一反应是「会话没了」而不是「还没加载完」。
 * 转圈能说明「在忙」，但说不明白**等出来的是什么形状**。
 * 骨架卡片跟真卡片同形同位，真数据来了是「填进去」不是「换一屏」，中间没有跳变。
 *
 * ⚠️ **只在真的一无所有时才画。** 有缓存（[app.yxi.agent.Recent] 落盘那份）就直接摆
 * 旧数据 + 标「N 分钟前」—— 旧但真实的东西永远比骨架有用（TROUBLESHOOTING #170）。
 *
 * ⚠️ **系统关了动画就不扫光**，只留静态灰块。这不是体贴，是无障碍要求 ——
 * 循环扫光对前庭功能障碍的用户是持续的动态刺激。
 */
@Composable
fun BoardSkeleton(modifier: Modifier = Modifier, cards: Int = 4) {
    val ctx = LocalContext.current
    val motion = remember { motionOn(ctx) }
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val glow = MaterialTheme.colorScheme.surfaceContainerHighest

    // 扫光位置。1600ms 一轮 —— 快了显得焦躁，慢了看着像卡死。
    val shift by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "sweep",
    )

    // ⚠️ 扫光用渐变的**端点**做，端点按每块自己的像素宽度算 ——
    // 固定像素的话，宽卡片上光走到三分之一就没了。
    val sweep = Modifier.drawWithCache {
        val b = if (!motion) SolidColor(base) else Brush.linearGradient(
            listOf(base, glow, base),
            start = Offset(size.width * shift, 0f),
            end = Offset(size.width * (shift + 0.7f), 0f),
        )
        onDrawBehind { drawRect(b) }
    }

    fun block(w: Dp?, h: Dp, r: Dp, m: Modifier = Modifier) = m
        .then(if (w == null) Modifier.fillMaxWidth() else Modifier.width(w))
        .height(h).clip(RoundedCornerShape(r)).then(sweep)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 分组标题那行也占上位子，否则真数据来了整列会往下跳一格
        Box(block(96.dp, 14.dp, 7.dp, Modifier.padding(4.dp, 6.dp, 0.dp, 2.dp)))
        // 高度错开：真看板上「等你」的卡片比「干活中」高一行（多一句「已经等了 N 分钟」）。
        // 一水儿等高反而一眼就看出是假的。
        repeat(cards) { i -> Box(block(null, if (i % 3 == 0) 104.dp else 84.dp, 22.dp)) }
    }
}

/**
 * 系统里关了动画就别扫光。
 * ⚠️ 跟 `Switcher` 里那份是同一条无障碍要求，只是那边是私有的。
 */
private fun motionOn(ctx: Context): Boolean = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)
