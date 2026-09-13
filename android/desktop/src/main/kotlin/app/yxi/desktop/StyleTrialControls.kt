package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable
fun StyleTrialControls(preview: BrowserPreview, selection: PageSelection) {
    val t = Tokens.current
    var property by remember(preview) { mutableStateOf("font-size") }
    var value by remember(selection, property) { mutableStateOf(TextFieldValue(preview.styleChanges[property]?.let { StyleTrial.editorValue(property, it) } ?: StyleTrial.initial(property, selection.computed[property].orEmpty()))) }
    val textSupported = selection.computed.containsKey(StyleTrial.TEXT)
    var menu by remember { mutableStateOf(false) }
    var fontMenu by remember { mutableStateOf(false) }
    NativeOverlay(menu || fontMenu)
    var gesture by remember(property) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(if (preview.stylePending != null) "正在试调…" else "临时预览 · 尚未修改源文件", style = MaterialTheme.typography.labelSmall, color = t.warning)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                TextButton({ menu = true }) { Text(StyleTrial.properties.getValue(property) + " ▾") }
                DropdownMenu(menu, { menu = false }) {
                    StyleTrial.properties.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, enabled = key != StyleTrial.TEXT || textSupported, onClick = { property = key; menu = false; fontMenu = false }) }
                }
            }
            if (property == "font-family") Box(Modifier.weight(1f)) {
                TextButton({ fontMenu = true }, enabled = !preview.selectionStale) { Text((StyleTrial.fonts[value.text] ?: "选择字体") + " ▾") }
                DropdownMenu(fontMenu, { fontMenu = false }) {
                    StyleTrial.fonts.forEach { (font, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { value = TextFieldValue(font); fontMenu = false }) }
                }
            } else OutlinedTextField(value, { value = it }, Modifier.weight(1f).onPreviewKeyEvent {
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.isCtrlPressed && it.key == Key.A -> { value = value.copy(selection = TextRange(0, value.text.length)); true }
                    it.key == Key.Enter && (property != StyleTrial.TEXT || it.isCtrlPressed) -> { preview.trialStyle(property, value.text); true }
                    else -> false
                }
            }, label = { Text(if (property == StyleTrial.TEXT) "替换文字 · Ctrl+Enter试调" else if (StyleTrial.isColor(property)) "颜色值" else "试调值 · px") }, singleLine = property != StyleTrial.TEXT, maxLines = 4, enabled = !preview.selectionStale && (property != StyleTrial.TEXT || textSupported))
            TextButton({ preview.trialStyle(property, value.text) }, enabled = !preview.selectionStale && (property != "font-family" || value.text in StyleTrial.fonts) && (property != StyleTrial.TEXT || textSupported)) { Text("试调") }
        }
        if (property == StyleTrial.TEXT) {
            Text(if (textSupported) "临时修改纯文本节点，保留元素结构与事件。" else "请选取纯文本子元素，或在源码中修改。", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        } else if (property == "font-family") {
            Text("使用浏览器可用的字体类型，实际字形取决于设备。", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        } else if (!StyleTrial.isColor(property)) {
            val bounds = StyleTrial.range(property)
            Slider(value.text.toFloatOrNull()?.coerceIn(bounds) ?: bounds.start,
                onValueChange = {
                    if (gesture == null) gesture = java.util.UUID.randomUUID().toString()
                    value = TextFieldValue(it.toInt().toString()); preview.trialStyle(property, value.text, gesture)
                },
                onValueChangeFinished = { gesture = null }, valueRange = bounds, enabled = !preview.selectionStale,
                modifier = Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = "调整${StyleTrial.properties.getValue(property)}" })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("#202636", "#5359db", "#0b57d0", "#168169", "#ffffff").forEach { hex ->
                    IconButton({ value = TextFieldValue(hex); preview.trialStyle(property, value.text) }, enabled = !preview.selectionStale, modifier = Modifier.size(34.dp).semantics { contentDescription = "选择颜色 $hex" }) {
                        Box(Modifier.size(19.dp).background(Color(0xff000000L or hex.drop(1).toLong(16)), CircleShape).border(1.dp, t.border, CircleShape))
                    }
                }
            }
        }
        Text("选取时：${selection.computed[property].orEmpty().ifBlank { "未提供" }}", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = t.textMuted)
    }
}
