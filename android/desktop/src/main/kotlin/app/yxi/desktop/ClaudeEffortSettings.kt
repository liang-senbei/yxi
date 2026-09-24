package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun ClaudeEffortSettings(model: String, levels: List<String>, current: String?, enabled: Boolean,
    applying: Boolean, modifier: Modifier = Modifier, apply: (String) -> Unit) {
    var selected by remember(model, current, levels) { mutableStateOf(current) }
    Column(modifier.widthIn(max = 320.dp)) {
        EffortControl(model, levels, selected) { selected = it }
        TextButton({ selected?.let(apply) }, enabled = enabled && !applying && selected != null && selected != current) {
            Text(if (applying) "正在应用…" else "应用思考强度")
        }
    }
}
