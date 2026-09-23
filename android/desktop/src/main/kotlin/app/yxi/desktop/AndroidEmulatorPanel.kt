package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retained at App scope so dismissing the panel never loses owned process handles. */
@Composable
internal fun AndroidEmulatorPanel(open: Boolean, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var environment by remember { mutableStateOf<AndroidEmulatorEnvironment.Result?>(null) }
    var launcher by remember { mutableStateOf<AndroidEmulatorLauncher?>(null) }
    var running by remember { mutableStateOf(emptyList<String>()) }
    var exits by remember { mutableStateOf(emptyMap<String, AndroidEmulatorLauncher.ExitInfo>()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val bridge = remember { AndroidDeviceBridge(managedSdkRoot = java.io.File(Store.dir, "android-sdk")) }
    var devices by remember { mutableStateOf<AndroidDeviceBridge.DeviceList?>(null) }
    var selectedSerial by remember { mutableStateOf("") }
    var apkPath by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var deviceBusy by remember { mutableStateOf(false) }
    var checkingAcceleration by remember { mutableStateOf(false) }
    var acceleration by remember { mutableStateOf<AndroidAcceleration.Result?>(null) }
    val windows = remember { System.getProperty("os.name").startsWith("Windows", true) }
    DisposableEffect(Unit) { onDispose { launcher?.stopAll() } }
    LaunchedEffect(open, refresh) {
        if (!open || !windows) return@LaunchedEffect
        busy = true
        try {
            val found = withContext(Dispatchers.IO) { AndroidEmulatorEnvironment(managedSdkRoot = java.io.File(Store.dir, "android-sdk")).discover() }
            if (launcher == null) launcher = AndroidEmulatorLauncher(found) else launcher!!.updateEnvironment(found)
            environment = found
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = "环境读取失败：${e.message.orEmpty()}" }
        finally { busy = false }
    }
    LaunchedEffect(open) {
        while (open) {
            running = launcher?.runningAvds().orEmpty()
            exits = launcher?.exits().orEmpty()
            delay(1000)
        }
    }
    LaunchedEffect(open, environment, refresh) {
        val adb = environment?.tools?.firstOrNull { it.name == "adb" }?.path
        if (!open || adb == null) return@LaunchedEffect
        try { devices = bridge.devices(adb) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { devices = AndroidDeviceBridge.DeviceList(emptyList(), e.message) }
    }
    if (!open) return
    WorkbenchDialog(onDismissRequest = close, title = { Text("Android 模拟器") }, text = {
        Column(Modifier.widthIn(min = 360.dp, max = 560.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("运行在本机 Windows · 不占用远程服务器的模拟器资源", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (!windows) Text("请在 Windows 电脑上使用本地 Android 模拟器。")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (windows && environment?.missing?.contains("sdkmanager") == true) AndroidSetupCard { refresh++ }
            if (windows && environment?.sdkRoot == java.io.File(Store.dir, "android-sdk").absolutePath &&
                environment?.missing?.contains("sdkmanager") == false &&
                environment?.missing?.any { it == "emulator" || it == "adb" } == true) AndroidComponentsCard { refresh++ }
            environment?.let { env ->
                if (windows && env.usable && env.sdkRoot == java.io.File(Store.dir, "android-sdk").absolutePath)
                    AndroidDeviceSetupCard { name ->
                        busy = true
                        scope.launch {
                            try {
                                val found = withContext(Dispatchers.IO) { AndroidEmulatorEnvironment(managedSdkRoot = java.io.File(Store.dir, "android-sdk")).discover() }
                                check(name in found.avds) { "设备创建结果尚未出现在本机列表，请刷新核对" }
                                val engine = launcher ?: AndroidEmulatorLauncher(found).also { launcher = it }
                                engine.updateEnvironment(found); environment = found
                                val result = withContext(Dispatchers.IO) { engine.launch(name) }
                                notice = if (result.started) "已启动 $name，请等待开机。" else result.reason.orEmpty()
                                running = engine.runningAvds()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = "设备已创建，但自动启动未完成：${e.message.orEmpty()}" }
                            finally { busy = false }
                        }
                    }
                if (env.sdkRoot != null) Text("SDK · ${env.sdkRoot}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                env.tools.firstOrNull { it.name == "emulator" }?.let { executable ->
                    TextButton({
                        checkingAcceleration = true
                        scope.launch {
                            try { acceleration = AndroidAcceleration.inspect(executable.path) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { acceleration = AndroidAcceleration.Result(false, "检查未完成：${e.message.orEmpty()}") }
                            finally { checkingAcceleration = false }
                        }
                    }, enabled = !checkingAcceleration && !busy) { Text(if (checkingAcceleration) "正在检查虚拟化…" else "检查运行环境") }
                    acceleration?.let { result ->
                        Text(if (result.ready) "硬件加速可用" else "硬件加速尚未就绪", color = if (result.ready) Tokens.current.success else Tokens.current.warning)
                        Text(result.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    }
                }
                if (env.avds.isEmpty() && !busy) Text("没有找到虚拟设备。需要准备 Android SDK、系统镜像并创建虚拟设备后才能运行。")
                env.avds.forEach { name ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                val exit = exits[name]
                                Text(when {
                                    name in running -> "模拟器进程运行中"
                                    exit?.stoppedByUser == true || exit?.code == 0 -> "已停止"
                                    exit != null -> "模拟器已退出 · 错误码 ${exit.code}"
                                    else -> "本地虚拟设备"
                                }, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                                if (exit != null) TextButton({
                                    scope.launch {
                                        try { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(exit.log) } }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { notice = "日志无法打开：${e.message.orEmpty()}" }
                                    }
                                }, enabled = exit.log.isFile) { Text("查看启动日志") }
                            }
                            TextButton({
                                val engine = launcher ?: return@TextButton
                                if (name in running) { engine.stop(name); notice = "正在停止 $name" }
                                else {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) { engine.launch(name) }
                                            notice = if (result.started) "已启动 $name，请等待模拟器窗口完成开机。" else result.reason.orEmpty()
                                            running = engine.runningAvds()
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { notice = "启动失败：${e.message.orEmpty()}" }
                                        finally { busy = false }
                                    }
                                }
                            }, enabled = !busy && env.tools.any { it.name == "emulator" }) { Text(if (name in running) "停止" else "运行") }
                        }
                    }
                }
                if (env.missing.isNotEmpty()) Text("缺少组件：${env.missing.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                env.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.current.warning) }
            }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            devices?.let { result ->
                HorizontalDivider()
                Text("安装与运行应用", style = MaterialTheme.typography.titleMedium)
                result.error?.let { Text(it, color = Tokens.current.warning) }
                result.devices.forEach { device ->
                    QuietChoice(selectedSerial == device.serial, { selectedSerial = device.serial }, enabled = device.online && !deviceBusy,
                        label = { Text("${device.model.ifBlank { device.serial }} · ${device.serial} · " + when {
                            device.online -> "已连接"
                            device.unauthorized -> "等待设备授权"
                            else -> "离线"
                        }) })
                }
                if (result.devices.isEmpty() && result.error == null) Text("设备启动后点击刷新设备。", color = Tokens.current.textMuted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(apkPath, { apkPath = it }, enabled = !deviceBusy, singleLine = true, modifier = Modifier.weight(1f), label = { Text("本机 APK 文件路径") })
                    TextButton({
                        val owner = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull { it.isFocused }
                        val dialog = java.awt.FileDialog(owner, "选择 Android 安装包", java.awt.FileDialog.LOAD)
                        try {
                            dialog.file = "*.apk"
                            dialog.isVisible = true
                            dialog.files.firstOrNull()?.let { file ->
                                if (file.isFile && file.extension.equals("apk", true)) apkPath = file.absolutePath
                                else notice = "请选择 APK 文件。"
                            }
                        } finally { dialog.dispose() }
                    }, enabled = !deviceBusy) { Text("选择文件") }
                }
                OutlinedTextField(packageName, { packageName = it }, enabled = !deviceBusy, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("应用包名，例如 app.yxi") })
                val adb = environment?.tools?.firstOrNull { it.name == "adb" }?.path
                val ready = adb != null && result.devices.any { it.serial == selectedSerial && it.online } && !deviceBusy
                Row {
                    TextButton({
                        val serial = selectedSerial; val path = apkPath
                        deviceBusy = true
                        scope.launch {
                            try { notice = bridge.install(adbPath = adb, serial = serial, apk = java.io.File(path)).detail }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = "安装失败：${e.message.orEmpty()}" }
                            finally { deviceBusy = false }
                        }
                    }, enabled = ready && apkPath.isNotBlank()) { Text("安装 APK") }
                    TextButton({
                        val serial = selectedSerial; val pkg = packageName.trim()
                        deviceBusy = true
                        scope.launch {
                            try { notice = bridge.launch(adbPath = adb, serial = serial, packageName = pkg).detail }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = "启动失败：${e.message.orEmpty()}" }
                            finally { deviceBusy = false }
                        }
                    }, enabled = ready && packageName.isNotBlank()) { Text("启动应用") }
                }
                if (deviceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text("关闭面板不会停止模拟器；退出 Yxi 时会停止由本次启动的实例。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
        }
    }, confirmButton = { TextButton({ refresh++ }, enabled = windows && !busy) { Text("刷新设备") } },
        dismissButton = { TextButton(close) { Text("关闭") } })
}
