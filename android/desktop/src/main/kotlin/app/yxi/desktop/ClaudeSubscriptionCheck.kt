package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable internal fun ClaudeSubscriptionCheck(runtime: LocalRuntimeInstallation, dialogModifier: Modifier = Modifier,
    verify: suspend (LocalRuntimeInstallation, File) -> Unit = { selected, cwd ->
        ClaudeSubscriptionProbe.verify(selected.command, cwd, System.getenv() + ("CLAUDE_CONFIG_DIR" to selected.home))
    }) {
    ClaudeSubscriptionCheckAction(runtime.id, runtime.command.joinToString(" "), "需要检查的本机工作目录",
        System.getProperty("user.home"), runtime.ready, dialogModifier) { directory ->
        val cwd = File(directory)
        require(cwd.isAbsolute && cwd.isDirectory) { "请选择已存在的本机工作目录" }
        verify(runtime, cwd)
    }
}

@Composable internal fun RemoteClaudeSubscriptionCheck(conn: Conn, dialogModifier: Modifier = Modifier,
    verify: suspend (String) -> Unit = { ClaudeSubscriptionProbe.verifyRemote(conn.ssh, it) }) {
    ClaudeSubscriptionCheckAction(conn, "${conn.host.label} · 服务器 Claude Code", "需要检查的服务器工作目录",
        "/", conn.status == Conn.Status.Connected && conn.ssh.isConnected, dialogModifier) { directory ->
        check(conn.ssh.isConnected) { "服务器已断开，请重新连接" }
        require(directory.startsWith('/') && directory.none { it < ' ' }) { "请输入服务器绝对工作目录" }
        verify(directory)
    }
}

@Composable private fun ClaudeSubscriptionCheckAction(identity: Any, description: String, directoryLabel: String,
    initialDirectory: String, ready: Boolean, dialogModifier: Modifier, verify: suspend (String) -> Unit) {
    var open by remember(identity) { mutableStateOf(false) }
    TextButton({ open = true }, enabled = ready) { Text("检查官方认证") }
    if (open) {
        val scope = rememberCoroutineScope()
        var directory by remember { mutableStateOf(initialDirectory) }
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        WorkbenchDialog(onDismissRequest = { open = false }, modifier = dialogModifier, title = { Text("检查 Claude 官方认证") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(directory, { directory = it; result = ""; error = "" }, Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, label = { Text(directoryLabel) })
                Text("按此目录的原生配置核对认证来源，不发送任务，也不保存新的线路配置。", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotBlank()) Text(result)
                if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            }
        }, confirmButton = {
            TextButton({ if (!busy) {
                busy = true; result = ""; error = ""
                val requestedDirectory = directory
                scope.launch {
                try {
                    verify(requestedDirectory)
                    result = "认证来源核对通过。订阅额度与实际任务线路尚未验证，此检查不会应用配置到任务。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "认证检查未完成" }
                finally { busy = false }
            } } }, enabled = ready && !busy && directory.isNotBlank()) { Text(if (busy) "检查中…" else "开始检查") }
        }, dismissButton = { TextButton({ open = false }) { Text(if (busy) "取消检查" else "关闭") } })
    }
}
