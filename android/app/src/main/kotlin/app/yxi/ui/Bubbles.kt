package app.yxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 聊天气泡的**样式**（老板 2026-09-06：照 QQ 的「聊天气泡」商店做，先设计几款、复刻他发的两款）。
 *
 * 原来的气泡只是「换颜色」（[Skins.Bubble] 的 bg/on），现在每套气泡多一份 [BubbleStyle]：
 * 边（圆角 / 云朵 / 像素）· 尾巴（角上一小圆角 / 两个小圆圈 / 没有）· 描边粗细 · 玻璃质感 · 角上探出来的角标。
 * 画法全在 [BubbleBox]，对话页和商店预览、装扮小样用的是**同一段代码** —— 所见即所得。
 *
 * 复刻的两款（QQ「心理活动框」「思考小睫」）：白底 + 2dp 墨色描边 + 大圆角 + 尾巴是**两个空心小圆圈**；
 * 「思考小睫」多一只角上探出来的小人 —— 我们用 Q 版云曦的头（`yunxi_q_head`）。
 *
 * ⚠️ 尾巴和角标画在气泡**外面**，所以 [BubbleBox] 自己在外圈留白，别的布局不用管；
 *    深浅皮肤靠 [Skins.Bubble] 的两套颜色，样式本身不变。
 */
enum class BubbleEdge { ROUND, CLOUD, PIXEL }
enum class BubbleTail { CORNER, DOTS, NONE }

data class BubbleStyle(
    val edge: BubbleEdge = BubbleEdge.ROUND,
    val tail: BubbleTail = BubbleTail.CORNER,
    val corner: Dp = 26.dp,
    /** 描边粗细；0 = 不描 */
    val outline: Dp = 0.dp,
    /** 半透明 + 高光的玻璃质感（跟输入框那颗药丸同一个语言） */
    val glass: Boolean = false,
    /** 角上探出来的角标图（drawable id）；0 = 没有 */
    val peek: Int = 0,
)

/** 默认那套（`id` 为空）跟主题走：底 primaryContainer、字 onPrimaryContainer */
@Composable
fun bubbleFill(b: Skins.Bubble): Color = if (b.id.isEmpty()) MaterialTheme.colorScheme.primaryContainer else b.bg

@Composable
fun bubbleInk(b: Skins.Bubble): Color = if (b.id.isEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else b.on

/** 点按 / 裁切用的形状（尾巴圆圈和角标不在形状里，它们只是画出来的装饰） */
fun bubbleShape(st: BubbleStyle): Shape = when (st.edge) {
    BubbleEdge.ROUND, BubbleEdge.CLOUD ->
        RoundedCornerShape(st.corner, st.corner, if (st.tail == BubbleTail.CORNER) 8.dp else st.corner, st.corner)
    BubbleEdge.PIXEL -> PixelShape(st.corner)
}

/** 像素边的裁切形状：台阶按 dp 换算，跟 [drawBubble] 画的那条边严丝合缝（GenericShape 拿不到 density，所以自己实现） */
private data class PixelShape(val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply { addPixelRect(size, with(density) { corner.toPx() }) })
}

/**
 * 气泡容器。[modifier] 加在**气泡本体**上（点按 / 长按放这儿），外圈留白给尾巴和角标。
 * 内容自己带 padding（对话页是 18×14）。
 */
@Composable
fun BubbleBox(b: Skins.Bubble, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable () -> Unit) {
    val st = b.style
    val fill = bubbleFill(b)
    val ink = bubbleInk(b)
    val peek: ImageBitmap? = if (st.peek != 0) ImageBitmap.imageResource(st.peek) else null
    // 外圈留白：尾巴圆圈往右下探 ~20×16dp，角标 64dp 探出 ~34×30dp；小样（compact）按比例缩
    val k = if (compact) 0.55f else 1f
    val padEnd = maxOf(if (st.tail == BubbleTail.DOTS) 20.dp * k else 0.dp, if (peek != null) 34.dp * k else 0.dp)
    val padBottom = maxOf(if (st.tail == BubbleTail.DOTS) 16.dp * k else 0.dp, if (peek != null) 30.dp * k else 0.dp)
    Box(Modifier.padding(end = padEnd, bottom = padBottom)) {
        Box(
            Modifier
                .drawBehind { drawBubble(st, fill, ink, peek, k) }
                .clip(bubbleShape(st))
                .then(modifier)
                // 探头图压在气泡右下角上，文字得让开，不然最后一个字被小人盖住。
                // ⚠️ 放在 modifier **后面**：点按 / 长按仍然覆盖整个气泡，只有文字往里收。
                .padding(end = if (peek != null) 14.dp * k else 0.dp),
        ) { content() }
    }
}

