package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.yxi.agent.Account
import app.yxi.agent.Wish
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 祈愿 —— 抽奖（老板 2026-09-04：「模仿原神那种」，但**代币是我们自己的**：曦光）。
 *
 * ⚠️⚠️ **摇号在服务端，这一页只放动画。** 奖池里有真东西（会员天数），
 * 客户端摇 = 改个包就能中头奖。见 [Wish] 的注释 —— 这里一行随机数都没有。
 *
 * ⚠️ **概率公示的数字来自服务端**（[Wish.Item.rate]），不是客户端写死的。
 * 公示的和实际发奖的必须是同一套数。
 */
@Composable
fun WishScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pool by remember { mutableStateOf<Wish.Pool?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var rates by remember { mutableStateOf(false) }
    var got by remember { mutableStateOf<Wish.Draw?>(null) }

    LaunchedEffect(Unit) { pool = Wish.pool(ctx); loading = false }
    got?.let { d -> WishResult(d) { got = null } }

    // 手上几张曦光 / 离保底还差几抽 —— 真相源是 /api/me；刚抽完用返回值先更新，不等下一次拉取
    var tickets by remember { mutableStateOf(Account.me?.tickets ?: 0) }
    var pityLeft by remember { mutableStateOf(Account.me?.pityRemaining ?: 0) }
    LaunchedEffect(Account.me) {
        Account.me?.let { tickets = it.tickets; pityLeft = it.pityRemaining }
    }
    // ⚠️ **幂等键**：一次「点击」一个 uuid，重试要用**同一个** ——
    //    抽奖每抽扣一张曦光，换个 id 重试就是扣两次（对方契约里专门写了这条）。
    var pendingId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("祈愿"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )
        val p = pool
        when {
            loading -> Hint(t("正在取…"))
            p == null -> Hint(
                t("祈愿还没开通。开了之后：签到攒「曦光」，一张曦光换一次祈愿；\n") +
                    t("奖池的 UP 是**角色**（第一个是云曦）—— 抽到她会一并带上她的立绘头像、\n") +
                    t("专属光环、专属开屏和主题配色。其余是单件装扮，另有少量会员天数。\n") +
                    t("⚠️ 摇号在服务端，概率会公示 —— 公示的和实际发奖的是同一套数。"),
            )
            else -> {
                // 奖池横幅
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Box(
                        Modifier.height(150.dp)
                            .background(Brush.horizontalGradient(listOf(Color(0xFF8AB4F8), Color(0xFFB07AE8), Color(0xFFFDBE5A)))),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(p.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text(
                                t("%d 抽内必出稀有").format(p.pityAt),
                                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
                // 曦光 + 保底
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Row(Modifier.padding(18.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        YxiIcon(Ico.Wish, size = 22.dp, tint = Color(0xFFB07AE8))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("曦光 ×%d").format(tickets), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                t("离保底还差 %d 抽").format(pityLeft.coerceAtLeast(0)),
                                style = MaterialTheme.typography.labelSmall, color = Muted,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(1, 10).forEach { n ->
                        Surface(
                            color = if (tickets >= n) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(100.dp))
                                .clickable(enabled = tickets >= n && !busy) {
                                    busy = true
                                    val rid = pendingId ?: java.util.UUID.randomUUID().toString()
                                    pendingId = rid
                                    scope.launch {
                                        val d = Wish.draw(ctx, n, rid)
                                        if (d != null) {
                                            got = d
                                            tickets = d.tickets
                                            pityLeft = d.pityRemaining
                                            pendingId = null      // 这一次成了，下一次换新 id
                                        }
                                        busy = false
                                    }
                                },
                        ) {
                            Text(
                                t("祈愿 ×%d").format(n), Modifier.padding(vertical = 14.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (tickets >= n) MaterialTheme.colorScheme.onPrimary else Muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                // 概率公示 —— 数字来自服务端
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(22.dp)).clickable { rates = !rates },
                ) {
                    Column(Modifier.padding(18.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Text(t("概率公示"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(if (rates) "▴" else "▾", color = Muted)
                        }
                        AnimatedVisibility(rates) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                p.items.sortedByDescending { it.rate }.forEach { it2 ->
                                    Row {
                                        Text(it2.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "%.2f%%".format(it2.rate * 100),
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                            color = Muted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 抽出来之后：卡片一张张翻出来，稀有的带光。
 * ⚠️ 卡片上写的是**服务端给的结果**，翻面只是表演 —— 翻之前结果就定了。
 */
@Composable
private fun WishResult(d: Wish.Draw, onClose: () -> Unit) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(300 + d.results.size * 160, easing = LinearEasing)) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC0E1116)).clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                d.results.forEachIndexed { i, g ->
                    // 一张张来：第 i 张在 160ms × i 之后翻
                    val at = (clock.value * (300 + d.results.size * 160) - i * 160f) / 300f
                    val p = at.coerceIn(0f, 1f)
                    val glow = rarityColor(g.rarity)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            cameraDistance = 900.dp.toPx()
                            rotationY = -90f + 90f * FLIP.transform(p)
                            alpha = p
                        },
                    ) {
                        Row(
                            Modifier.background(Brush.horizontalGradient(listOf(glow.copy(alpha = 0.18f), Color.Transparent)))
                                .padding(16.dp, 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    rarityLabel(g.rarity) + (if (g.isNew) " · NEW" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = glow,
                                )
                            }
                            g.dupConvertedTo?.let { (k, n2) ->
                                Text(
                                    if (k == "tickets") t("已有 · 折 %d 曦光").format(n2) else t("已有"),
                                    Modifier.padding(end = 10.dp),
                                    style = MaterialTheme.typography.labelSmall, color = Muted,
                                )
                            }
                            if (g.amount > 0) Text(
                                "×" + g.amount,
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                color = glow,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t("点一下关闭 · 还剩 %d 张曦光").format(d.tickets),
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private val FLIP = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1.02f)

@Composable
private fun rarityColor(r: String): Color = when (r) {
    "legendary", "珍藏" -> Amber
    "rare", "稀有" -> Color(0xFFB07AE8)
    else -> Copper
}

private fun rarityLabel(r: String): String = when (r) {
    "legendary" -> t("珍藏")
    "rare" -> t("稀有")
    else -> t("普通")
}

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
