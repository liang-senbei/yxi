package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HostRecoveryDialog(close: () -> Unit, restore: (HostRecoveryCopy) -> Unit) {
    var error by remember { mutableStateOf("") }
    val copies = remember { runCatching { Store.hostRecoveryCopies() }.getOrElse { error = "无法读取受保护副本，请使用原Windows账号重试"; emptyList() } }
    var selected by remember { mutableStateOf<HostRecoveryCopy?>(null) }
    WorkbenchDialog(onDismissRequest = close, title = { Text("恢复服务器配置") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("主配置已缺失。请选择要恢复的迁移副本；副本可能较旧，恢复后不会自动连接服务器。", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                copies.forEach { copy -> OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) {
                    RadioButton(selected == copy, { selected = copy }, enabled = copy.valid)
                    Column(Modifier.weight(1f)) {
                        Text(when { copy.name.contains(".import-") -> "旧目录迁移副本"; copy.name.contains(".bak.") -> "旧备份副本"; copy.name.contains(".damaged.") -> "损坏文件副本"; else -> "原主文件副本" }, style = MaterialTheme.typography.titleSmall)
                        Text(if (copy.valid) "${copy.count} 台服务器" else "此副本无法验证或解密", style = MaterialTheme.typography.bodySmall)
                        copy.summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(copy.name, style = MaterialTheme.typography.labelSmall)
                    }
                } } }
            }
            if (copies.isEmpty()) Text("没有可用的迁移副本")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton({
            selected?.let { copy -> runCatching { restore(copy) }.onFailure { error = it.message ?: "恢复失败，副本已保留" } }
        }, enabled = selected?.valid == true) { Text("恢复所选副本") } }, dismissButton = { TextButton(close) { Text("取消") } })
}
