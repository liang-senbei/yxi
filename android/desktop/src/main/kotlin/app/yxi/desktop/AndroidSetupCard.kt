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
internal fun AndroidSetupCard(onInstalled: () -> Unit) {
    val installer = remember { AndroidSdkBootstrap() }
    val scope = rememberCoroutineScope()
    val cancel = remember { AtomicBoolean(false) }
    var manifest by remember { mutableStateOf<AndroidSdkBootstrap.Manifest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0L to 0L) }
    var notice by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("准备 Android 开发工具", style = MaterialTheme.typography.titleMedium)
            Text("下载 Google 官方命令行工具到 Yxi 数据目录。接下来还需安装系统镜像并创建虚拟设备。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            val pkg = manifest
            if (pkg == null) TextButton({
                cancel.set(false); busy = true; notice = ""; progress = 0L to 0L
                scope.launch {
                    try {
                        // 取消接线进探测：inspect 在阶段边界轮询这个 flag，
                        // 探测期的取消按钮不再是只能置 flag 的摆设
                        val result = withContext(Dispatchers.IO) { installer.inspect(isCancelled = { cancel.get() }) }
                        manifest = result.manifest; notice = result.reason.orEmpty(); accepted = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = e.message.orEmpty() }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("获取官方安装信息") }
            else {
                Text("命令行工具 ${pkg.version} · ${pkg.sizeBytes / 1024 / 1024} MB", style = MaterialTheme.typography.bodyMedium)
                Text(installer.targetDir.absolutePath, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                TextButton({ showLicense = !showLicense }) { Text(if (showLicense) "收起许可内容" else "查看许可内容") }
                if (showLicense) Text(pkg.licenses.joinToString("\n\n").ifBlank { "未能读取许可内容，请重新获取。" },
                    Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accepted, { accepted = it }, enabled = !busy && pkg.licenses.isNotEmpty())
                    Text("我已阅读并同意此工具包的许可条款", style = MaterialTheme.typography.bodySmall)
                }
                Button({
                    cancel.set(false); busy = true; notice = ""; progress = 0L to pkg.sizeBytes
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                installer.install(pkg, { done, total -> scope.launch { progress = done to total } }, cancel::get)
                            }
                            notice = if (result.installed) "开发工具已安装，可以继续准备模拟器组件。" else result.reason.orEmpty()
                            if (result.installed) onInstalled()
                        } catch (e: CancellationException) { cancel.set(true); throw e }
                        catch (e: Exception) { notice = "安装未完成：${e.message.orEmpty()}" }
                        finally { busy = false }
                    }
                }, enabled = accepted && !busy && pkg.licenses.isNotEmpty()) { Text("下载并安装工具") }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progress.second > 0) Text("${progress.first / 1024 / 1024} / ${progress.second / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall)
                TextButton({ cancel.set(true); notice = "正在取消…" }) { Text("取消") }
            }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        }
    }
}
