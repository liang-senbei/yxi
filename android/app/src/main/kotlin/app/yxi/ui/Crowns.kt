package app.yxi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 《神之冠冕》—— 八顶王冠，祈愿的角色池就是它们（老板 2026-09-04 给的设定）。
 * 首期 UP：**浮云之冠 · 云曦**。
 *
 * **老板给了 9 张立绘**（2026-09-04），卡面用立绘（`res/drawable-nodpi/card_<id>.webp`，
 * 已按人物位置切成竖版、总共 1.2MB）。只有幻蝶没有图 —— 那一张退回下面这套**几何冠纹章**，
 * 卡面不会因此开天窗。
 *
 * 每顶冠在 **100×100** 的格子里画，跟 [LineIcons] 同一套手法：圆头圆角、描边为主。
 * 它现在有两个用处：没立绘的顶位，以及需要小尺寸单色标记的地方。
 */
data class Crown(
    /** 服务端奖池里的 id（`kind: "character"`） */
    val id: String,
    /** 冠名，如「浮云之冠」 */
    val crown: String,
    /** 角色名，如「云曦」 */
    val name: String,
    /** 性格，两个词 */
    val trait: String,
    val story: String,
    val quote: String,
    /** 卡面配色：底色渐变两端 + 冠的描边色。**有立绘时只用来染文字**，卡面本身是立绘。 */
    val bg: List<Color>,
    val ink: Color,
    /**
     * 立绘资源（`R.drawable.card_<id>`）。0 = 没有立绘，退回几何冠纹章。
     * ⚠️ 老板 2026-09-04 给了 9 张，只有幻蝶没有 —— 卡面不能因此开天窗。
     */
    val art: Int = 0,
)

/**
 * 八顶。**顺序就是展示顺序**，云曦排第一（首期 UP）。
 * ⚠️ 文案是老板给的原文，只做了标点整理 —— **别自己改写**，这是设定不是文案草稿。
 */
val CROWNS = listOf(
    Crown(
        "yunxi", "浮云之冠", "云曦", "空灵 · 纯真",
        "收集宇宙间所有逝去文明的梦境，将其编织成云朵戴在发间。",
        "千万年的文明更迭，也不过是我发间一朵聚散的云烟。",
        listOf(Color(0xFFBFD8FF), Color(0xFFEBD9FF)), Color(0xFF4C7FE0), app.yxi.R.drawable.card_yunxi,
    ),
    Crown(
        "xingjin", "寂灭之冠", "星烬", "深沉 · 悲悯",
        "游走在废弃的星系坟场，将坍缩的黑洞与死寂的白矮星化作冠冕上的余晖。",
        "当最后一缕光也熄灭时，我会为这虚无加冕。",
        listOf(Color(0xFF2A2540), Color(0xFF4A2E4E)), Color(0xFFB98CD9), app.yxi.R.drawable.card_xingjin,
    ),
    Crown(
        "jinxing", "辉耀之冠", "烬星", "威严 · 炽烈",
        "从超新星爆发的灰烬中汲取力量，用绝对的光明统治刚刚重生的宇宙。",
        "不要妄图在我的光芒中寻找阴影，因为我即是不可违抗的破晓。",
        listOf(Color(0xFFFFD9A0), Color(0xFFFF9E6B)), Color(0xFFD9631E), app.yxi.R.drawable.card_jinxing,
    ),
    Crown(
        "suyuan", "圣痕之冠", "溯源", "庄重 · 坚忍",
        "替众生寻找万物起源的答案，将全宇宙的苦难化为头顶的荆棘与自身的修为。",
        "我头顶的每一根荆棘，都是这宇宙向真理献祭的代价。",
        listOf(Color(0xFFE6D6BC), Color(0xFFC9A87C)), Color(0xFF7A5A32), app.yxi.R.drawable.card_suyuan,
    ),
    Crown(
        "huandie", "幽冥之冠", "幻蝶", "神秘 · 温柔",
        "用灵蝶读取死者的遗憾，将躁动不安的灵魂引渡至没有痛苦的彼岸。",
        "嘘，闭上眼。死亡不是终点。",
        listOf(Color(0xFF3C4A6B), Color(0xFF5B7A6B)), Color(0xFF8FE0C0),
    ),
    Crown(
        "xuanji", "永恒之冠", "璇玑", "冷酷 · 绝对理性",
        "宇宙物理法则的具象化，永远站在宇宙的几何中心点静静旁观。",
        "我若闭目，便是万物运转的法则；我若睁眼，即是时间停滞的深渊。",
        listOf(Color(0xFFD8E2EC), Color(0xFFA9BCCF)), Color(0xFF44607C), app.yxi.R.drawable.card_xuanji,
    ),
    Crown(
        "gezhe", "真理之冠", "歌者", "高傲 · 不可侵犯",
        "掌握宇宙规律的造物主，指尖流转的光芒便是现实的绝对律令。",
        "你们穷极一生仰望的真理，不过是我随口哼唱的残音。",
        listOf(Color(0xFFFFF6D8), Color(0xFFFFE39E)), Color(0xFFBE9420), app.yxi.R.drawable.card_gezhe,
    ),
    Crown(
        "yeyin", "皎月之冠", "夜吟", "内敛 · 包容",
        "掌管黑暗与静谧，用温柔的夜曲封锁疯狂的记忆，抚平白昼留下的伤痕。",
        "白昼的荣光太过刺眼，来我的阴影里，赐你无梦的长眠。",
        listOf(Color(0xFF232C4A), Color(0xFF3B4A73)), Color(0xFFCBD8FF), app.yxi.R.drawable.card_yeyin,
    ),
    // ⚠️ 下面两位是老板 2026-09-04 连立绘一起追加的，**只给了名字，没给性格/故事/台词** ——
    //    我不替他编设定（那是 IP，不是文案草稿），空着显示「设定待补」。
    Crown(
        "duwuzhe", "星月", "独舞者", "",
        "", "",
        listOf(Color(0xFF2E3A5C), Color(0xFF5A6B93)), Color(0xFFCFE0FF), app.yxi.R.drawable.card_duwuzhe,
    ),
    Crown(
        "youhuo", "苍冥", "幽火", "",
        "", "",
        listOf(Color(0xFF2B2A22), Color(0xFF4E4530)), Color(0xFFE8C87A), app.yxi.R.drawable.card_youhuo,
    ),
)

