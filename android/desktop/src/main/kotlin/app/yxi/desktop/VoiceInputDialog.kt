package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun VoiceInputDialog(conn: Conn, close: () -> Unit, useText: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val recorder = remember { DesktopRecorder() }
    var checking by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var editorShown by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var clearedText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }
    var seconds by remember { mutableStateOf(0) }
    var microphones by remember { mutableStateOf<List<String>>(emptyList()) }
    var microphone by remember { mutableStateOf(Store.pref("voiceMicrophone", "")) }
    var deviceMenu by remember { mutableStateOf(false) }
    var devicesRevision by remember { mutableStateOf(0) }
    var setupOpen by remember { mutableStateOf(false) }
    var pendingAudio by remember { mutableStateOf<ByteArray?>(null) }
    val windows = remember { System.getProperty("os.name").startsWith("Windows") }
    var systemVoice by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(systemVoice, editorShown) {
        if (systemVoice && editorShown) {
            withFrameNanos { }
            runCatching { editorFocus.requestFocus() }
        }
    }
    NativeOverlay(deviceMenu)
    LaunchedEffect(devicesRevision) {
        microphones = withContext(Dispatchers.IO) { runCatching { DesktopRecorder.microphones() }.getOrDefault(emptyList()) }
    }
    SideEffect { VoiceActivity.busy = checking || recording || transcribing; VoiceActivity.hasDraft = text.isNotBlank() || pendingAudio != null }
    DisposableEffect(Unit) { onDispose { recorder.cancel(); pendingAudio?.fill(0); VoiceActivity.busy = false; VoiceActivity.hasDraft = false } }
    suspend fun recognizePending() {
        val audio = pendingAudio ?: return
        check(conn.ssh.isConnected) { "请重新连接服务器，再重试识别" }
        val result = DesktopAsr.transcribe(conn.ssh, directory, audio.copyOf())
        text = if (text.isBlank()) result else text.trimEnd() + "\n" + result
        editorShown = true; copied = false; clearedText = ""
        audio.fill(0); pendingAudio = null
    }
    fun retryRecognition() {
        if (transcribing || pendingAudio == null) return
        transcribing = true; error = ""
        scope.launch {
            try { recognizePending() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "识别失败，录音仍可重试" }
            finally { transcribing = false }
        }
    }
    fun finishRecording() {
        if (!recording || transcribing) return
        recording = false; transcribing = true
        scope.launch {
            try {
                pendingAudio = withContext(Dispatchers.IO) { recorder.stopWav() }
                recognizePending()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "语音识别失败" }
            finally { transcribing = false }
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            level = recorder.level; seconds = recorder.seconds
            if (!recorder.running) { finishRecording(); break }
            delay(100)
        }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text("语音输入") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (windows) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canSwitch = !checking && !recording && !transcribing && pendingAudio == null
                FilterChip(!systemVoice, { systemVoice = false }, enabled = canSwitch, label = { Text("服务器识别") })
                FilterChip(systemVoice, { systemVoice = true; editorShown = true }, enabled = canSwitch, label = { Text("Windows 语音") })
            }
            if (systemVoice) {
                Text("点击下方文本框，按 Win + H 开始系统语音输入。", style = MaterialTheme.typography.titleSmall)
                Text("使用 Windows 在线语音服务，需要网络和系统麦克风权限，不经所选 SSH 服务器。文字先进入编辑框，不自动发送给 Agent。结束前请在系统语音面板停止听写。", style = MaterialTheme.typography.bodySmall)
            } else {
            Text("识别服务器：${conn.host.label} · ${conn.host.hostname}", style = MaterialTheme.typography.titleSmall)
            Text("录音通过SSH发送到此服务器的 yxi-asr 识别，文字先供你编辑，不自动发送给Agent。", style = MaterialTheme.typography.bodySmall)
            TextButton({ setupOpen = true }, enabled = !checking && !recording && !transcribing) { Text("服务器尚未安装？查看安装步骤") }
            Row {
                Box(Modifier.weight(1f)) {
                    OutlinedButton({ deviceMenu = true }, enabled = !checking && !recording && !transcribing) { Text(microphone.ifBlank { "系统默认麦克风" }, maxLines = 2) }
                    DropdownMenu(deviceMenu, { deviceMenu = false }) {
                        DropdownMenuItem(text = { Text("系统默认麦克风") }, onClick = { microphone = ""; Store.setPref("voiceMicrophone", ""); deviceMenu = false })
                        microphones.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { microphone = name; Store.setPref("voiceMicrophone", name); deviceMenu = false }) }
                    }
                }
                TextButton({ devicesRevision++ }, enabled = !checking && !recording && !transcribing) { Text("刷新设备") }
            }
            when {
                checking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("准备录音与识别服务…") }
                recording -> { Text("正在录音 · ${seconds}s / 120s"); LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth()) }
                transcribing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在识别…") }
            }
            if (!checking && !transcribing) {
                if (recording) Button(::finishRecording) { Text("停止并识别") }
                else if (pendingAudio != null) Row {
                    TextButton(::retryRecognition) { Text("重试识别") }
                    TextButton({ pendingAudio?.fill(0); pendingAudio = null; error = "" }) { Text("丢弃此段录音") }
                }
                else OutlinedButton({ scope.launch {
                    checking = true; error = ""
                    try {
                        check(conn.ssh.isConnected) { "请先连接要使用的服务器" }
                        directory = DesktopAsr.prepare(conn.ssh)
                        withContext(Dispatchers.IO) { recorder.start(microphone) }
                        recording = true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "无法开始录音" }
                    finally { checking = false }
                } }) { Text(if (text.isBlank()) "开始录音" else "继续录音并追加") }
            }
            }
            if (editorShown || text.isNotBlank()) {
                OutlinedTextField(text, { text = it; copied = false; clearedText = "" }, Modifier.fillMaxWidth().focusRequester(editorFocus), label = { Text("识别文字，可编辑") }, minLines = 3, maxLines = 8)
                Row {
                    TextButton({
                        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null); copied = true }
                            .onFailure { error = "复制失败：${it.message}" }
                    }, enabled = text.isNotBlank()) { Text(if (copied) "已复制" else "复制文字") }
                    TextButton({ clearedText = text; text = ""; copied = false; editorShown = true }, enabled = text.isNotEmpty() && !checking && !recording && !transcribing) { Text("清空") }
                    if (clearedText.isNotEmpty()) TextButton({ text = clearedText; clearedText = ""; copied = false }, enabled = !checking && !recording && !transcribing) { Text("撤销清空") }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ useText(text) }, enabled = text.isNotBlank() && !checking && !recording && !transcribing) { Text("加入输入框") } }, dismissButton = { TextButton(close) { Text("取消并关闭") } })
    if (setupOpen) VoiceSetupDialog(conn.host) { setupOpen = false }
}
