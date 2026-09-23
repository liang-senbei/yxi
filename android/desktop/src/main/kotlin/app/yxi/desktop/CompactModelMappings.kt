package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

internal data class ModelMappingRow(val id: String, val label: String, val value: String, val displayName: String?, val supportsOneM: Boolean)

@Composable internal fun CompactOneM(value: String, supported: Boolean, change: (String) -> Unit,
    modifier: Modifier = Modifier, height: Dp = compactControlHeight) {
    if (!supported) { Box(modifier.height(height), contentAlignment = Alignment.CenterStart) { Text("—", color = Tokens.current.textMuted) }; return }
    Row(modifier.heightIn(min = height).toggleable(hasOneM(value), enabled = oneMBase(value).isNotBlank(), role = Role.Checkbox,
        onValueChange = { change(setOneM(oneMBase(value), it)) }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Checkbox(hasOneM(value), null, Modifier.size(20.dp), enabled = oneMBase(value).isNotBlank())
        }
        Text("1M", fontSize = 13.sp, color = Tokens.current.textMuted)
    }
}

@Composable internal fun CompactModelMappings(rows: List<ModelMappingRow>, models: List<ProviderModels.Model>,
    changeModel: (String, String) -> Unit, changeName: (String, String) -> Unit,
    fallback: String, changeFallback: (String) -> Unit) {
    val t = Tokens.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 720.dp
        val height = if (narrow) maxOf(44.dp, compactControlHeight) else compactControlHeight
        @Composable fun cell(text: String, modifier: Modifier, muted: Boolean = false) {
            Box(modifier.height(height).background(t.surface1, RoundedCornerShape(6.dp)).border(1.dp, t.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(text, fontSize = 14.sp, color = t.textMuted, fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!narrow) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("模型角色", "显示名称", "实际请求模型", "声明支持 1M").forEachIndexed { i, title ->
                    Text(title, when (i) { 0 -> Modifier.width(120.dp); 3 -> Modifier.width(104.dp); else -> Modifier.weight(1f) }, fontSize = 12.sp, color = t.textMuted)
                }
            }
            rows.forEach { row ->
                @Composable fun name(modifier: Modifier) {
                    if (row.displayName == null) cell("不显示在模型菜单", modifier, muted = true)
                    else CompactTextInput(row.displayName, { changeName(row.id, it) }, "${row.label}显示名称", modifier.testTag("name:${row.id}"),
                        placeholder = oneMBase(row.value), height = height)
                }
                @Composable fun model(modifier: Modifier) = CompactModelInput(oneMBase(row.value), models, {
                    changeModel(row.id, setOneM(oneMBase(it), row.supportsOneM && (hasOneM(row.value) || hasOneM(it))))
                }, "${row.label}实际模型", modifier, height)
                if (narrow) Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, t.border), color = t.surface2) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(row.label, Modifier.weight(1f), fontWeight = FontWeight.Medium); CompactOneM(row.value, row.supportsOneM, { changeModel(row.id, it) }, height = height) }
                        Text("显示名称", fontSize = 12.sp, color = t.textMuted); name(Modifier.fillMaxWidth())
                        Text("实际请求模型", fontSize = 12.sp, color = t.textMuted); model(Modifier.fillMaxWidth())
                    }
                } else Row(Modifier.fillMaxWidth().testTag("mapping:${row.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    cell(row.label, Modifier.width(120.dp)); name(Modifier.weight(1f)); model(Modifier.weight(1f))
                    CompactOneM(row.value, row.supportsOneM, { changeModel(row.id, it) }, Modifier.width(104.dp), height)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = t.border)
            Text("默认 / 兜底模型", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactModelInput(oneMBase(fallback), models, { changeFallback(setOneM(oneMBase(it), hasOneM(fallback) || hasOneM(it))) },
                    "默认模型", Modifier.weight(1f), height)
                CompactOneM(fallback, true, changeFallback, Modifier.width(104.dp), height)
            }
        }
    }
}
