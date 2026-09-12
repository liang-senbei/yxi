package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Compact, neutral view navigation; accent colour is reserved for primary actions. */
@Composable
fun WorkbenchTabs(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = Tokens.current
    val shape = RoundedCornerShape(9.dp)
    Row(modifier.clip(shape).background(t.surface1).border(0.5.dp, t.border, shape).padding(3.dp)) {
        options.forEach { label ->
            val active = selected == label
            Text(label,
                Modifier.widthIn(min = 60.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (active) t.surface2 else Color.Transparent)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(label) })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                color = if (active) t.textPrimary else t.textMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}
