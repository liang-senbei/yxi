package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Account
import app.yxi.agent.AccountApi
import app.yxi.agent.Shop
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 商城：两件商品（Pro / Ultra 一个月的兑换码）+ 余额。买 → 弹出码 + 引导去会员中心兑。
 *
 * ⚠️ **价格照抄 `/api/shop`**，不写死（改价不发版）。
 * ⚠️ **`requestId` 在确认框弹出时生成，同一次购买重试都用它** —— 买东西会扣钱，网络超时再点一次不能扣两次。
 * ⚠️ 余额不足服务端回 400 + needCents，界面直说「还差 ¥x」，不做假按钮。
 */
@Composable
fun ShopScreen(onRedeem: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cat by remember { mutableStateOf<Shop.Catalog?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<Shop.Item, String>?>(null) }   // (商品, requestId)
    var busy by remember { mutableStateOf(false) }
    var bought by remember { mutableStateOf<Shop.Bought?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { cat = Shop.catalog(ctx); failed = cat == null }
    val balance = Account.me?.balanceCents ?: cat?.balanceCents ?: 0L

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("商城"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp))
        Text(
            t("余额 %s · 买到的是一张不记名兑换码，自己兑或送人都行；同档叠加、时间往后累加。").format(AccountApi.yuan(balance)),
            style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(18.dp, 0.dp),
        )
        when {
            failed -> Hint(t("取不到商品 —— 网络不通，或者登录过期了。"))
            cat == null -> Hint(t("正在取…"))
            else -> cat!!.items.forEach { it ->
                val short = balance < it.priceCents
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    Row(Modifier.padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name.ifBlank { it.id.uppercase() }, style = MaterialTheme.typography.titleMedium)
                            Text(t("%d 天 · %s 的兑换码").format(it.days, it.tier.uppercase()), style = MaterialTheme.typography.bodySmall, color = Muted)
                            if (short) Text(t("还差 %s").format(AccountApi.yuan(it.priceCents - balance)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        Surface(
                            color = if (short) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable(enabled = !short && !busy) {
                                // ⚠️ requestId 在这一刻定下来，确认框里重试也用它
                                confirm = it to java.util.UUID.randomUUID().toString()
                            },
                        ) {
                            Text(
                                AccountApi.yuan(it.priceCents), Modifier.padding(18.dp, 9.dp),
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                color = if (short) Muted else MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
        err?.let { Hint(it) }
    }

    confirm?.let { (item, rid) ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = null },
            title = { Text(t("买 %s？").format(item.name.ifBlank { item.id.uppercase() })) },
            text = { Text(t("从余额扣 %s，得到一张 %d 天的 %s 兑换码。").format(AccountApi.yuan(item.priceCents), item.days, item.tier.uppercase())) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true; err = null
                    scope.launch {
                        val r = Shop.buy(ctx, item.id, rid)
                        busy = false
                        r.onSuccess { bought = it; confirm = null }
                        r.onFailure { e ->
                            val be = (e as? Shop.BuyException)?.err
                            err = when (be) {
                                is Shop.BuyError.Insufficient -> t("余额不够：还差 %s（余额 %s）").format(AccountApi.yuan(be.needCents - be.balanceCents), AccountApi.yuan(be.balanceCents))
                                is Shop.BuyError.Other -> t("没买成：%s").format(be.msg)
                                null -> t("没买成：%s").format(e.message ?: "")
                            }
                            // 余额不足不必再让人重试；别的错留着确认框，同一个 requestId 再点
                            if (be is Shop.BuyError.Insufficient) confirm = null
                        }
                    }
                }) { Text(if (busy) t("买着…") else t("确认")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirm = null }) { Text(t("算了")) } },
        )
    }

    bought?.let { b ->
        AlertDialog(
            onDismissRequest = { bought = null },
            title = { Text(if (b.replay) t("这单之前已经买过了") else t("买好了")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (b.replay) Text(t("刚才那次其实已经成功，没有重复扣钱。这就是那张码："), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text(
                        b.code, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.clickable {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("code", b.code))
                            android.widget.Toast.makeText(ctx, t("复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    Text(t("%d 天 %s · 已扣 %s · 余额 %s\n码也发到了你的邮件里。去会员中心兑，同档会叠加。").format(b.days, b.tier.uppercase(), AccountApi.yuan(b.priceCents), AccountApi.yuan(b.balanceCents)), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            },
            confirmButton = { TextButton({ bought = null; onRedeem() }) { Text(t("去兑换")) } },
            dismissButton = { TextButton({ bought = null }) { Text(t("先放着")) } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
