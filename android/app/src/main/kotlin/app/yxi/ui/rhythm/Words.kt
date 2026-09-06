package app.yxi.ui.rhythm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 音游的字 —— 老板那张评价词参考图（design/judge-words-ref.png）的语言：
 * **粗斜体白字 + 深色细描边 + 首字母一抹橙**，大词旁边黄 / 橙 / 蓝三道斜线和几个星点。
 * 连击数字、倍率、Miss 也用同一套（老板：「连击的字体最好也要跟 Miss / Perfect 一样」）。
 *
 * ⚠️ 舞台固定深色，颜色全是常量（#14）。斜体和最重字重用系统字，不带字形文件。
 */
object Words {
    val ORANGE = Color(0xFFF97316)
    val YELLOW = Color(0xFFFDBE5A)
    val BLUE = Color(0xFF3B9BF6)
    val MISS = Color(0xFFE5484D)
    private val OUTLINE = Color(0xFF141822)

    /** 粗斜体 + 描边 + 填充。[fill] 默认白；[firstOrange] 首字母染橙（大评价词用） */
    @Composable
    fun Bold(
        text: String, size: TextUnit, modifier: Modifier = Modifier,
        fill: Color = Color.White, firstOrange: Boolean = false, alpha: Float = 1f,
    ) {
        val style = TextStyle(fontSize = size, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = TextUnit.Unspecified)
        val body: AnnotatedString = if (firstOrange && text.isNotEmpty()) buildAnnotatedString {
            withStyle(SpanStyle(color = ORANGE)) { append(text.substring(0, 1)) }
            append(text.substring(1))
        } else AnnotatedString(text)
        Box(modifier.graphicsLayer { this.alpha = alpha }) {
            Text(text, style = style.copy(color = OUTLINE, drawStyle = Stroke(width = size.value * 0.06f * 3f)))
            Text(body, style = style.copy(color = fill))
        }
    }

    /**
     * 大评价词（Great / Excellent / Amazing / Free）：砸进来（1.25→1 缩放）、停、最后 28% 上飘淡出；
     * 黄 / 橙 / 蓝三道斜线从左下往右上，长短不一；一橙一蓝两个点、一颗四角星。
     * @param k 0..1 的生命进度（宿主按 1.2 秒推）
     */
    @Composable
    fun Praise(text: String, k: Float, modifier: Modifier = Modifier) {
        val inK = (k * 6f).coerceAtMost(1f)
        val pop = 1f + 0.25f * (1f - inK) * (1f - inK)
        val out = if (k > 0.72f) (k - 0.72f) / 0.28f else 0f
        Box(modifier, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.height * 0.55f                    // 跟字号同量级
                val cx = size.width / 2f; val cy = size.height / 2f
                val cols = listOf(YELLOW, ORANGE, BLUE)
                cols.forEachIndexed { i, c ->
                    val off = (i - 1) * s * 0.55f
                    val len = s * (1.4f - 0.3f * i)
                    val a = (0.55f + 0.35f * (1f - inK)) * (1f - out)
                    drawLine(
                        c.copy(alpha = a.coerceIn(0f, 1f)),
                        Offset(cx - s * 1.6f + off, cy + s * 0.45f + i * s * 0.08f),
                        Offset(cx - s * 1.6f + off + len, cy + s * 0.45f - len * 0.8f + i * s * 0.08f),
                        strokeWidth = 2f + i * 0.5f, cap = StrokeCap.Round,
                    )
                }
                drawCircle(ORANGE.copy(alpha = .9f * (1f - out)), s * 0.05f, Offset(cx + s * 1.9f, cy - s * 0.4f))
                drawCircle(BLUE.copy(alpha = .9f * (1f - out)), s * 0.035f, Offset(cx - s * 2.0f, cy + s * 0.25f))
                val r = s * 0.08f; val sx = cx + s * 1.7f; val sy = cy + s * 0.35f
                val p = Path()
                for (i in 0 until 8) {
                    val ang = i * Math.PI.toFloat() / 4f
                    val rr = if (i % 2 == 1) r * 0.35f else r
                    val x = sx + kotlin.math.cos(ang) * rr; val y = sy + kotlin.math.sin(ang) * rr
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, Color.White.copy(alpha = .95f * (1f - out)))
            }
            Bold(
                text, MaterialTheme.typography.displayMedium.fontSize, firstOrange = true, alpha = 1f - out,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop; scaleY = pop
                    translationY = -out * 40.dp.toPx()
                },
            )
        }
    }
}
