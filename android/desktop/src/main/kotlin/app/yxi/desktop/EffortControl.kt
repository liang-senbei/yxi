package app.yxi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun effortName(value: String) = when (value) {
    "none" -> "关闭"; "minimal" -> "最少"; "low" -> "轻度"; "medium" -> "中等"
    "high" -> "高"; "xhigh" -> "更高"; "max" -> "最高"; else -> value
}

/** Shared discrete control. Dots and colour are visual only; values come from the runtime. */
@Composable
internal fun EffortControl(model: String, levels: List<String>, selected: String?, onSelect: (String) -> Unit) {
    if (levels.isEmpty()) return
    val index = levels.indexOf(selected).coerceAtLeast(0)
    val fraction = if (levels.size == 1) 0f else index.toFloat() / levels.lastIndex
    val tint = when (selected) {
        "none", "minimal" -> Color(0xFF7A8DA8)
        "low" -> Color(0xFF3B82F6)
        "medium" -> Color(0xFF4973EA)
        "high" -> Color(0xFF6B5CDF)
        "xhigh" -> Color(0xFF8D48D7)
        "max" -> Color(0xFF9E43D4)
        else -> Color(0xFF3B82F6)
    }
    val change by rememberUpdatedState(onSelect)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(model.ifBlank { "选择模型" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text(selected?.let(::effortName) ?: "保持当前", color = tint, style = MaterialTheme.typography.bodyMedium)
            Text(" ›", color = Tokens.current.textMuted)
        }
        Canvas(Modifier.fillMaxWidth().height(44.dp)
            .semantics {
                contentDescription = "思考强度"
                stateDescription = selected?.let(::effortName) ?: "保持当前"
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..levels.lastIndex.coerceAtLeast(1).toFloat(), (levels.size - 2).coerceAtLeast(0))
                setProgress { value -> change(levels[value.roundToInt().coerceIn(0, levels.lastIndex)]); true }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> { change(levels[(index - 1).coerceAtLeast(0)]); true }
                    Key.DirectionRight, Key.DirectionUp -> { change(levels[(index + 1).coerceAtMost(levels.lastIndex)]); true }
                    Key.MoveHome -> { change(levels.first()); true }
                    Key.MoveEnd -> { change(levels.last()); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(levels) {
                fun select(x: Float) {
                    val inset = 17.dp.toPx()
                    val ratio = ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    change(levels[(ratio * levels.lastIndex).roundToInt()])
                }
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume(); select(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed) { select(pointer.position.x); pointer.consume() }
                    } while (event.changes.any { it.id == down.id && it.pressed })
                }
            }) {
            val trackH = 30.dp.toPx(); val radius = trackH / 2
            val y = (size.height - trackH) / 2
            val thumb = 17.dp.toPx()
            val x = thumb + (size.width - 2 * thumb).coerceAtLeast(0f) * fraction
            val path = Path().apply { addRoundRect(RoundRect(0f, y, size.width, y + trackH, CornerRadius(radius))) }
            clipPath(path) {
                drawRect(Color(0xFFE8E8EA), Offset(0f, y), Size(size.width, trackH))
                val brush = if (selected == "max") Brush.horizontalGradient(listOf(Color(0xFF5430A5), Color(0xFFA66CEC), Color(0xFFCF8AE8)))
                    else Brush.horizontalGradient(listOf(tint, tint))
                drawRect(brush, Offset(0f, y), Size(x, trackH))
                repeat(levels.size) { n ->
                    val dotX = thumb + (size.width - 2 * thumb) * n / levels.lastIndex.coerceAtLeast(1)
                    drawCircle(if (n <= index) Color.White.copy(alpha = .4f) else Color(0xFFB8B8BC), 2.3.dp.toPx(), Offset(dotX, size.height / 2))
                }
                if (selected == "max") repeat(21) { n ->
                    val px = 12.dp.toPx() + ((n * 43) % 257) / 257f * (size.width - 24.dp.toPx())
                    drawCircle(Color.White.copy(alpha = .24f), (if (n % 3 == 0) 1.5f else .8f).dp.toPx(), Offset(px, y + trackH * (.25f + (n % 4) * .17f)))
                }
            }
            drawCircle(Color.Black.copy(alpha = .07f), thumb + 1.dp.toPx(), Offset(x, size.height / 2 + 1.dp.toPx()))
            drawCircle(Color(0xFFE4E4E8), thumb, Offset(x, size.height / 2))
            drawCircle(Color.White, thumb - 1.dp.toPx(), Offset(x, size.height / 2))
        }
    }
}