private fun DrawScope.drawBubble(st: BubbleStyle, fill: Color, ink: Color, peek: ImageBitmap?, k: Float = 1f) {
    val w = size.width; val h = size.height
    val stroke = st.outline.toPx()
    val outlineColor = if (st.glass) ink.copy(alpha = 0.28f) else ink

    // ── 云朵边：先画一圈半露的小圆，再用本体盖住内侧半边 ──
    if (st.edge == BubbleEdge.CLOUD) {
        val r = 7.dp.toPx(); val c = st.corner.toPx()
        val pts = mutableListOf<Offset>()
        // 沿边均分（不是定步长）：小气泡（装扮小样）也保证有边泡，末尾也不会留半截空当
        fun along(len: Float): List<Float> {
            val a = minOf(c, len / 2f); val b = len - a
            if (b - a < r) return listOf(len / 2f)
            val n = Math.round((b - a) / (r * 1.9f)).coerceAtLeast(1)
            return (0..n).map { a + (b - a) * it / n }
        }
        along(w).forEach { pts += Offset(it, 0f); pts += Offset(it, h) }
        along(h).forEach { pts += Offset(0f, it); pts += Offset(w, it) }
        pts += listOf(Offset(c * 0.45f, c * 0.45f), Offset(w - c * 0.45f, c * 0.45f), Offset(c * 0.45f, h - c * 0.45f), Offset(w - c * 0.45f, h - c * 0.45f))
        pts.forEach { p ->
            drawCircle(fill, r, p)
            if (stroke > 0) drawCircle(outlineColor, r, p, style = Stroke(stroke))
        }
    }

    // ── 本体 ──
    val path = Path().apply {
        when (st.edge) {
            BubbleEdge.PIXEL -> addPixelRect(size, st.corner.toPx())
            else -> {
                val c = st.corner.toPx()
                val br = if (st.tail == BubbleTail.CORNER) 8.dp.toPx() else c
                addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(c), CornerRadius(c), CornerRadius(br), CornerRadius(c)))
            }
        }
    }
    if (st.glass) {
        drawPath(path, fill.copy(alpha = 0.58f))
        // 顶部高光 → 透明 → 底部微暗，让它像一块有厚度的玻璃（同 GlassPill）
        drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent, Color.Black.copy(alpha = 0.06f))))
    } else {
        drawPath(path, fill)
    }
    if (stroke > 0) drawPath(path, outlineColor, style = Stroke(stroke))

    // ── 尾巴：两个空心小圆圈（QQ「心理活动框」那种），往右下角外面飘 ──
    if (st.tail == BubbleTail.DOTS) {
        val a = Offset(w - 6.dp.toPx() * k, h + 5.dp.toPx() * k); val ra = 6.dp.toPx() * k
        val b = Offset(w + 8.dp.toPx() * k, h + 12.dp.toPx() * k); val rb = 3.5f.dp.toPx() * k
        drawCircle(if (st.glass) fill.copy(alpha = 0.58f) else fill, ra, a)
        drawCircle(if (st.glass) fill.copy(alpha = 0.58f) else fill, rb, b)
        if (stroke > 0) { drawCircle(outlineColor, ra, a, style = Stroke(stroke)); drawCircle(outlineColor, rb, b, style = Stroke(stroke)) }
    }

    // ── 角标：右下角探出个小人 ──
    if (peek != null) {
        val s = (64.dp.toPx() * k).toInt()
        drawImage(peek, dstOffset = IntOffset((w - 30.dp.toPx() * k).toInt(), (h - 34.dp.toPx() * k).toInt()), dstSize = IntSize(s, s))
    }
}

/** 像素风：四角用 3 级台阶代替圆角，[step] 是每级台阶的边长（px） */
private fun Path.addPixelRect(size: Size, cornerPx: Float) {
    val s = minOf(cornerPx / 3f, minOf(size.width, size.height) / 9f).coerceAtLeast(2f)
    val w = size.width; val h = size.height
    moveTo(3 * s, 0f); lineTo(w - 3 * s, 0f)
    lineTo(w - 3 * s, s); lineTo(w - 2 * s, s); lineTo(w - 2 * s, 2 * s); lineTo(w - s, 2 * s); lineTo(w - s, 3 * s); lineTo(w, 3 * s)
    lineTo(w, h - 3 * s)
    lineTo(w - s, h - 3 * s); lineTo(w - s, h - 2 * s); lineTo(w - 2 * s, h - 2 * s); lineTo(w - 2 * s, h - s); lineTo(w - 3 * s, h - s); lineTo(w - 3 * s, h)
    lineTo(3 * s, h)
    lineTo(3 * s, h - s); lineTo(2 * s, h - s); lineTo(2 * s, h - 2 * s); lineTo(s, h - 2 * s); lineTo(s, h - 3 * s); lineTo(0f, h - 3 * s)
    lineTo(0f, 3 * s)
    lineTo(s, 3 * s); lineTo(s, 2 * s); lineTo(2 * s, 2 * s); lineTo(2 * s, s); lineTo(3 * s, s)
    close()
}

/** 商店预览：两条自己的消息，跟对话页一模一样的画法（同 QQ 那页的示例） */
@Composable
fun BubblePreview(b: Skins.Bubble, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BubbleBox(b) { Text(t("气泡样式随心换"), Modifier.padding(18.dp, 14.dp), style = MaterialTheme.typography.bodyLarge, color = bubbleInk(b)) }
        BubbleBox(b) {
            Text(t("让每次对话都带一点自己的味道"), Modifier.padding(18.dp, 14.dp).fillMaxWidth(0.72f), style = MaterialTheme.typography.bodyLarge, color = bubbleInk(b))
        }
    }
}
