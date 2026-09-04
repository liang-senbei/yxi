package app.yxi.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Account
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * 兑换成功动效「玻璃药丸展开」—— 老板 2026-09-04 选定，规格与参考实现由 cc-logto_yxi 给：
 * `logto_yxi/design/redeem-success.md` + `redeem-success.html`。
 *
 * 转圈 → 打勾 → **药丸横向展开**，欢迎语从里面长出来。
 * 那颗药丸**就是对话页输入框那颗 [GlassPill]**，不是另造的装饰物件 ——
 * 三个循环（呼吸 2.6s / 光带 3.8s / 色相 6s）展开之后继续跑。
 *
 * ⚠️ **流动层不透明度用 0.20，不是 design/STYLE.md §2.2 里的 0.38。**
 * 0.38 那个值是盖在**整页光晕**上的；药丸自己近白底，再叠 0.38 的饱和档位色
 * 会变成一颗实心橙药丸（对方第一版就是这么翻车的）。
 *
 * ⚠️ **展开宽度按文字实测算，别写死倍数** ——「Yunxi Ultra」比「Yunxi Pro」长，写死会顶边。
 *
 * ⚠️ **重兑（replay）不放动效**：同一张码再兑一次没有加任何东西，庆祝它是在骗人。
 */
@Composable
fun RedeemSuccess(r: Account.Redeemed, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val motion = remember { motionOn(ctx) }

    // 档位色。余额券跟**当前档位**走，不另起一套（cc-logto_yxi 的建议）。
    val ultra = r.tier == Account.Tier.Ultra
    val flow = if (ultra) {
        listOf(Color(0xFFFDBE5A), Color(0xFFF59E8C), Color(0xFFFFE1A8), Color(0xFFFDBE5A))
    } else {
        listOf(Color(0xFF8AB4F8), Color(0xFF346BF0), Color(0xFFB9D2FB), Color(0xFF8AB4F8))
    }
    // 文字和勾要压深才够对比 —— 淡的那套是给流动层的
    val inkGrad = if (ultra) {
        listOf(Color(0xFFE8912F), Color(0xFFEC7A62), Color(0xFFE0A64A))
    } else {
        listOf(Color(0xFF1B62E8), Color(0xFF2E5FD6), Color(0xFF3E7BF2))
    }

    val head = if (r.kind == "balance") t("余额到账") else t("Welcome to")
    val name = when {
        r.kind == "balance" -> app.yxi.agent.Account.yuan(r.amountCents)
        ultra -> "Yunxi Ultra"
        else -> "Yunxi Pro"
    }
    val sub = when {
        // 负数是**该显示**的（刚兑的券被撤销就会这样），只是负号要在 ¥ 前面 —— 见 [yuan]
        r.kind == "balance" -> t("当前余额 %s").format(app.yxi.agent.Account.yuan(r.balanceCents))
        r.expiresAt.isNotBlank() -> t("会员有效期至 %s").format(r.expiresAt)
        else -> ""
    }

    // 全局时钟：0→1 线性走完 TOTAL。关了动效就直接停在最后一帧。
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(TOTAL.toInt(), easing = LinearEasing)) }
    // ⚠️ 出声和「勾开始描出」那一刻（1020ms）对齐，不是动效一开始就响
    LaunchedEffect(Unit) { if (motion) { delay(1020); ding(ctx) } else ding(ctx) }

    // 玻璃壳的三个循环 —— 跟输入框同一套周期，展开之后不停
    val tr = rememberInfiniteTransition(label = "glass")
    val breathe by tr.animateFloat01(2600, motion)
    val sheen by tr.animateFloat01(3800, motion)
    val hue by tr.animateFloat01(6000, motion)

    val measurer = rememberTextMeasurer()
    val headStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val nameStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val subStyle = TextStyle(fontSize = 13.sp)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // 播的过程中别让人点外面就关掉 —— 两秒而已，让它放完
            dismissOnClickOutside = false,
        ),
    ) {
    Box(
        Modifier.fillMaxSize()
            .background(Color(0x66000000))
            // 播完点一下关掉；播的过程中吃掉点击，别让人手一抖就跳走
            .clickable(enabled = true) { if (clock.value >= 1f) onDone() },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ms = clock.value * TOTAL
            val w = size.width
            val h = size.height
            // s = 尺寸基准（规格：min(宽×0.44, 高×0.30)）
            val s = min(w * 0.44f, h * 0.30f)
            val pillH = 0.62f * s
            val padL = 0.135f * s
            val icon = 0.35f * s
            val gap = 0.10f * s
            val padR = 0.135f * s

            // ── 文字实测宽 → 展开后的目标宽度（**不写死倍数**）
            val headLay = measurer.measure(head, headStyle)
            val nameLay = measurer.measure(name, nameStyle)
            val textW = maxOf(headLay.size.width, nameLay.size.width).toFloat()
            val cap = w - with(density) { 24.dp.toPx() }
            val fullW = min(padL + icon + gap + textW + padR, cap)

            // ── 时间轴七段
            val spin = seg(ms, 0f, 950f, SPIN)
            val arcA = 1f - seg(ms, 950f, 1110f, EaseIn)
            val check = seg(ms, 1020f, 1360f, CHECK)
            val open = seg(ms, 1240f, 1860f, OPEN)
            val rise = seg(ms, 1460f, 1960f, RISE)
            val subA = seg(ms, 1700f, 2200f, EaseOut)

            val pw = pillH + (fullW - pillH) * open      // 圆形 → 药丸
            val cx = w / 2f
            val cy = h / 2f
            val left = cx - pw / 2f
            val top = cy - pillH / 2f
            val rr = CornerRadius(pillH / 2f)

            // ① 投影 —— **画在不透明的面下面**，不用 shadowElevation（STYLE.md #225）
            drawRoundRect(
                Color(0x1C1F375A),
                topLeft = Offset(left, top + 0.05f * s),
                size = Size(pw, pillH),
                cornerRadius = CornerRadius(pillH / 2f + 0.13f * s),
            )
            // ② 玻璃体：近白
            drawRoundRect(Color.White.copy(alpha = 0.92f), Offset(left, top), Size(pw, pillH), rr)
            // ③ 流动层：档位色，**0.20**
            val drift = (if (motion) hue else 0.5f) * pw
            drawRoundRect(
                Brush.linearGradient(flow, start = Offset(left - drift, top), end = Offset(left + pw * 1.6f - drift, top)),
                Offset(left, top), Size(pw, pillH), rr, alpha = 0.20f,
            )
            // ④ 立体：顶部高光 → 透明
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                    startY = top, endY = top + pillH * 0.55f,
                ),
                Offset(left, top), Size(pw, pillH), rr,
            )
            // ⑤ 流动光带：斜着扫一遍
            val bx = left - pw * 0.4f + (if (motion) sheen else 0.5f) * pw * 1.8f
            drawRoundRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                    start = Offset(bx - pillH * 0.5f, top), end = Offset(bx + pillH * 0.5f, top + pillH),
                ),
                Offset(left, top), Size(pw, pillH), rr,
            )
            // ⑥ 玻璃边 + 外圈呼吸光晕
            val glow = 0.12f + 0.20f * (if (motion) breathe else 0.5f)
            drawRoundRect(
                Brush.linearGradient(listOf(Color.White, flow[1].copy(alpha = 0.8f))),
                Offset(left, top), Size(pw, pillH), rr,
                style = Stroke(width = with(density) { 1.2.dp.toPx() }),
                alpha = 0.9f,
            )
            drawRoundRect(
                flow[0].copy(alpha = glow * 0.5f),
                Offset(left - 6f, top - 6f), Size(pw + 12f, pillH + 12f),
                CornerRadius(pillH / 2f + 6f),
                style = Stroke(width = 6f),
            )

            // ── 中间那枚：先转圈，再打勾。图标一直贴在左内边距那儿（展开时它就自然靠左了）
            val icx = if (open < 0.02f) cx else left + padL + icon / 2f
            val icy = cy
            if (arcA > 0.01f) {
                val rad = icon * 0.42f
                drawArc(
                    inkGrad[0].copy(alpha = arcA),
                    startAngle = -90f + spin * 700f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(icx - rad, icy - rad), size = Size(rad * 2, rad * 2),
                    style = Stroke(width = icon * 0.13f, cap = StrokeCap.Round),
                )
            }
            if (check > 0f) {
                // 规格给的是 100×100 视口里的三点：30,52 → 44,66 → 71,36
                val k = icon / 100f
                val p = Path().apply {
                    moveTo(icx - icon / 2 + 30 * k, icy - icon / 2 + 52 * k)
                    lineTo(icx - icon / 2 + 44 * k, icy - icon / 2 + 66 * k)
                    lineTo(icx - icon / 2 + 71 * k, icy - icon / 2 + 36 * k)
                }
                val pm = PathMeasure().apply { setPath(p, false) }
                val seen = Path()
                pm.getSegment(0f, pm.length * check, seen, true)
                drawPath(
                    seen,
                    Brush.linearGradient(inkGrad),
                    style = Stroke(width = 13 * k, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // ── 文字：从药丸里长出来
            if (rise > 0f) {
                val tx = left + padL + icon + gap
                val dy = (1f - rise) * with(density) { 10.dp.toPx() }
                val blockH = headLay.size.height + nameLay.size.height
                drawText(
                    measurer, head,
                    topLeft = Offset(tx, cy - blockH / 2f + dy),
                    style = headStyle.copy(color = Color(0xFF2B2B2B).copy(alpha = rise)),
                )
                drawText(
                    measurer, name,
                    topLeft = Offset(tx, cy - blockH / 2f + headLay.size.height + dy),
                    style = nameStyle.copy(brush = Brush.linearGradient(inkGrad), alpha = rise),
                )
            }
            // ── 有效期 / 余额：药丸下面那行
            if (subA > 0f && sub.isNotBlank()) {
                val lay = measurer.measure(sub, subStyle)
                drawText(
                    measurer, sub,
                    topLeft = Offset(cx - lay.size.width / 2f, top + pillH + 0.18f * s),
                    style = subStyle.copy(color = Color(0xFF5F6368).copy(alpha = subA)),
                )
            }
        }
    }

    }

    // 播完停一会儿自己让开 —— 不用非等人点
    LaunchedEffect(Unit) { delay(if (motion) (TOTAL + 1400).toLong() else 1400L); onDone() }
}

