package app.yxi.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Selection uses a neutral inset background; action and input focus colours stay unchanged. */
@Composable internal fun QuietChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: @Composable () -> Unit, leadingIcon: (@Composable () -> Unit)? = null) {
    val t = Tokens.current
    FilterChip(selected, onClick, label, modifier, enabled = enabled, leadingIcon = leadingIcon,
        shape = RoundedCornerShape(8.dp), border = null, elevation = null,
        colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, labelColor = t.textSecondary,
            selectedContainerColor = t.selected, selectedLabelColor = t.textPrimary,
            iconColor = t.textSecondary, selectedLeadingIconColor = t.textPrimary))
}
