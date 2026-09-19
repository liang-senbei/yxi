package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.AccountApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

internal fun redemptionMessage(result: JSONObject): String {
    val server = result.optString("msg").takeIf { it.isNotBlank() && it != "null" }
    return when {
        result.optBoolean("revoked") -> server ?: "这张码此前的兑换已被撤销，不能再次兑换。"
        result.optBoolean("replay") -> server ?: "这张码已经兑换过，本次没有重复增加权益。"
        server != null -> server
        result.optString("kind") == "balance" -> "余额到账 ${AccountApi.yuan(result.getLong("amountCents"))}，当前余额 ${AccountApi.yuan(result.getLong("balanceCents"))}"
        else -> "兑换成功，会员权益已更新。"
    }
}

@Composable
internal fun RedeemCodeCard(owner: String, generation: Long) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }
    val normalized = code.trim().uppercase(Locale.ROOT)
    val valid = Regex("^[A-Z0-9][A-Z0-9-]{3,39}$").matches(normalized)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("兑换码", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(code, { code = it.take(80) }, enabled = !busy, singleLine = true,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("粘贴或输入兑换码") })
            Text("会员码和余额券均可使用；保留原有连字符，不重新排列兑换码。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (code.isNotBlank() && !valid) Text("兑换码应为4–40位字母、数字或连字符。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall,
                color = if (success) MaterialTheme.colorScheme.primary else Tokens.current.danger)
            Button({
                val submitted = normalized
                busy = true; message = ""; success = false
                scope.launch {
                    try {
                        val (status, body) = MeAuth.accountRequest(owner, "/api/me/redeem", "POST",
                            JSONObject().put("code", submitted).toString(), generation)
                        if (MeAuth.sessionGeneration != generation || MeAuth.me?.userId != owner) return@launch
                        if (status !in 200..299) { message = AccountApi.httpErr(status, body); return@launch }
                        val result = JSONObject(body)
                        message = redemptionMessage(result)
                        success = !result.optBoolean("revoked")
                        code = ""
                        val refreshError = MeAuth.refresh()
                        if (MeAuth.sessionGeneration == generation && MeAuth.me?.userId == owner && refreshError != null)
                            message += "\n兑换结果已收到，账户资料暂未刷新，可稍后点击刷新。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = (e.message ?: "兑换结果未确认") + "；如网络中断，请刷新账户核对后再重试。" }
                    finally { busy = false }
                }
            }, enabled = valid && !busy && MeAuth.signedIn && MeAuth.sessionGeneration == generation) {
                Text(if (busy) "兑换中…" else "兑换")
            }
        }
    }
}
