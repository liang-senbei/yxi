package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import app.yxi.agent.Shop
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 钱包页：余额 · 自动续费开关 · 商城入口 · 订单记录。契约 `wallet-mail.md` §7。
 *
 * ⚠️ 余额**只靠余额兑换码兑入**（老板定的，不做充值），所以这里没有「充值」按钮，只有一句怎么来的。
 * ⚠️ 自动续费开关**是真的**（服务端 cron 到期先试续费）—— 之前「不做假开关」那条现在不成立了。
 *    free 档 `autoRenewPriceCents` 为 null：没东西可续，开关不给开，明说。
 * ⚠️ 负余额照常显示（撤销兑换会扣穿），数字红色；到期续费**不会扣成负**（消费路径不许透支）。
 */
@Composable
fun WalletScreen(onShop: () -> Unit, onRedeem: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<Shop.Order>?>(null) }
    var toggling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Account.refresh(ctx); orders = Shop.orders(ctx) }
    val me = Account.me

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("钱包"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp))

        Card {
            Text(t("余额"), style = MaterialTheme.typography.labelMedium, color = Muted)
            val cents = me?.balanceCents ?: 0L
            Text(
                Account.yuan(cents),
                style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
                color = if (cents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (cents < 0) t("有一笔兑换被撤销了 · 下次兑余额券会先抵这笔")
                else t("余额靠「余额兑换码」兑入（会员中心 → 兑换码），可以去商城买会员码，或者到期自动续费。"),
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }

        // 自动续费
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("到期自动续费"), style = MaterialTheme.typography.titleMedium)
                    val price = me?.autoRenewPriceCents
                    Text(
                        when {
                            me == null -> t("读取中")
                            price == null -> t("免费版没有东西可续 —— 兑一张会员码之后这里就能开")
                            else -> t("到期那天从余额扣 %s 续一期 %s；余额不够就正常到期，不会扣成负数").format(Account.yuan(price), me.tier.name.lowercase())
                        },
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                Spacer(Modifier.padding(6.dp))
                Switch(
                    checked = me?.autoRenew == true,
                    enabled = me?.autoRenewPriceCents != null && !toggling,
                    onCheckedChange = { on ->
                        toggling = true
                        scope.launch {
                            val err = Shop.setAutoRenew(ctx, on)
                            toggling = false
                            if (err != null) android.widget.Toast.makeText(ctx, t("没改成：%s").format(err), android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }

        // 商城入口
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onShop),
        ) {
            Row(Modifier.padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("商城"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(t("用余额买 Pro / Ultra 的兑换码，同档叠加、时间往后累加"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        // 订单
        Text(t("订单记录"), style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(18.dp, 6.dp, 18.dp, 0.dp))
        when {
            orders == null -> Card { Text(t("正在取…"), style = MaterialTheme.typography.bodySmall, color = Muted) }
            orders!!.isEmpty() -> Card { Text(t("还没买过东西。"), style = MaterialTheme.typography.bodySmall, color = Muted) }
            else -> orders!!.forEach { od ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(od.itemId.uppercase() + " · " + Account.yuan(od.priceCents), style = MaterialTheme.typography.titleSmall)
                            Text(od.at.take(16).replace('T', ' '), style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        // 码：点一下复制。⚠️ 这是不记名码，谁拿到谁能兑 —— 所以只在你自己的订单里露出来
                        Text(
                            od.code, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("code", od.code))
                                android.widget.Toast.makeText(ctx, t("复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
        if (!orders.isNullOrEmpty()) Text(
            t("码在会员中心 → 兑换码里输，同档会叠加。"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(18.dp, 0.dp).clickable(onClick = onRedeem),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Column(Modifier.padding(18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
