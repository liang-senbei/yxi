package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ProviderQuotaBadge(line: Lines.Line) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(line.id, line.baseUrl, line.apiKey, line.token) { mutableStateOf<ProviderQuota.Snapshot?>(null) }
    var busy by remember(line.id) { mutableStateOf(false) }
    var error by remember(line.id, line.baseUrl) { mutableStateOf("") }
    val supported = ProviderQuota.endpoint(line.baseUrl) != null
    Column(Modifier.widthIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!supported) Text("额度查询暂未接入", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
        else {
            val data = snapshot
            if (data != null) {
                QuotaWindow("5 小时", data.fiveHour)
                QuotaWindow("每周", data.weekly)
                Text("更新于 " + quotaTime(data.fetchedAt), style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
            }
            TextButton({
                busy = true; error = ""
                scope.launch {
                    try { snapshot = ProviderQuota.fetch(line) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "额度查询未完成，请检查网络、供应商密钥或套餐权限。" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text(if (busy) "查询额度…" else if (data == null) "查询额度" else "刷新额度") }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.labelSmall, color = Tokens.current.warning)
        }
    }
}

private fun quotaTime(ms: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

@Composable
private fun QuotaWindow(label: String, window: ProviderQuota.Window?) {
    if (window == null) Text("$label · 未提供", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
    else {
        Text("$label · 已用 ${"%.0f".format(window.usedPercent)}%", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(progress = { (window.usedPercent / 100).toFloat() }, modifier = Modifier.fillMaxWidth().height(3.dp))
        window.resetsAt?.let { Text("${quotaTime(it)} 重置", style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted) }
    }
}
