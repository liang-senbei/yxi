package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.io.File

internal fun chooseHostTransferFile(save: Boolean): File? {
    val dialog = FileDialog(null as java.awt.Frame?, if (save) "导出服务器（不含认证信息）" else "导入服务器", if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        if (save) dialog.file = "yxi-hosts.json"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    } finally { dialog.dispose() }
}

@Composable
internal fun HostImportDialog(plan: HostImportPlan, close: () -> Unit, apply: () -> Unit) {
    var error by remember { mutableStateOf("") }
    WorkbenchDialog(onDismissRequest = close, title = { Text("导入服务器预览") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("新增 ${plan.additions.size} 台，跳过 ${plan.rows.count { it.duplicate }} 条重复记录。已有服务器和认证信息保持不变。")
            Text("导入不携带密码或私钥路径，请之后编辑新服务器的认证设置。", style = MaterialTheme.typography.bodySmall)
            if (plan.ignoredAuthentication) Text("文件含认证字段，本次将忽略。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan.rows.forEach { row -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(row.host.label, style = MaterialTheme.typography.titleSmall)
                    Text("${row.host.username}@${row.host.hostname}:${row.host.port}", style = MaterialTheme.typography.bodySmall)
                    Text(if (row.duplicate) "已存在：跳过" else if (row.reassignedId) "新增：标识冲突，将分配新标识" else "新增：待配置认证", style = MaterialTheme.typography.bodySmall)
                } } }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ runCatching(apply).onFailure { error = it.message ?: "导入失败，预览已保留" } }, enabled = plan.additions.isNotEmpty()) { Text("导入 ${plan.additions.size} 台") } }, dismissButton = { TextButton(close) { Text("取消") } })
}
