package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun GroupDeliveryControls(conn: Conn, group: String) {
    var paused by remember(conn, group) { mutableStateOf<Boolean?>(null) }
    var updated by remember(conn, group) { mutableStateOf("") }
    var busy by remember(conn, group) { mutableStateOf(false) }
    var error by remember(conn, group) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun request(action: String) {
        busy = true; error = ""
        try {
            check(conn.status == Conn.Status.Connected) { "服务器未连接" }
            val command = """
hub=${'$'}(command -v yxi-hub || true)
if [ -z "${'$'}hub" ] && [ -x "${'$'}HOME/.local/bin/yxi-hub" ]; then hub="${'$'}HOME/.local/bin/yxi-hub"; fi
[ -n "${'$'}hub" ] || exit 1
"${'$'}hub" ${Shell.q(action)} ${Shell.q(group)}
""".trimIndent()
            val raw = conn.ssh.exec(command)
            val data = raw.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.optString("group") == group && it.opt("paused") is Boolean }
                ?: error("未收到组状态，请确认服务器安装了支持暂停控制的新版 yxi-hub")
            paused = data.getBoolean("paused")
            updated = data.optString("updatedAt")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { paused = null; error = e.message.orEmpty() }
        finally { busy = false }
    }
    LaunchedEffect(conn, group) { request("status") }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (paused) { true -> "组消息投递已暂停"; false -> "组消息投递已开放"; null -> "组消息投递状态未确认" }, style = MaterialTheme.typography.titleSmall)
            Text("控制后续 yxi-hub 消息；不会中断运行中的 Agent，也不会自动发送本地待处理指令。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (updated.isNotBlank()) Text("服务器更新时间 · $updated", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Tokens.current.warning)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                if (paused != null) TextButton({ scope.launch { request(if (paused == true) "resume" else "pause") } }, enabled = !busy) { Text(if (paused == true) "恢复组消息" else "暂停组消息") }
                TextButton({ scope.launch { request("status") } }, enabled = !busy) { Text("刷新状态") }
            }
        }
    }
}
