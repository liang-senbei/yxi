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

private val hubInstallCommands = """
mkdir -p "${'$'}HOME/.local/bin"
if [ -f "${'$'}HOME/.local/bin/yxi-hub" ]; then
  cp -p "${'$'}HOME/.local/bin/yxi-hub" "${'$'}HOME/.local/bin/yxi-hub.backup.${'$'}(date +%Y%m%d%H%M%S)"
fi
install -m 700 ./yxi-hub "${'$'}HOME/.local/bin/yxi-hub"
export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
""".trimIndent()

@Composable
internal fun HubSetupDialog(host: Host, close: () -> Unit) {
    var note by remember { mutableStateOf("") }
    WorkbenchDialog(onDismissRequest = close, title = { Text("服务器协作服务") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${host.label} · ${host.hostname}", style = MaterialTheme.typography.titleSmall)
            Text("导出本版配套 yxi-hub，上传到测试服务器的工作目录，再执行下方命令。需要 Linux、Bash、Python3 和 tmux。", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(hubInstallCommands, style = CodeStyle) }
            Text("旧文件会保留备份；groups.json、消息日志和预算记录继续使用。新终端也需要将 ~/.local/bin 放到 PATH 前面，已有 Agent 可能要重开才能使用新路径。", style = MaterialTheme.typography.bodySmall)
            Text("需要 Claude 会话启动时自动读取组规，可另外导出钩子配置脚本，上传后执行 python3 configure-hub.py install；移除用 python3 configure-hub.py remove。配置前保留备份，不发送消息或启动 Agent。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            TextButton({ runCatching {
                val dialog = FileDialog(null as java.awt.Frame?, "导出协作钩子配置脚本", FileDialog.SAVE)
                try {
                    dialog.file = "configure-hub.py"; dialog.isVisible = true
                    dialog.file?.let { filename ->
                        val target = File(dialog.directory, filename).canonicalFile
                        check(!target.toPath().startsWith(Store.dir.canonicalFile.toPath())) { "请选择应用配置目录之外的位置" }
                        val content = Host::class.java.getResourceAsStream("/app/yxi/desktop/hub/configure-hub.py")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("安装资源缺失")
                        DurableFile.replace(target, content.replace("\r\n", "\n"))
                        note = "钩子配置脚本已导出，上传后按说明运行。"
                    }
                } finally { dialog.dispose() }
            }.onFailure { note = "导出失败：${it.message}" } }) { Text("导出钩子配置脚本") }
            Row {
                TextButton({
                    runCatching {
                        val dialog = FileDialog(null as java.awt.Frame?, "导出服务器协作脚本", FileDialog.SAVE)
                        try {
                            dialog.file = "yxi-hub"; dialog.isVisible = true
                            dialog.file?.let { filename ->
                                val target = File(dialog.directory, filename).canonicalFile
                                check(!target.toPath().startsWith(Store.dir.canonicalFile.toPath())) { "请选择应用配置目录之外的位置" }
                                val content = Host::class.java.getResourceAsStream("/app/yxi/desktop/hub/yxi-hub")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("安装资源缺失")
                                DurableFile.replace(target, content.replace("\r\n", "\n"))
                                note = "已导出，请上传到目标服务器后安装。"
                            }
                        } finally { dialog.dispose() }
                    }.onFailure { note = "导出失败：${it.message}" }
                }) { Text("导出 yxi-hub") }
                TextButton({ runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(hubInstallCommands), null)
                    note = "安装命令已复制"
                }.onFailure { note = it.message.orEmpty() } }) { Text("复制安装命令") }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(close) { Text("返回协作组") } })
}
