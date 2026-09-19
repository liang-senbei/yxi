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

@Composable
internal fun ShopPane(owner: String, generation: Long, purchases: ShopPurchaseStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(owner, generation) { DesktopShop { path, method, body -> MeAuth.accountRequest(owner, path, method, body, generation) } }
    var catalog by remember { mutableStateOf<ShopCatalog?>(null) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reviewing by remember { mutableStateOf<ShopItem?>(null) }
    var retrying by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<ShopReceipt?>(null) }
    val busy = purchases.running.containsKey(owner)
    val pending = purchases.forOwner(owner)
    fun work(block: suspend () -> Unit) {
        if (purchases.running.containsKey(owner)) return
        purchases.running[owner] = true; error = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "请求未确认，请查看订单记录" }
            finally { purchases.running.remove(owner) }
        }
    }
    fun refresh() = work { catalog = api.catalog() }
    LaunchedEffect(owner, generation) { refresh() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, enabled = !busy) { Text("返回钱包") }
            Text("商城", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(::refresh, enabled = !busy) { Text(if (busy) "处理中…" else "刷新") }
        }
        catalog?.let { Text("余额 ${AccountApi.yuan(it.balanceCents)} · 购买后获得兑换码，需自行兑换。", style = MaterialTheme.typography.bodySmall) }
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        if (purchases.error.isNotBlank()) Text(purchases.error, color = Tokens.current.danger)
        if (notice.isNotBlank()) Text(notice, color = Tokens.current.textMuted)
        receipt?.let { result ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("订单 ${result.orderId} · ${AccountApi.yuan(result.cents)}")
                    SelectionContainer { Text(result.code) }
                    Text("兑换码也可在钱包订单记录中找回。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (pending != null) OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上一笔购买仍待核对", style = MaterialTheme.typography.titleSmall)
                Text("${pending.itemId} · 原报价 ${AccountApi.yuan(pending.quotedCents)}。继续会使用同一请求编号，可能完成尚未执行的购买。", style = MaterialTheme.typography.bodySmall)
                TextButton({
                    val item = catalog?.items?.firstOrNull { it.id == pending.itemId }
                    if (item == null) error = "商品不在当前目录中，请先到订单记录核对结果。"
                    else { reviewing = item; retrying = true }
                }, enabled = !busy) { Text("核对或重试原请求") }
                Text("结束本机重试不会取消服务器订单或退款，请先核对钱包订单。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                TextButton({ runCatching { purchases.resolved(pending) }.onFailure { error = it.message.orEmpty() } }, enabled = !busy) { Text("已核对订单，结束本机重试") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (catalog?.items?.isEmpty() == true) item { Text("暂无可购买商品。") }
            items(catalog?.items.orEmpty(), key = { it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("${item.tier.uppercase()} · ${item.days} 天 · ${AccountApi.yuan(item.cents)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button({ reviewing = item; retrying = false }, enabled = !busy && pending == null && purchases.error.isBlank()) { Text("购买") }
                    }
                }
            }
        }
    }
    reviewing?.let { quote -> WorkbenchDialog(onDismissRequest = { if (!busy) reviewing = null }, title = { Text(if (retrying) "核对并重试原购买" else "确认购买") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${quote.name} · ${quote.tier.uppercase()} · ${quote.days} 天 · ${AccountApi.yuan(quote.cents)}\n将使用钱包余额购买兑换码，不是直接开通会员。")
            if (retrying) Text("沿用原请求编号；若服务器已处理则返回原订单，不重复扣款。")
            if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        }
    }, confirmButton = { TextButton({ work {
        val latest = api.catalog()
        catalog = latest
        check(latest.items.firstOrNull { it.id == quote.id } == quote) { "商品或价格已变化，请关闭后按新报价重新确认" }
        check(MeAuth.sessionGeneration == generation && MeAuth.me?.userId == owner) { "登录账号已变化" }
        val request = if (retrying) purchases.forOwner(owner)?.also { check(it.itemId == quote.id) } ?: throw IllegalStateException("原请求已不存在")
            else purchases.begin(owner, quote)
        val result = api.buy(request)
        MeAuth.walletBalanceUpdated(owner, result.balanceCents, generation)
        receipt = result
        catalog = latest.copy(balanceCents = result.balanceCents)
        purchases.resolved(request)
        notice = if (result.replay) "已找回原订单，本次没有重复扣款。"
            else if (result.cents != quote.cents) "服务器返回的扣款金额与确认报价不同，请查看订单并联系支持。"
            else "购买成功，可返回账户页面兑换。"
        reviewing = null
    } }, enabled = !busy) { Text(if (busy) "处理中…" else "确认") } }, dismissButton = { TextButton({ reviewing = null }, enabled = !busy) { Text("取消") } }) }
}
