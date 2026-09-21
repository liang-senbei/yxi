package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.yxi.agent.GeminiRouteConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GeminiConfigurationCard(conn: Conn) {
    val scope = rememberCoroutineScope()
    var directory by remember(conn) { mutableStateOf("") }
    var status by remember(conn) { mutableStateOf<GeminiRouteConfig.Status?>(null) }
    var base by remember(conn) { mutableStateOf("") }
    var model by remember(conn) { mutableStateOf("") }
    var secret by remember(conn) { mutableStateOf("") }
    var busy by remember(conn) { mutableStateOf(false) }
    var notice by remember(conn) { mutableStateOf("") }
    var confirm by remember(conn) { mutableStateOf(false) }
    suspend fun load() {
        val home = conn.ssh.exec("printf '%s' \"\$HOME\"").trim()
        check(home.startsWith('/') && home.none { it < ' ' }) { "无法确认服务器配置目录" }
        directory = "$home/.gemini"
        val next = GeminiRouteConfig.status(conn.ssh, directory)
        status = next; base = next.baseUrl.orEmpty(); model = next.model.orEmpty()
    }
    LaunchedEffect(conn) {
        busy = true
        try { load() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "无法读取 Gemini 配置，请检查服务器连接后刷新。" }
        finally { busy = false }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Gemini · 当前服务器配置", style = MaterialTheme.typography.titleLarge)
            Text("${conn.host.label} · 服务器用户级", color = Tokens.current.textMuted)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(base, { base = it }, label = { Text("API 地址") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("具体模型名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text(if (status?.apiKeyFingerprint != null) "新 API Key（留空保留原密钥）" else "API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            status?.settingsError?.let { Text(it, color = Tokens.current.warning) }
            Text("保存后使用 API Key 认证；运行中的 Agent 可能需要重新连接。供应商列表管理仍在接入。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ confirm = true }, enabled = !busy && status != null && status?.settingsError == null && model.isNotBlank() && (secret.isNotBlank() || status?.apiKeyFingerprint != null)) { Text("保存并应用") }
                TextButton({ scope.launch {
                    busy = true
                    try { load(); notice = "已刷新配置" }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice = "读取失败，当前编辑内容保留。" }
                    finally { busy = false }
                } }, enabled = !busy) { Text("刷新") }
            }
        }
    }
    if (confirm) WorkbenchDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text("应用 Gemini 配置") }, text = {
        Text("将修改 ${conn.host.label} 的 Gemini 用户级配置及认证方式，可能影响该服务器同一用户下的其他 Gemini 会话。")
    }, confirmButton = { TextButton({
        val patch = GeminiRouteConfig.Patch(base.trim(), secret.takeIf { it.isNotBlank() }, model.trim())
        val expected = status
        busy = true
        scope.launch {
            try {
                check(conn.ssh.isConnected) { "服务器未连接" }
                check(expected != null) { "配置尚未读取" }
                GeminiRouteConfig.apply(conn.ssh, directory, patch, expected)?.let {
                    confirm = false; notice = it; return@launch
                }
                secret = ""; confirm = false; load()
                notice = "配置已写入并重新读取；尚未验证模型请求。"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { confirm = false; notice = "配置未能完整应用，请刷新核对后再试；未自动重试。" }
            finally { busy = false }
        }
    }, enabled = !busy) { Text("确认应用") } }, dismissButton = { TextButton({ confirm = false }, enabled = !busy) { Text("取消") } })
}
