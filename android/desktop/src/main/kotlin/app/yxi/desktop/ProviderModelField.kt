package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Requests the runner's context suffix, not an assertion of provider capability. */
@Composable
internal fun ProviderModelField(label: String, value: String, change: (String) -> Unit) {
    val suffix = Regex("\\[1m]$", RegexOption.IGNORE_CASE)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        Checkbox(suffix.containsMatchIn(value), { enabled ->
            val base = value.trim().replace(suffix, "")
            change(if (enabled) "$base[1m]" else base)
        }, enabled = value.isNotBlank())
        Text("1M", style = MaterialTheme.typography.labelMedium)
    }
}
