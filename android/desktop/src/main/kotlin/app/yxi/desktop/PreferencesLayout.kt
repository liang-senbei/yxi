package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PreferencesLayout(title: String, sections: List<String>, selected: String, select: (String) -> Unit, back: () -> Unit, content: @Composable () -> Unit) {
    var search by remember(title) { mutableStateOf("") }
    val t = Tokens.current
    BoxWithConstraints(Modifier.fillMaxSize().background(t.surface0)) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(if (maxWidth < 720.dp) 176.dp else 224.dp).fillMaxHeight().background(t.surface1).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(back) { Text("← 返回应用") }
                OutlinedTextField(search, { search = it }, placeholder = { Text("搜索分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(title, Modifier.padding(10.dp, 14.dp), style = MaterialTheme.typography.labelMedium, color = t.textMuted)
                sections.filter { it.contains(search.trim(), ignoreCase = true) }.forEach { label ->
                    Text(label, Modifier.fillMaxWidth().background(if (label == selected) t.border else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp)).clickable { select(label) }.padding(12.dp, 10.dp), style = MaterialTheme.typography.bodyMedium, color = t.textPrimary)
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }
}
