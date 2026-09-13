package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val voiceInstallCommands = "unzip yxi-asr-setup.zip -d yxi-asr-setup\ncd yxi-asr-setup\nbash install-asr.sh"

private fun exportVoiceSetup(): Boolean {
    val dialog = FileDialog(null as java.awt.Frame?, "保存语音服务安装包", FileDialog.SAVE)
    try {
        dialog.file = "yxi-asr-setup.zip"; dialog.isVisible = true
        val name = dialog.file ?: return false
        val target = File(dialog.directory, name).canonicalFile
        check(!target.toPath().startsWith(Store.dir.canonicalFile.toPath())) { "请选择应用配置目录之外的位置" }
        val temporary = Files.createTempFile(target.parentFile.toPath(), "yxi-asr-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                for (resource in listOf("install-asr.sh", "yxi-asr")) {
                    val bytes = DesktopAsr::class.java.getResourceAsStream("/app/yxi/desktop/asr/$resource")?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray() } ?: error("安装资源缺失")
                    zip.putNextEntry(ZipEntry(resource)); zip.write(bytes); zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(("Yxi服务器语音识别\n需要Linux、Python3及venv、ffmpeg、curl、tar、unzip。\n在当前SSH用户下执行，模型和环境安装到用户目录。\n\n$voiceInstallCommands\n").toByteArray())
                zip.closeEntry()
            }
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return true
    } finally { dialog.dispose() }
}

@Composable
internal fun VoiceSetupDialog(host: Host, close: () -> Unit) {
    var note by remember { mutableStateOf("") }
    WorkbenchDialog(onDismissRequest = close, title = { Text("安装服务器语音识别") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("目标服务器：${host.label} · ${host.hostname}")
            Text("1. 导出安装包，通过文件面板上传到这台服务器。\n2. 确保已安装 Python3/venv、ffmpeg、curl、tar 和 unzip。\n3. 在服务器终端执行以下命令。", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(voiceInstallCommands, style = CodeStyle) }
            Text("模型下载包约1GB，安装后保留量化模型。服务只在用户目录安装，不修改Agent钩子。", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton({ runCatching { if (exportVoiceSetup()) note = "已导出，请上传到服务器后解压使用。" }.onFailure { note = it.message.orEmpty() } }) { Text("导出安装包") }
                TextButton({ Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(voiceInstallCommands), null); note = "命令已复制" }) { Text("复制命令") }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(close) { Text("返回语音输入") } })
}
