package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.yxi.agent.OpenCodeRouteConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OpenCodeProviderForm(
    original: OpenCodeRouteConfig.Provider?, hostLabel: String,
    close: () -> Unit, save: suspend (OpenCodeRouteConfig.ProviderPatch) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var id by remember(original?.id) { mutableStateOf(original?.id.orEmpty()) }
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var npm by remember(original?.id) { mutableStateOf(original?.npm.orEmpty()) }
    var base by remember(original?.id) { mutableStateOf(original?.baseURL.orEmpty()) }
    var key by remember(original?.id) { mutableStateOf("") }
    var clearKey by remember(original?.id) { mutableStateOf(false) }
    var models by remember(original?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val dirty = name != original?.name.orEmpty() || npm != original?.npm.orEmpty() ||
        base != original?.baseURL.orEmpty() || key.isNotBlank() || clearKey || models.isNotBlank()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(close, enabled = !busy) { Icon(Icons.Outlined.ArrowBack, "返回供应商列表") }
                Column {
                    Text(if (original == null) "新增供应商" else "编辑供应商", style = MaterialTheme.typography.headlineSmall)
                    Text("$hostLabel · OpenCode", color = Tokens.current.textMuted)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), label = { Text("供应商 ID") },
                singleLine = true, enabled = !busy && original == null,
                supportingText = { Text("用于模型名称的前缀；已有供应商的 ID 保持不变。") })
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("显示名称") }, singleLine = true, enabled = !busy)
            OutlinedTextField(npm, { npm = it }, Modifier.fillMaxWidth(), label = { Text("SDK 包（npm）") },
                singleLine = true, enabled = !busy, supportingText = { Text("填写与供应商接口协议对应的 SDK 包。") })
            OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("请求地址") }, singleLine = true, enabled = !busy)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true,
                enabled = !busy && !clearKey, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(if (original == null) "可按供应商要求填写凭据。" else "留空保留当前凭据。") })
            if (original?.apiKeyFingerprint != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(clearKey, { clearKey = it }, enabled = !busy)
                Text("清空此处的 API Key")
            }
            if (!original?.modelIds.isNullOrEmpty()) Text("已有模型：${original!!.modelIds.joinToString("、")}",
                color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(models, { models = it }, Modifier.fillMaxWidth(), label = { Text("补充模型 ID") },
                minLines = 3, maxLines = 6, enabled = !busy,
                supportingText = { Text("每行一个实际模型 ID，已有模型的设置会保留。") })
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(close, enabled = !busy) { Text("取消") }
                Button({
                    error = ""; busy = true
                    scope.launch {
                        try {
                            fun changed(value: String, previous: String?, trim: Boolean = false): String? {
                                if (original != null && value == previous.orEmpty()) return null
                                val next = if (trim) value.trim() else value
                                return next.takeUnless { original == null && it.isEmpty() }
                            }
                            val patch = OpenCodeRouteConfig.ProviderPatch(id.trim(), changed(name, original?.name),
                                changed(npm, original?.npm, true), changed(base, original?.baseURL, true),
                                if (clearKey) "" else key.takeIf { it.isNotBlank() },
                                models.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.distinct())
                            OpenCodeRouteConfig.validateProviderPatch(patch)
                            save(patch)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "保存未完成，请重试。" }
                        finally { busy = false }
                    }
                }, enabled = !busy && dirty && OpenCodeRouteConfig.PROVIDER_ID.matches(id.trim())) {
                    Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("保存供应商")
                }
            }
        }
    }
}
