package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VersionSettings() {
    val scope = rememberCoroutineScope()
    val urls = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    Text("Yxi", style = MaterialTheme.typography.headlineMedium)
    Text("当前版本 · " + if (Updater.version == "dev") "开发版" else Updater.version,
        style = MaterialTheme.typography.titleMedium)
    Text("更新不会删除已保存的服务器配置。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button({
            checking = true
            scope.launch {
                try { notice = withContext(Dispatchers.IO) { Updater.checkManually() } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { notice = "检查更新失败，请检查网络后重试，或从官网下载。" }
                finally { checking = false }
            }
        }, enabled = !checking && Updater.state !is Updater.Downloading) { Text(if (checking) "正在检查…" else "检查更新") }
        OutlinedButton({ runCatching { urls.openUri(Updater.FEED + "Yxi-win-Setup.exe?download=" + System.currentTimeMillis()) }.onFailure { notice = "无法打开浏览器，请手动访问官网" } }) { Text("官网下载") }
    }
    if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
    UpdateBanner()
}
