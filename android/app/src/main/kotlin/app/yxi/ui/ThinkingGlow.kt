package app.yxi.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 对话页的背景光 —— **照着用户录的 参考款 全流程逐帧抄的**（帧表见 TROUBLESHOOTING #191）。
 *
 * 它有三种状态，两次迁移：
 *  · **待机 / 打字**：一团很淡的蓝光挂在**底部**、输入框上方 —— 是「输入区的聚光」，
 *    参考款打字全程都有（帧 0→14s），跟键盘一起走。
 *  · **发送 → 思考**：那团光**脱离底部往上迁**，650ms 铺满顶部（帧 15.0→15.4s），
 *    然后**色相循环**：蓝→青→绿→黄→橙→粉，约 4 秒一轮（帧 15.6→19.0s）。
 *  · **回答到了**：光退到三四成，段落淡入的时候它渐渐没了（帧 19.8→20.2s）。
 *
 * ⚠️ **等你拍板那一档不循环，定在琥珀。** 参考款没有「等你」这个状态，我们有 ——
 * 而且它是这个 App 里最要紧的一个信号（看板上同一个色）。抄观感，不抄掉自己的信息层。
 *
 * ⚠️ **四团光各自独立的相位、周期互不成整数倍**（#186），不然是一块色板在平移。
 * ⚠️ **系统关了动画**：不迁移（直接到位）、不循环（干活中定在青）、不流动。
 */
@Composable
fun ThinkingGlow(
    busy: Boolean,
    waiting: Boolean,
    /** 回答正在到达（最后一条是助手的、且还在忙）—— 光该退下去让位给正文 */
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    val active = busy || waiting

    // 亮度：待机 0.55（聚光，别抢）；思考 1.0；回答到达 0.35；关掉 0
    val target = when {
        waiting -> 1f
        busy && streaming -> 0.35f
        busy -> 1f
        else -> 0.55f
    }
    val amount by animateFloatAsState(
        target, tween(if (active) 900 else 1400, easing = LinearEasing), label = "glow",
    )
    // 位置：0 = 底部聚光，1 = 铺满顶部。650ms，跟 参考款迁移那一下同量级
    val lift by animateFloatAsState(
        if (active) 1f else 0f,
        if (motion) tween(650, easing = FastOutSlowInEasing) else tween(0),
        label = "lift",
    )

    val tr = rememberInfiniteTransition(label = "flow")
    val a by tr.wave(7300); val b by tr.wave(9100); val c by tr.wave(11700); val d by tr.wave(13900)
    // 色相循环：6 秒转一圈（参考款约 4 秒扫完蓝→粉，我们慢一点，它抢眼的时候正文在读）
    val hueT by tr.animateFloat(
        0f, 1f, InfiniteRepeatableSpec(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "hue",
    )
    val pa = if (motion) a else .5f; val pb = if (motion) b else .5f
    val pc = if (motion) c else .5f; val pd = if (motion) d else .5f

    // 配色
    val hues: List<Color> = when {
        waiting -> listOf(Color(0xFFFFC46B), Color(0xFFFFAF9B), Color(0xFFFFE0A3), Color(0xFFFFD1B0))
        busy && motion -> {
            // 蓝(210)→青(180)→绿(120)→黄(60)→橙(30)→粉(330)：色相**递减**着走 300°
            // ⚠️ 方向别弄反：加的话是蓝→紫→粉→红，跟录像里的顺序正好相反（自己渲染核对过）
            val base = 210f - 300f * hueT
            List(4) { i -> pastel((base + i * 22f) % 360f) }
        }
        busy -> listOf(Color(0xFF8FD8C6), Color(0xFF9EC8F0), Color(0xFFC9E6D8), Color(0xFFA8D8E8))
        else -> listOf(Color(0xFF9EC8F0), Color(0xFFB4D6F5), Color(0xFFC9E0F7), Color(0xFFA8D8E8))   // 待机：蓝
    }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        // 顶部布局（思考）和底部布局（待机）各一套团心，按 lift 插值。
        // 待机时四团都缩到底边附近、半径小一圈 —— 一个聚光，不是四团。
        fun y(top: Float, bottom: Float) = h * (bottom + (top - bottom) * lift)
        fun r(top: Float, bottom: Float) = w * (bottom + (top - bottom) * lift)
        val blobs = listOf(
            Triple(Offset(w * (0.10f + 0.55f * pa), y(0.04f + 0.10f * pc, 0.98f)), r(1.05f + 0.12f * pb, 0.70f), 0.28f),
            Triple(Offset(w * (0.95f - 0.60f * pb), y(0.16f + 0.14f * pd, 1.02f)), r(0.90f + 0.12f * pc, 0.55f), 0.20f),
            Triple(Offset(w * (0.30f + 0.50f * pc), y(0.30f + 0.12f * pa, 1.00f)), r(0.78f + 0.14f * pd, 0.45f), 0.15f),
            Triple(Offset(w * (0.70f - 0.55f * pd), y(0.10f + 0.16f * pb, 1.04f)), r(0.85f + 0.10f * pa, 0.50f), 0.13f),
        )
        blobs.forEachIndexed { i, (center, radius, alpha) ->
            drawRect(
                Brush.radialGradient(
                    listOf(hues[i].copy(alpha = alpha * amount), Color.Transparent),
                    center = center, radius = radius,
                )
            )
        }
    }
}

