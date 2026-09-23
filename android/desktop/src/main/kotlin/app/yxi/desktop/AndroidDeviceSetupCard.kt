package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun AndroidDeviceSetupCard(onCreated: (String) -> Unit) {
    val installer = remember { AndroidSdkComponentInstaller() }
    val creator = remember { AndroidAvdCreator() }
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<AndroidSdkComponentInstaller.SystemImage>>(emptyList()) }
    var selected by remember { mutableStateOf<AndroidSdkComponentInstaller.SystemImage?>(null) }
    var installed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Yxi_Android") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var showImages by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("创建虚拟手机", style = MaterialTheme.typography.titleMedium)
        Text("选择官方 Android x86_64 系统镜像，安装后创建 Pixel 规格设备。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        TextButton({
            busy = true; notice = ""
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { installer.systemImages() }
                    images = result.images; notice = result.reason.orEmpty(); showImages = result.ok
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "无法获取官方系统镜像列表，请稍后重试。" }
                finally { busy = false }
            }
        }, enabled = !busy) { Text("选择系统镜像") }
        if (showImages) {
            images.take(20).forEach { image ->
                QuietChoice(selected?.path == image.path, {
                    selected = image; installed = false; showImages = false; name = "Yxi_API_${image.apiLevel}"
                }, label = { Text("Android API ${image.apiLevel} · ${image.abi} · ${image.sizeBytes / 1024 / 1024} MB") })
            }
            if (images.isEmpty()) Text("官方仓库暂未提供可用镜像。")
        }
        selected?.let { image ->
            Text("已选 · ${image.path}", style = MaterialTheme.typography.bodySmall)
            if (!installed) key(image.path) {
                AndroidComponentsCard(listOf(image.path), "安装所选系统镜像") { installed = true }
            }
            else {
                OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy, label = { Text("设备名称") }, modifier = Modifier.fillMaxWidth())
                Button({
                    val chosenName = name.trim()
                    busy = true; cancel.set(false); notice = "正在创建虚拟设备…"
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { creator.create(chosenName, image.path, "pixel", cancel::get) }
                            notice = if (result.created) "虚拟设备已创建，正在准备启动。" else result.reason.orEmpty()
                            if (result.created) onCreated(chosenName)
                        } catch (e: CancellationException) { cancel.set(true); throw e }
                        catch (_: Exception) { notice = "创建未完成，请刷新设备列表后核对。" }
                        finally { busy = false }
                    }
                }, enabled = !busy && name.isNotBlank()) { Text("创建并启动") }
                if (busy) TextButton({ cancel.set(true) }) { Text("取消创建") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
    }
}
