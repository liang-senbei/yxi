package app.yxi.ui.rhythm

import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativePaint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * swipe 方向标记，老板挑定的两款：`design/swipe/lane.js`「反向尾迹」、`design/swipe/motion.js`「风偏」，逐行搬。
 *
 * 单位：跟 HitFxs / Shatters 一样，数字都是网页的 CSS px 原样（线宽 1.6、柔光 8…），宿主整体 `scale(屏高/580)`，
 * `note` 里的尺寸也已经除过这个比例。
 */
object SwipeMarks {
    val all: List<Named<SwipeMark>> = listOf(
        Named("反向尾迹", SwipeMark { n, phase, rng -> reverseTrail(n, phase, rng) }),
        Named("风偏", SwipeMark { n, phase, rng -> windLean(n, phase, rng) }),
    )

    private val TAU = (2 * PI).toFloat()

    /** lane.js 的 rgba(c, a, k)：k>0 向白混，k<0 向黑混 */
    private fun Color.tint(a: Float, k: Float = 0f): Color {
        fun f(v: Float) = if (k > 0) v + (1 - v) * k else v * (1 + k)
        return Color(f(red), f(green), f(blue), a)
    }

    /** 尖角：顶点 (x,y) 朝 d，半高 s；只添路径不描 */
    private fun Path.chev(x: Float, y: Float, s: Float, d: Int) {
        moveTo(x - d * s * .55f, y - s); lineTo(x, y); lineTo(x - d * s * .55f, y + s)
    }

    // 本体柔光的画笔（Paint / BlurMaskFilter 带 native 句柄，别每帧新建）：
    // Canvas 2D 的 shadowBlur=8 是 σ=4px 的高斯；Skia 的 BlurMaskFilter 半径→σ 是 σ=.57735r+.5，反解 r
    // （API<28 硬件画布不认 maskFilter，只是没柔光，本体照画）
    private val glow = Paint().apply { nativePaint.maskFilter = BlurMaskFilter((4f - .5f) / .57735f, BlurMaskFilter.Blur.NORMAL) }

    /** lane.js 的 body()：圆角薄片（同色柔光 shadowBlur=8）+ 白芯 + 两头 ‹ › */
    private fun DrawScope.body(n: SwipeNote) {
        val (x, y, w, h) = n
        glow.color = n.color.copy(alpha = .7f)
        drawIntoCanvas { it.drawPath(Path().apply { addRoundRect(RoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, CornerRadius(h / 2))) }, glow) }
        drawRoundRect(n.color, Offset(x - w / 2, y - h / 2), Size(w, h), CornerRadius(h / 2))
        drawRoundRect(Color.White.copy(alpha = .85f), Offset(x - w / 2 + h * .35f, y - h * .22f), Size(w - h * .7f, h * .44f), CornerRadius(h * .22f))
        drawPath(
            Path().apply { chev(x - w / 2 - h * .35f, y, h * .5f, -1); chev(x + w / 2 + h * .35f, y, h * .5f, 1) },
            Color.White.copy(alpha = .95f), style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // ─────────────────────────────────────────────── 反向尾迹（lane.js #3）
    // 块朝反方向拖出逐级变淡的残像和细速度线，像已经在往方向冲；方向侧的头部尖角更亮更粗
    private fun DrawScope.reverseTrail(n: SwipeNote, t: Float, rng: Rng) {
        val (x, y, w, h) = n
        val d = n.dir; val c = n.color
        val br = 1 + .12f * sin(TAU * t)                                        // 尾迹一伸一缩
        clipRect(n.laneX, 0f, n.laneX + n.laneW, n.lineY) {                     // laneClip：本轨道内、判定线以上
            val fade = floatArrayOf(.22f, .12f, .05f)
            for (i in 1..3) drawRoundRect(
                c.tint(fade[i - 1], -.15f),
                Offset(x - d * w * .28f * i * br - w / 2, y - h / 2), Size(w, h), CornerRadius(h / 2),
            )
            // 速度线 ×4：从尾端往反方向甩出，长短由 rng 定（每帧同序列，不闪）
            val xs = x - d * w * .5f
            for (i in 0 until 4) {
                val ly = y + (i - 1.5f) * h * .55f
                val len = w * (.6f + rng.next() * .6f) * br
                drawLine(
                    Brush.horizontalGradient(0f to c.tint(.32f, .3f), 1f to c.tint(0f), startX = xs, endX = xs - d * len),
                    Offset(xs, ly), Offset(xs - d * len, ly),
                    strokeWidth = if (i % 2 == 1) 1f else 1.5f, cap = StrokeCap.Round,
                )
            }
        }
        body(n)
        // 头部：方向侧尖角加亮加粗一档
        drawPath(
            Path().apply { chev(x + d * (w / 2 + h * .35f), y, h * .55f, d) },
            Color.White, style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // ─────────────────────────────────────────────── 风偏（motion.js #3）
    // 整片斜体倒向滑动方向，尾后拖三道风线，本体一下一下被往那边推
    private fun DrawScope.windLean(n: SwipeNote, t: Float, rng: Rng) = withTransform({
        translate(n.x, n.y); scale(if (n.dir < 0) -1f else 1f, 1f, pivot = Offset.Zero)   // face：原点在音符中心、+x = 滑动方向
        val s = (if (t < .25f) sin(t / .25 * PI / 2) else cos((t - .25) / .75 * PI / 2)).toFloat()   // 快推慢回
        translate(n.h * .35f * s, 0f)
    }) {
        val (_, _, w, h) = n
        val c = n.color; val L = -w / 2; val hh = h / 2
        for (i in -1..1) {
            val len = w * (if (i != 0) .3f else .45f) * (.8f + .2f * sin(TAU * (t + rng.next())))
            val x1 = L - h * .45f - (if (i != 0) h * .45f else 0f); val y = i * h * .36f
            drawLine(
                Brush.horizontalGradient(0f to c.copy(alpha = 0f), 1f to c.copy(alpha = .9f), startX = x1 - len, endX = x1),
                Offset(x1 - len, y), Offset(x1, y), strokeWidth = 1.2f, cap = StrokeCap.Round,
            )
        }
        withTransform({ transform(Matrix().apply { values[Matrix.SkewX] = -.7f }) }) {   // 斜体：上沿往方向偏（圆角要小，圆头斜了看不出来）
            drawRoundRect(c.copy(alpha = .22f), Offset(L - 2, -hh - 2), Size(w + 4, h + 4), CornerRadius(h * .3f))
            drawRoundRect(c, Offset(L, -hh), Size(w, h), CornerRadius(h * .2f))
            drawRoundRect(Color.White.copy(alpha = .85f), Offset(L + h * .5f, -h * .22f), Size(w - h, h * .44f), CornerRadius(h * .1f))
        }
    }
}