/** HSL → 粉彩色。饱和 0.62、亮度 0.80：参考款那种「有颜色但不脏」的档位。 */
private fun pastel(hue: Float): Color {
    val s = 0.62f; val l = 0.80f
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = l - c / 2
    val (r1, g1, b1) = when {
        hue < 60 -> Triple(c, x, 0f); hue < 120 -> Triple(x, c, 0f); hue < 180 -> Triple(0f, c, x)
        hue < 240 -> Triple(0f, x, c); hue < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m)
}

/** 0→1→0 三角波。⚠️ `Reverse` 不用 `Restart`：到头瞬间跳回起点在大面积色块上像闪了一下。 */
@Composable
private fun InfiniteTransition.wave(ms: Int): State<Float> = animateFloat(
    0f, 1f, InfiniteRepeatableSpec(tween(ms, easing = LinearEasing), RepeatMode.Reverse), label = "w$ms",
)

/**
 * 输入框的底：跟 [ThinkingGlow] **同一套色相**、同样 6 秒一圈，横向淡淡地铺一层。
 * 光晕忙的时候在页面顶部，输入框离得远，用户说「输入框也要是渐变背景」—— 这层就是给它的。
 * ⚠️ 透明度压得很低（浅色 0.38 / 深色 0.22）：它是底色不是主角，字要读得清。
 */
@Composable
fun glowBrush(busy: Boolean, waiting: Boolean): Brush {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    val tr = rememberInfiniteTransition(label = "pill")
    val hueT by tr.animateFloat(
        0f, 1f, InfiniteRepeatableSpec(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "pillHue",
    )
    val drift by tr.wave(9100)
    val light = !androidx.compose.foundation.isSystemInDarkTheme() ||
        app.yxi.ui.theme.LocalPalette.current.light
    val a = if (light) 0.38f else 0.22f
    val hues: List<Color> = when {
        waiting -> listOf(Color(0xFFFFC46B), Color(0xFFFFAF9B), Color(0xFFFFE0A3))
        busy && motion -> List(3) { i -> pastel((210f - 300f * hueT + i * 28f + 360f) % 360f) }
        busy -> listOf(Color(0xFF8FD8C6), Color(0xFF9EC8F0), Color(0xFFC9E6D8))
        else -> listOf(Color(0xFF9EC8F0), Color(0xFFC9E0F7), Color(0xFFA8D8E8))
    }
    val d = if (motion) drift else .5f
    return Brush.horizontalGradient(
        hues.map { it.copy(alpha = a) },
        startX = -400f * d, endX = 1400f + 400f * d,
    )
}
