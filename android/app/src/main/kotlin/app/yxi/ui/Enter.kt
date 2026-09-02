package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 新出现的那一条从下面**滑上来 + 淡入**。
 *
 * ⚠️ 抄的是 参考款发送那一下：输入框里的字飞上去变成气泡（帧表 14.4→15.0s，约 400ms）。
 * 真正的共享元素动画要跨两个布局搬同一段文字，代价大；从下滑入 + 淡入在手机上
 * 读起来就是「它从输入框上去了」，够用。回答的每一块也走它 —— 参考款那边
 * 每段是先灰后实地淡入（19.2→19.6s），同一个手势。
 *
 * ⚠️ **只给「加载完之后才出现」的条目用。** 进对话页时历史几百条一起滑入是灾难 ——
 * 调用方用 [animate] 控制，初始那批传 false。
 * ⚠️ 系统关了动画就直接出现 —— [animate] 传 false 即可，这里不重复判。
 */
@Composable
fun EnterUp(animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) { content(); return }
    // 第一次组合时 false→true，触发进入动画；之后一直 true
    val st = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = st,
        enter = fadeIn(tween(380)) +
            slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 3 },
    ) { content() }
}
