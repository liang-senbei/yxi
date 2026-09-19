package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** State colours remain still: no moving gradients, pulses or colour cycling. */
@Composable
private fun stateTint(busy: Boolean, waiting: Boolean, streaming: Boolean = false): Color {
    val t = Tokens.current
    return when {
        waiting -> t.warning.copy(alpha = if (t.dark) 0.07f else 0.035f)
        busy -> t.accent.copy(alpha = if (streaming) 0.018f else 0.035f)
        else -> Color.Transparent
    }
}

@Composable
fun ThinkingGlow(busy: Boolean, waiting: Boolean, streaming: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(stateTint(busy, waiting, streaming)))
}

@Composable
fun glowBrush(busy: Boolean, waiting: Boolean): Brush {
    val colour = stateTint(busy, waiting)
    return Brush.linearGradient(listOf(colour, colour))
}
