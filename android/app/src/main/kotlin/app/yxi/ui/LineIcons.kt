package app.yxi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 界面上的小图标 —— **自己画路径，不用 Unicode 字符**。
 *
 * ⚠️ 原来底部导航是 `◫ ▤ ❖ ⚙` 这种字符（用户原话：「那个小图标太难看了」）。
 * 字符图标不受控：字重、基线、字形全看系统字体，粗细跟界面别的线条对不上，还会跟着中英文字体变。
 * 这里按 **24×24 的格子**画描边路径（圆头圆角、统一 2 格粗），跟 QQ 侧边栏那种细线图标一个路子。
 *
 * ⚠️ 没引图标库：离线构建加不了依赖，而且这几个形状用路径写就是几行。
 */
enum class Ico { Chat, Server, Sliders, Gear, Crown, Moon, Person, Bolt }

@Composable
fun YxiIcon(ico: Ico, size: Dp = 22.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f          // 24 格 → 实际像素
        val w = 2f * k
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = w, cap = StrokeCap.Round)
        when (ico) {
            Ico.Chat -> drawPath(                                   // 对话气泡
                Path().apply {
                    addRoundRect(RoundRect(Rect(3f * k, 4f * k, 21f * k, 17f * k), CornerRadius(5.5f * k)))
                    moveTo(8.5f * k, 16.6f * k); lineTo(8.5f * k, 21f * k); lineTo(13f * k, 16.6f * k)
                },
                tint, style = st,
            )
            Ico.Server -> {                                         // 机架：两层 + 指示灯
                drawRoundRect(tint, Offset(3f * k, 4f * k), Size(18f * k, 7f * k), CornerRadius(2.4f * k), style = st)
                drawRoundRect(tint, Offset(3f * k, 13f * k), Size(18f * k, 7f * k), CornerRadius(2.4f * k), style = st)
                drawCircle(tint, 1.05f * k, Offset(7f * k, 7.5f * k))
                drawCircle(tint, 1.05f * k, Offset(7f * k, 16.5f * k))
            }
            Ico.Sliders -> {                                        // 推子：三条线 + 旋钮
                line(3f, 6.5f, 21f, 6.5f); line(3f, 12f, 21f, 12f); line(3f, 17.5f, 21f, 17.5f)
                listOf(15f to 6.5f, 8.5f to 12f, 16.5f to 17.5f).forEach { (x, y) ->
                    drawCircle(tint, 2.1f * k, Offset(x * k, y * k), style = Stroke(width = w))
                }
            }
            Ico.Gear -> {                                           // 齿轮：中心圈 + 外圈 + 短齿
                // ⚠️ 齿别画太长：第一版齿从 5.7 拉到 8.7，看着像个太阳。齿要短、要靠外，
                //    再加一圈轮身，才像齿轮。
                drawCircle(tint, 3.1f * k, Offset(12f * k, 12f * k), style = st)
                drawCircle(tint, 6.6f * k, Offset(12f * k, 12f * k), style = st)
                repeat(8) { i ->
                    val a = Math.PI / 4 * i + Math.PI / 8
                    val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                    line(12f + c * 6.3f, 12f + s * 6.3f, 12f + c * 8.9f, 12f + s * 8.9f)
                }
            }
            Ico.Crown -> {                                          // 皇冠：会员
                drawPath(
                    Path().apply {
                        moveTo(3.5f * k, 16.5f * k); lineTo(5.2f * k, 7.5f * k); lineTo(9.4f * k, 12.2f * k)
                        lineTo(12f * k, 5.5f * k); lineTo(14.6f * k, 12.2f * k); lineTo(18.8f * k, 7.5f * k)
                        lineTo(20.5f * k, 16.5f * k); close()
                    },
                    tint, style = st,
                )
                line(6.5f, 19.8f, 17.5f, 19.8f)
            }
            Ico.Moon -> {                                           // 月亮：夜间
                val full = Path().apply { addOval(Rect(3.5f * k, 3.5f * k, 20.5f * k, 20.5f * k)) }
                val bite = Path().apply { addOval(Rect(9.5f * k, 0f * k, 27f * k, 17.5f * k)) }
                drawPath(Path().apply { op(full, bite, PathOperation.Difference) }, tint, style = st)
            }
            Ico.Bolt -> drawPath(                                   // 闪电：会话状态那条的开关 / 「模式」标记
                // ⚠️ 这里原来直接写 emoji「⚡」（用户 2026-09-04：「看起来很违和」）。
                //    emoji 是彩色位图字形，粗细、基线、配色全归系统字体管 —— 它旁边就是 ︿ 那种细线，
                //    一胖一瘦贴在一起怎么调都不对。改成跟别的图标同一套：24 格、2 格粗、圆角描边。
                Path().apply {
                    moveTo(13.2f * k, 2.6f * k); lineTo(5.6f * k, 13.4f * k); lineTo(11.2f * k, 13.4f * k)
                    lineTo(10.8f * k, 21.4f * k); lineTo(18.4f * k, 10.6f * k); lineTo(12.8f * k, 10.6f * k)
                    close()
                },
                tint, style = st,
            )
            Ico.Person -> {                                         // 头像占位
                drawCircle(tint, 3.6f * k, Offset(12f * k, 8.6f * k), style = st)
                drawPath(
                    Path().apply {
                        moveTo(4.6f * k, 20f * k)
                        cubicTo(5.6f * k, 15.2f * k, 18.4f * k, 15.2f * k, 19.4f * k, 20f * k)
                    },
                    tint, style = st,
                )
            }
        }
    }
}
