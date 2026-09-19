package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.AccountApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class WalletOrder(val id: String, val item: String, val cents: Long, val code: String, val at: String)
internal fun parseWalletOrders(body: String): List<WalletOrder> {
    val items = JSONObject(body).getJSONArray("items")
    return (0 until items.length()).map { index -> items.getJSONObject(index).let {
        WalletOrder(it.getString("orderId"), it.getString("itemId"), it.getLong("priceCents"), it.getString("code"), it.getString("at"))
    } }.distinctBy { it.id }
}

@Composable
internal fun WalletPane(owner: String, generation: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<WalletOrder>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    fun refresh() {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try {
                val (status, body) = MeAuth.accountRequest(owner, "/api/shop/orders", "GET", null, generation)
                check(status in 200..299) { AccountApi.httpErr(status, body) }
                if (MeAuth.sessionGeneration != generation || MeAuth.me?.userId != owner) return@launch
                orders = parseWalletOrders(body)
                val problem = MeAuth.refresh()
                if (MeAuth.sessionGeneration == generation && problem != null) error = "订单已读取，账户资料暂未刷新：$problem"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "钱包读取失败，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(owner, generation) { refresh() }
    fun setRenewal(enabled: Boolean) {
        if (busy) return
        busy = true; error = ""; notice = ""
        scope.launch {
            try {
                val (status, body) = MeAuth.accountRequest(owner, "/api/me/wallet", "PATCH",
                    JSONObject().put("autoRenew", enabled).toString(), generation)
                check(status in 200..299) { AccountApi.httpErr(status, body) }
                val result = JSONObject(body)
                MeAuth.walletUpdated(owner, result, generation)
                notice = if (result.getBoolean("autoRenew")) "自动续费已开启。" else "自动续费已关闭。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "续费设置未确认：${e.message}。请刷新核对，系统不会自动重试。" }
            finally { busy = false }
        }
    }
    val me = MeAuth.me?.takeIf { it.userId == owner && MeAuth.sessionGeneration == generation }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("返回账户") }
            Text("钱包与订单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(::refresh, enabled = !busy) { Text(if (busy) "刷新中…" else "刷新") }
        }
        if (me != null) OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(AccountApi.yuan(me.balanceCents), style = MaterialTheme.typography.headlineMedium,
                    color = if (me.balanceCents < 0) Tokens.current.danger else MaterialTheme.colorScheme.primary)
                Text("余额通过余额券兑换获得。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动续费", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(me.autoRenew, ::setRenewal, enabled = !busy && (me.autoRenew || me.autoRenewPriceCents != null))
                }
                Text("开启后，会员到期时会按服务器续费规则从余额扣款。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                me.autoRenewPriceCents?.let { Text("服务器当前续费价格：${AccountApi.yuan(it)}", style = MaterialTheme.typography.bodySmall) }
                    ?: Text("当前档位暂无可用续费报价。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
            }
        }
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        if (notice.isNotBlank()) Text(notice, color = Tokens.current.textMuted)
        Text("订单记录", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (orders?.isEmpty() == true) item { Text("还没有订单。", color = Tokens.current.textMuted) }
            items(orders.orEmpty(), key = { it.id }) { order ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${order.item} · ${AccountApi.yuan(order.cents)}", style = MaterialTheme.typography.titleSmall)
                        Text(order.at, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                        SelectionContainer { Text(order.code) }
                        TextButton({ runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(order.code), null)
                        }.onSuccess { notice = "兑换码已复制，可返回账户页面兑换。" }.onFailure { error = "复制失败，可选中文字手动复制。" } }) { Text("复制兑换码") }
                    }
                }
            }
        }
    }
}
