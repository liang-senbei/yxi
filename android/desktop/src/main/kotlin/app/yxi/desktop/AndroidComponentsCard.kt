package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun AndroidComponentsCard(onInstalled: () -> Unit) {
    val installer = remember { AndroidSdkComponentInstaller() }
    val scope = rememberCoroutineScope()
    val cancel = remember { AtomicBoolean(false) }
    val packages = remember { listOf("emulator", "platform-tools") }
    var licenses by remember { mutableStateOf<AndroidSdkComponentInstaller.LicenseStatusList?>(null) }
    val accepted = remember { mutableStateMapOf<String, Boolean>() }
    var expanded by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("安装模拟器运行组件", style = MaterialTheme.typography.titleMedium)
            Text("Android Emulator 与 ADB · 安装到 Yxi 专用 SDK 目录", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (licenses == null) TextButton({
                busy = true; notice = ""
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { installer.listPendingLicenses(packages) }
                        if (result.ok && result.missing.isEmpty()) {
                            licenses = result
                            result.statuses.forEach { accepted[it.id] = it.alreadyAccepted }
                        } else notice = result.reason ?: "未找到组件信息：${result.missing.joinToString()}"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice = "无法获取组件许可信息，请稍后重试。" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("查看组件与许可") }
            licenses?.statuses?.forEach { license ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accepted[license.id] == true, { accepted[license.id] = it }, enabled = !busy)
                    TextButton({ expanded = if (expanded == license.id) null else license.id }) { Text(license.id + if (license.alreadyAccepted) " · 已接受" else " · 阅读条款") }
                }
                if (expanded == license.id) Text(license.text, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
            val reviewed = licenses
            if (reviewed != null) Button({
                cancel.set(false); busy = true; installing = true; notice = "正在安装运行组件…"
                val ids = accepted.filterValues { it }.keys.toSet()
                val hashes = reviewed.statuses.associate { it.id to it.hash }
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { installer.install(packages, ids, cancel::get, hashes) }
                        notice = if (result.installed) "运行组件已安装。下一步需要准备系统镜像和虚拟设备。" else result.reason.orEmpty()
                        if (result.installed) onInstalled()
                    } catch (e: CancellationException) { cancel.set(true); throw e }
                    catch (_: Exception) { notice = "安装未完成，请重新检查组件状态。" }
                    finally { busy = false; installing = false }
                }
            }, enabled = !busy && reviewed.statuses.isNotEmpty() && reviewed.statuses.all { accepted[it.id] == true }) { Text("同意所选许可并安装") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (installing) TextButton({ cancel.set(true); notice = "正在停止安装…" }) { Text("取消安装") }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        }
    }
}
