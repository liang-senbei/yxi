package app.yxi.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 可搜索的模型选择面板（参照 CC Switch 的获取模型下拉，收起时只是一个按钮）。
 * 数据来自「获取模型列表」的只读结果（ProviderModels.fetch，网络层在 root 手里）；
 * 点选回填后自动收起，字段本身仍可手填。筛选按模型 ID 或提供方（owner）不区分大小写。
 * 空匹配如实提示；不用弹窗、不抢焦点，展开就地占位（外层编辑页本身可滚动）。
 */
@Composable
internal fun ProviderModelPicker(models: List<ProviderModels.Model>, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember(open) { mutableStateOf("") }
    val t = Tokens.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton({ open = !open }) { Text(if (open) "收起模型列表" else "从列表选择 ⌄") }
        if (open) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("按模型 ID 或提供方筛选（共 ${models.size} 个）") })
            val filtered = if (query.isBlank()) models else models.filter { m ->
                m.id.contains(query, true) || m.owner?.contains(query, true) == true
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = t.surface1, border = BorderStroke(0.5.dp, t.border)) {
                if (filtered.isEmpty()) {
                    Text("没有匹配的模型。", Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(filtered.size) { index ->
                        val model = filtered[index]
                        Row(Modifier.fillMaxWidth().clickable { open = false; pick(model.id) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(model.id, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            model.owner?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (index < filtered.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}