private const val TOTAL = 2200f

// 规格里逐段给的缓动曲线，原样抄
private val SPIN = CubicBezierEasing(0.4f, 0f, 0.5f, 1f)
private val CHECK = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)
private val OPEN = CubicBezierEasing(0.22f, 0.9f, 0.24f, 1f)
private val RISE = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1f)
private val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** 某一段的进度：还没到 = 0，过了 = 1，中间过一遍缓动。 */
private fun seg(ms: Float, from: Float, to: Float, e: Easing): Float =
    e.transform(((ms - from) / (to - from)).coerceIn(0f, 1f))

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloat01(
    periodMs: Int,
    motion: Boolean,
): androidx.compose.runtime.State<Float> = animateFloat(
    0f, 1f,
    infiniteRepeatable(tween(if (motion) periodMs else 100_000_000, easing = LinearEasing), RepeatMode.Restart),
    label = "c$periodMs",
)


/**
 * 「叮」的一声 —— **合成的，不带音频文件**（规格给的参数原样实现）：
 * 30ms 白噪声过 2600Hz 高通当起音，加 C6(1046.5Hz, 190ms) 和 G6(1567.98Hz, 延后 12ms, 170ms, 0.6 倍)，
 * 三者都是指数包络、6ms 起音。
 *
 * ⚠️ **跟随系统静音**：手机调了静音/震动就一声不出。开屏、通知这些地方我们从不强出声，
 * 兑换成功也不例外。
 */
