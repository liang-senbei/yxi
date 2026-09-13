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
    var budget by remember(conn, group) { mutableStateOf<JSONObject?>(null) }
    var editingBudget by remember(conn, group) { mutableStateOf(false) }
    var messageLimit by remember(conn, group) { mutableStateOf("20") }
    var duration by remember(conn, group) { mutableStateOf("60") }
    val scope = rememberCoroutineScope()
    suspend fun request(action: String, count: Int? = null, minutes: Int? = null) {
        busy = true; error = ""
        try {
            check(conn.status == Conn.Status.Connected) { "服务器未连接" }
            val command = """
hub=${'$'}(command -v yxi-hub || true)
if [ -z "${'$'}hub" ] && [ -x "${'$'}HOME/.local/bin/yxi-hub" ]; then hub="${'$'}HOME/.local/bin/yxi-hub"; fi
[ -n "${'$'}hub" ] || exit 1
"${'$'}hub" ${Shell.q(action)} ${Shell.q(group)} ${if (count != null && minutes != null) "$count $minutes" else ""}
""".trimIndent()
            val raw = conn.ssh.exec(command)
            val data = raw.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.optString("group") == group && it.opt("paused") is Boolean }
                ?: error("未收到组状态，请确认服务器安装了支持暂停控制的新版 yxi-hub")
            paused = data.getBoolean("paused")
            updated = data.optString("updatedAt")
            budget = data
            if (action == "limit") editingBudget = false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { paused = null; budget = null; error = e.message.orEmpty() }
        finally { busy = false }
    }
    LaunchedEffect(conn, group) { request("status") }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val capped = budget?.let { it.optBoolean("budgetExpired") || (it.optInt("messageLimit") > 0 && it.optInt("messagesUsed") >= it.optInt("messageLimit")) } == true
            Text(when { paused == null -> "组消息投递状态未确认"; paused == true -> "组消息投递已暂停"; capped -> "协作预算已到限"; else -> "组消息投递已开放" }, style = MaterialTheme.typography.titleSmall)
            budget?.takeIf { it.optInt("messageLimit") > 0 }?.let { value ->
                val deadline = if (value.has("deadline") && !value.isNull("deadline")) value.optDouble("deadline").toLong() else 0L
                val deadlineText = if (deadline > 0) " · 截止 " + java.time.Instant.ofEpochSecond(deadline).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) else ""
                Text("消息 ${value.optInt("messagesUsed")} / ${value.getInt("messageLimit")}$deadlineText", style = MaterialTheme.typography.bodySmall)
            }
            Text("控制后续 yxi-hub 消息；不会中断运行中的 Agent，也不会自动发送本地待处理指令。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (updated.isNotBlank()) Text("服务器更新时间 · $updated", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Tokens.current.warning)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                if (paused != null) TextButton({ scope.launch { request(if (paused == true) "resume" else "pause") } }, enabled = !busy) { Text(if (paused == true) "恢复组消息" else "暂停组消息") }
                TextButton({ scope.launch { request("status") } }, enabled = !busy) { Text("刷新状态") }
                if (budget?.optBoolean("budgetSupported") == true) TextButton({ editingBudget = !editingBudget }, enabled = !busy) { Text("设置新预算") }
            }
            if (editingBudget) {
                Text("保存后从现在开始重新计数；群发每位接收者占一条。暂停状态保持不变。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(messageLimit, { messageLimit = it }, singleLine = true, enabled = !busy, label = { Text("消息上限（1–10000）") })
                OutlinedTextField(duration, { duration = it }, singleLine = true, enabled = !busy, label = { Text("有效分钟（1–10080）") })
                val count = messageLimit.toIntOrNull()
                val minutes = duration.toIntOrNull()
                Row {
                    TextButton({ scope.launch { request("limit", count, minutes) } }, enabled = !busy && count != null && count in 1..10000 && minutes != null && minutes in 1..10080) { Text("保存新预算") }
                    TextButton({ editingBudget = false }, enabled = !busy) { Text("取消") }
                }
            }
        }
    }
}
