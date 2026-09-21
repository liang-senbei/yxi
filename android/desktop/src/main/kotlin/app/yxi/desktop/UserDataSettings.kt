package app.yxi.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UserDataSettings() {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("用户数据", style = MaterialTheme.typography.titleSmall)
        Text(Store.dir.absolutePath, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        Text("保存登录凭据、服务器记录、会话整理和偏好。Windows 凭据由当前系统账号加密保护。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        Row {
            TextButton({ scope.launch {
                try { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(Store.dir) }; error = "" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "无法打开目录：${e.message.orEmpty()}" }
            } }) { Text("打开数据目录") }
            TextButton({ clipboard.setText(AnnotatedString(Store.dir.absolutePath)) }) { Text("复制路径") }
        }
        if (Store.warning.isNotBlank()) Text(Store.warning, style = MaterialTheme.typography.bodySmall, color = Tokens.current.warning)
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
    }
}
