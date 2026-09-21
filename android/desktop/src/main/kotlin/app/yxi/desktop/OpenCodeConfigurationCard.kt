package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import app.yxi.agent.OpenCodeRouteConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OpenCodeConfigurationCard(conn: Conn) {
    val scope = rememberCoroutineScope()
    var status by remember(conn) { mutableStateOf<OpenCodeRouteConfig.Status?>(null) }
    var model by remember(conn) { mutableStateOf("") }
    var busy by remember(conn) { mutableStateOf(false) }
    var menu by remember(conn) { mutableStateOf(false) }
    var notice by remember(conn) { mutableStateOf("") }
    suspend fun load() {
        val directory = conn.ssh.exec("printf '%s' \"\${XDG_CONFIG_HOME:-\$HOME/.config}/opencode\"").trim()
        check(directory.startsWith('/') && directory.none { it < ' ' }) { "无法确认配置目录" }
        val next = OpenCodeRouteConfig.status(conn.ssh, directory)
        status = next
        model = next.selectedModel.orEmpty()
    }
    fun act(block: suspend () -> Unit) { scope.launch {
        busy = true; notice = ""
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "操作未完成，请检查服务器连接或配置后重试。" }
        finally { busy = false }
    } }
    LaunchedEffect(conn) {
        busy = true
        try { load() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "无法读取 OpenCode 配置，请重新连接后刷新。" }
        finally { busy = false }
    }
    val current = status
    val choices = current?.providers.orEmpty().flatMap { provider ->
        provider.modelIds.map { "${provider.id}/$it" }
    }.distinct().sorted()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, Modifier.padding(end = 10.dp).size(20.dp), tint = Tokens.current.textSecondary)
                    Text("OpenCode · 服务器默认模型", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton({ act { load() } }, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("刷新")
                    }
                }
                Text("会话和项目可以使用各自的模型设置。", color = Tokens.current.textMuted)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                current?.parseError?.let { Text(it, color = Tokens.current.danger) }
                if (current?.bothExist == true) Text("检测到两份 OpenCode 配置。请先合并配置，再在这里保存默认模型。", color = Tokens.current.warning)
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
                    label = { Text("供应商 / 模型 ID") }, singleLine = true, enabled = !busy && current?.parseError == null,
                    supportingText = { Text("填写实际模型标识，例如供应商 ID/模型 ID。") })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box {
                        OutlinedButton({ menu = true }, enabled = !busy && choices.isNotEmpty()) {
                            Text("选择已配置模型"); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 320.dp)) {
                            choices.forEach { id -> DropdownMenuItem(text = { Text(id) },
                                leadingIcon = { Icon(if (model == id) Icons.Outlined.CheckCircle else Icons.Outlined.Code, null, Modifier.size(18.dp)) },
                                onClick = { model = id; menu = false }) }
                        }
                    }
                    Button(onClick = {
                        val before = current ?: return@Button
                        val selected = model.trim()
                        act {
                            val error = OpenCodeRouteConfig.apply(conn.ssh, before.dir, OpenCodeRouteConfig.Patch(selected), before)
                            if (error != null) { notice = error; return@act }
                            val checked = OpenCodeRouteConfig.status(conn.ssh, before.dir)
                            check(checked.selectedModel == selected && !checked.bothExist && checked.parseError == null)
                            status = checked; model = selected
                            notice = "默认模型已保存。现有会话可能仍使用其单独设置。"
                        }
                    }, enabled = !busy && current != null && current.parseError == null && !current.bothExist &&
                        OpenCodeRouteConfig.MODEL.matches(model.trim()) && model.trim() != current.selectedModel) {
                        Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("保存默认模型")
                    }
                }
                if (notice.isNotBlank()) Text(notice, color = Tokens.current.textSecondary)
            }
        }
        if (current != null) {
            Text("已配置的供应商", style = MaterialTheme.typography.titleSmall)
            if (current.providers.isEmpty()) Text("尚无自定义供应商。新增供应商和凭据编辑尚未接入。", color = Tokens.current.textMuted)
            current.providers.forEach { provider ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Tokens.current.surface2, modifier = Modifier.size(38.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text((provider.name?.takeIf { it.isNotBlank() } ?: provider.id).take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Text(provider.name?.takeIf { it.isNotBlank() } ?: provider.id, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (current.selectedModel?.substringBefore('/') == provider.id) {
                                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = Tokens.current.success)
                                Text("当前默认", color = Tokens.current.success, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text("${provider.id} · ${provider.modelIds.size} 个已配置模型", color = Tokens.current.textMuted)
                        if (provider.apiKeyFingerprint != null) Text("凭据已填写", color = Tokens.current.textSecondary)
                    }
                }
            }
        }
    }
}
