package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountAvatar(name: String, size: Dp = 34.dp) {
    val t = Tokens.current
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(t.textPrimary), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifBlank { "Y" }, color = t.surface2,
            style = if (size >= 56.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}
