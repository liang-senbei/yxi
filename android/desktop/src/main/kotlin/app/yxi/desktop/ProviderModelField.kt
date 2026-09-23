package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 运行器的 [1m] 上下文后缀（只改写请求标签，不是对供应商能力的断言）。 */
internal val OneMSuffix = Regex("(?:\\[1m])+$", RegexOption.IGNORE_CASE)

/** 去掉尾部 [1m] 并整值 trim，得到纯模型 ID（映射「显示名称」跟随的是这个纯 ID）。 */
internal fun oneMBase(value: String) = value.trim().replace(OneMSuffix, "").trim()

internal fun hasOneM(value: String) = OneMSuffix.containsMatchIn(value.trim())

internal fun setOneM(base: String, enabled: Boolean): String {
    val id = oneMBase(base)
    return if (enabled && id.isNotEmpty()) "$id[1m]" else id
}

/**
 * 单个模型 ID 输入框。
 * - `models` 非空时在下方附「从列表选择」面板（搜索 + 点选回填，字段本身仍可手填）；
 * - `supportsOneM = false`（如 Haiku 档位，运行器不给该档位挂 1M 上下文）时不显示 1M 勾选，
 *   由调用方决定是否剥掉输入值里的 [1m]（本文件的 [oneMBase] 是约定剥法）。
 *
 * 1M 勾选只请求运行器的上下文后缀，不声明供应商能力。
 */
@Composable
internal fun ProviderModelField(
    label: String,
    value: String,
    models: List<ProviderModels.Model> = emptyList(),
    supportsOneM: Boolean = true,
    change: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
            if (supportsOneM) {
                Checkbox(hasOneM(value), { enabled -> change(setOneM(oneMBase(value), enabled)) }, enabled = value.isNotBlank())
                Text("1M", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (models.isNotEmpty()) ProviderModelPicker(models) { picked ->
            change(setOneM(oneMBase(picked), supportsOneM && hasOneM(value)))
        }
    }
}