/**
 * 画一顶冠。[locked] = 还没抽到，只画剪影（灰、无光）——
 * ⚠️ **没获得的不能画成获得的样子**，一眼要能分出来。
 */
@Composable
fun CrownArt(c: Crown, size: Dp = 96.dp, locked: Boolean = false, modifier: Modifier = Modifier) {
    val ink = if (locked) Color(0x66FFFFFF) else c.ink
    Canvas(modifier.size(size)) { drawCrown(c.id, ink, if (locked) 0.55f else 1f) }
}

/** 八顶冠各自的轮廓。100 格，圆头圆角，2.6 格粗。 */
internal fun DrawScope.drawCrown(id: String, ink: Color, alpha: Float) {
    val k = size.minDimension / 100f
    val w = 2.6f * k
    val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(ink, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = w, cap = StrokeCap.Round, alpha = alpha)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(ink, r * k, Offset(x * k, y * k), alpha = alpha)
    fun ring(cx: Float, cy: Float, r: Float) =
        drawCircle(ink, r * k, Offset(cx * k, cy * k), style = st, alpha = alpha)
    fun path(b: Path.() -> Unit) = drawPath(Path().apply(b), ink, style = st, alpha = alpha)
    // 所有冠共用的底座（冠带）—— 八顶摆一起时有共同语言，才像一套
    fun band() {
        line(24f, 78f, 76f, 78f)
        line(26f, 84f, 74f, 84f)
    }

    when (id) {
        // 浮云：三团云 + 一缕垂下的云尾
        "yunxi" -> {
            path {
                moveTo(26f * k, 66f * k)
                cubicTo(18f * k, 66f * k, 18f * k, 52f * k, 30f * k, 52f * k)
                cubicTo(30f * k, 38f * k, 50f * k, 34f * k, 54f * k, 46f * k)
                cubicTo(66f * k, 40f * k, 80f * k, 50f * k, 74f * k, 60f * k)
                cubicTo(84f * k, 62f * k, 82f * k, 66f * k, 74f * k, 66f * k)
                close()
            }
            path {
                moveTo(58f * k, 70f * k); cubicTo(66f * k, 72f * k, 62f * k, 76f * k, 54f * k, 74f * k)
            }
            band()
        }
        // 寂灭：坍缩环 + 中心的空 + 几点余晖
        "xingjin" -> {
            ring(50f, 48f, 20f)
            drawCircle(ink, 9f * k, Offset(50f * k, 48f * k), alpha = alpha * 0.25f)
            listOf(28f to 30f, 72f to 32f, 50f to 22f).forEach { (x, y) -> dot(x, y, 1.8f) }
            line(30f, 62f, 70f, 62f)
            band()
        }
        // 辉耀：尖冠 + 放射
        "jinxing" -> {
            path {
                moveTo(26f * k, 68f * k); lineTo(34f * k, 40f * k); lineTo(43f * k, 58f * k)
                lineTo(50f * k, 30f * k); lineTo(57f * k, 58f * k); lineTo(66f * k, 40f * k)
                lineTo(74f * k, 68f * k)
            }
            repeat(7) { i ->
                val a = (-PIf / 2f) + (i - 3) * 0.34f
                line(50f + cos(a) * 26f, 30f + sin(a) * 26f, 50f + cos(a) * 33f, 30f + sin(a) * 33f)
            }
            band()
        }
        // 圣痕：荆棘环
        "suyuan" -> {
            ring(50f, 46f, 21f)
            repeat(10) { i ->
                val a = PIf * 2f * i / 10f - PIf / 2f
                line(50f + cos(a) * 21f, 46f + sin(a) * 21f, 50f + cos(a) * 30f, 46f + sin(a) * 30f)
            }
            band()
        }
        // 幽冥：蝶翼
        "huandie" -> {
            path {
                moveTo(50f * k, 66f * k)
                cubicTo(30f * k, 66f * k, 18f * k, 46f * k, 30f * k, 34f * k)
                cubicTo(42f * k, 26f * k, 50f * k, 44f * k, 50f * k, 66f * k)
                close()
            }
            path {
                moveTo(50f * k, 66f * k)
                cubicTo(70f * k, 66f * k, 82f * k, 46f * k, 70f * k, 34f * k)
                cubicTo(58f * k, 26f * k, 50f * k, 44f * k, 50f * k, 66f * k)
                close()
            }
            line(50f, 30f, 50f, 66f)
            dot(44f, 28f, 1.6f); dot(56f, 28f, 1.6f)
            band()
        }
        // 永恒：同心 + 准星
        "xuanji" -> {
            ring(50f, 46f, 24f); ring(50f, 46f, 13f)
            dot(50f, 46f, 3f)
            line(50f, 14f, 50f, 26f); line(50f, 66f, 50f, 74f)
            line(18f, 46f, 30f, 46f); line(70f, 46f, 82f, 46f)
            band()
        }
        // 真理：层层光弦
        "gezhe" -> {
            listOf(14f, 20f, 26f, 32f).forEachIndexed { i, r ->
                drawArc(
                    ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset((50f - r) * k, (58f - r) * k), size = Size(r * 2 * k, r * 2 * k),
                    style = st, alpha = alpha * (1f - i * 0.15f),
                )
            }
            dot(50f, 58f, 2.6f)
            band()
        }
        // 皎月：弯月 + 星
        "yeyin" -> {
            val full = Path().apply { addOval(Rect(28f * k, 24f * k, 72f * k, 68f * k)) }
            val bite = Path().apply { addOval(Rect(40f * k, 18f * k, 90f * k, 68f * k)) }
            drawPath(
                Path().apply { op(full, bite, androidx.compose.ui.graphics.PathOperation.Difference) },
                ink, style = st, alpha = alpha,
            )
            dot(72f, 30f, 2.2f); dot(80f, 44f, 1.5f); dot(66f, 20f, 1.3f)
            band()
        }
    }
}

private const val PIf = 3.1415927f

/** 卡面底：两色渐变。没获得的压暗，一眼分得出。 */
internal fun cardBrush(c: Crown, locked: Boolean): Brush =
    if (locked) {
        Brush.linearGradient(listOf(Color(0xFF2B2F36), Color(0xFF3A3F48)))
    } else {
        Brush.linearGradient(c.bg)
    }
