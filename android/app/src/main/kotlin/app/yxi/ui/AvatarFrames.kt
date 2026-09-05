package app.yxi.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * 头像框 —— 戴在头像外圈的装扮（槽位 [Skins.FRAME]）。
 *
 * 第一款是深渊满星的**「王棋彩框」**（老板 2026-09-05：做成星穹铁道「异相仲裁 · 王棋彩框头像」同款）：
 * 每期一款、期末不再产出，拿到的人永久可戴。第二款是奖池里早就有、客户端一直没接的「晨曦光环」。
 *
 * 画法跟 [MeAvatar] 的档位光环同一套语言（描边环 + 一点发光），但**颜色一眼分得开**：
 *  · 王棋彩框 = 四档稀有度色顺着环流一圈（蓝 → 紫 → 金 → 红），这就是「彩」；环上 12 个刻度 = 12 层；
 *    顶上一枚王棋（圆 + 十字）。期数不同只转一下起始色相，不另画。
 *  · 晨曦光环 = 玫瑰 → 桃 → 天蓝的晨空色，不带刻度。
 *
 * ⚠️ 只在过程里华丽：环会随 [spin] 慢转（减弱动效时 spin 恒 0，就是一枚静止的框）。
 * ⚠️ 全部矢量描边，不用位图 —— 头像 52dp 和选择页 34dp 的小样都是同一段代码画的。
 */
internal fun DrawScope.drawAvatarFrame(f: Skins.Frame, c: Offset, r: Float, stroke: Float, spin: Float) {
    when (f.kind) {
        Skins.FrameKind.NONE -> Unit
        Skins.FrameKind.DAWN -> {
            val cols = listOf(Color(0xFFFF9AA2), Color(0xFFFFD2A6), Color(0xFFC8E7FF), Color(0xFFFF9AA2))
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFFB7A0).copy(alpha = 0.22f), Color.Transparent), center = c, radius = r * 1.35f),
                radius = r * 1.35f, center = c,
            )
            rotate(spin, c) { drawCircle(Brush.sweepGradient(cols, c), radius = r, center = c, style = Stroke(stroke)) }
        }
        Skins.FrameKind.ABYSS -> {
            // 四档色转一圈：蓝 → 紫 → 金 → 红 → 蓝。期数每 +1 起点转 30°，第 N 期各有各的「彩」
            val cols = listOf(Color(0xFF5AA8FF), Color(0xFFA06BFF), Color(0xFFFFD76A), Color(0xFFFF5A4E), Color(0xFF5AA8FF))
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFA06BFF).copy(alpha = 0.20f), Color.Transparent), center = c, radius = r * 1.4f),
                radius = r * 1.4f, center = c,
            )
            rotate(spin + f.season * 30f, c) {
                drawCircle(Brush.sweepGradient(cols, c), radius = r, center = c, style = Stroke(stroke))
            }
            // 12 个刻度 = 12 层，固定不转（刻度是尺，环是光）
            val tick = stroke * 1.1f
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0 - 90.0)
                val p1 = Offset(c.x + (r - tick) * cos(a).toFloat(), c.y + (r - tick) * sin(a).toFloat())
                val p2 = Offset(c.x + (r + tick) * cos(a).toFloat(), c.y + (r + tick) * sin(a).toFloat())
                drawLine(Color.White.copy(alpha = 0.85f), p1, p2, strokeWidth = stroke * 0.55f, cap = StrokeCap.Round)
            }
            // 顶上一枚王棋：圆 + 十字，压在 12 点的刻度上
            val top = Offset(c.x, c.y - r)
            drawCircle(Color(0xFF14161C), radius = stroke * 1.9f, center = top)
            drawCircle(Color(0xFFFFD76A), radius = stroke * 1.9f, center = top, style = Stroke(stroke * 0.5f))
            drawLine(Color(0xFFFFD76A), Offset(top.x - stroke * 0.9f, top.y), Offset(top.x + stroke * 0.9f, top.y), stroke * 0.45f, StrokeCap.Round)
            drawLine(Color(0xFFFFD76A), Offset(top.x, top.y - stroke * 0.9f), Offset(top.x, top.y + stroke * 0.9f), stroke * 0.45f, StrokeCap.Round)
        }
    }
}