private fun ding(ctx: Context) {
    runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val sr = 44100
        val n = (sr * 0.24f).toInt()
        val buf = ShortArray(n)
        var hp = 0f
        var prev = 0f
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val tSec = i / sr.toFloat()
            var v = 0f
            // ① 起音「哒」：白噪声过一阶高通，幅度 (1-i/nn)^6
            val nn = (sr * 0.030f).toInt()
            if (i < nn) {
                val raw = rnd.nextFloat() * 2f - 1f
                val a = 2f * PI.toFloat() * 2600f / sr
                hp = (1f / (1f + a)) * (hp + raw - prev)
                prev = raw
                val k = 1f - i / nn.toFloat()
                v += hp * 0.18f * k * k * k * k * k * k
            }
            // ② 主音 C6 + ③ 泛音 G6（延后 12ms、0.6 倍）
            v += env(tSec, 0.190f) * sin(2f * PI.toFloat() * 1046.5f * tSec)
            val t2 = tSec - 0.012f
            if (t2 > 0) v += 0.6f * env(t2, 0.170f) * sin(2f * PI.toFloat() * 1567.98f * t2)
            buf[i] = (v.coerceIn(-1f, 1f) * 0.32f * Short.MAX_VALUE).roundToInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        track.setNotificationMarkerPosition(n)
        track.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) = runCatching { t?.release() }.let {}
                override fun onPeriodicNotification(t: AudioTrack?) {}
            },
        )
        track.play()
    }
}

/** 指数包络：6ms 冲到峰值，然后指数衰减到 0。起音越快越「脆」。 */
private fun env(t: Float, dur: Float): Float {
    if (t < 0f || t > dur) return 0f
    val attack = 0.006f
    val a = if (t < attack) t / attack else 1f
    return a * exp(-4.2f * (t / dur))
}

/** 跟别处同一套判法：系统关了动效就不动，直接给最后一帧（design/STYLE.md §2.4）。 */
private fun motionOn(ctx: Context): Boolean = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)
