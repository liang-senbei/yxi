package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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

    // ⚠️ 进页面先把账号资料刷一遍：曦光可能刚在签到那边变过（`/api/me` 是它的真相源）
    LaunchedEffect(Unit) {
        Account.refresh(ctx)
        pool = Wish.pool(ctx)
        loading = false
    }
    got?.let { d -> WishResult(d) { got = null } }
    // 卡牌库：《神之冠冕》八顶。**没抽到的只给剪影**，别让人以为已经有了。
    var library by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    if (history) Dialog(
        onDismissRequest = { history = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            WishHistory()
            Text(
                "✕", Modifier.align(Alignment.TopEnd).padding(18.dp)
                    .clip(RoundedCornerShape(100.dp)).clickable { history = false }.padding(10.dp),
                style = MaterialTheme.typography.titleMedium, color = Muted,
            )
        }
    }
    // 拥有哪些 —— **服务端说了算**（见 Wish.collection 的注释）。拿不到就当空，界面会说明还没开通。
    var ownedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { Wish.collection(ctx)?.let { ownedIds = it } }
    if (library) Dialog(
        onDismissRequest = { library = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            CardLibrary(owned = ownedIds, live = pool != null)
            Text(
                "✕", Modifier.align(Alignment.TopEnd).padding(18.dp)
                    .clip(RoundedCornerShape(100.dp)).clickable { library = false }.padding(10.dp),
                style = MaterialTheme.typography.titleMedium, color = Muted,
            )
        }
    }

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
        // 卡牌库入口：奖池开没开都能看 —— 这是设定集，不是奖励
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { library = true },
        ) {
            Row(Modifier.padding(18.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                YxiIcon(Ico.Crown, size = 22.dp, tint = Color(0xFFE8912D))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("卡牌库"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        t("《神之冠冕》· %d 位").format(CROWNS.size),
                        style = MaterialTheme.typography.labelSmall, color = Muted,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = Muted)
            }
        }

        // 抽过什么都记在这儿 —— 能自证没被坑（记录在服务端，换手机也在）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { history = true },
        ) {
            Row(Modifier.padding(18.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                YxiIcon(Ico.Chat, size = 22.dp, tint = Muted)
                Spacer(Modifier.width(10.dp))
                Text(t("祈愿记录"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("›", style = MaterialTheme.typography.titleMedium, color = Muted)
            }
        }

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
                    // ⚠️ 价钱用服务端给的（ultra 十连九折就体现在 tenPullCost 上），**不自己乘 0.9**
                    listOf(1 to p.singlePullCost, 10 to p.tenPullCost).forEach { (n, price) ->
                        Surface(
                            color = if (tickets >= price) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(100.dp))
                                .clickable(enabled = tickets >= price && !busy) {
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
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    t("祈愿 ×%d").format(n),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (tickets >= price) MaterialTheme.colorScheme.onPrimary else Muted,
                                )
                                Text(
                                    // 打了折就说一句 —— 便宜了得让人看见
                                    if (n == 10 && price < 10) t("%d 曦光 · 会员九折").format(price)
                                    else t("%d 曦光").format(price),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tickets >= price) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    else Muted,
                                )
                            }
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
 * 抽卡结果的表演 —— 老板 2026-09-04：「抽奖动画要够吸引人」。三拍：
 *
 *  1. **星轨**（0–900ms）：暗下来，一道光划过整屏，**颜色就是这一批里最高的稀有度**
 *     （蓝 → 紫 → 金 → 红）。这是整段唯一的悬念点：光一变红，人就知道出货了。
 *  2. **光爆**（900–1250ms）：星轨落到屏心炸开。
 *  3. **翻卡**（1250ms 起）：一张张翻出来，每张 140ms 错开；**红色档直接出立绘**。
 *
 * ⚠️ **结果在动画开始之前就定死了** —— 服务端摇的（见 [Wish]）。这里只是表演，
 *    颜色预告用的也是已经拿到的结果，不是「边演边摇」。
 * ⚠️ **随时点一下可以跳过**：好看归好看，第 20 次十连没人想再看一遍。
 * ⚠️ 系统关了动效就直接给最终画面（design/STYLE.md §2.4）。
 */
@Composable
private fun WishResult(d: Wish.Draw, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val total = 1250f + d.results.size * 140f + 300f
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(total.toInt(), easing = LinearEasing)) }
    var skipped by remember { mutableStateOf(!motion) }
    val p = if (skipped) 1f else clock.value
    val ms = p * total

    // 这一批里最高的稀有度 —— 星轨的颜色就是它
    val best = d.results.maxByOrNull { rank(it.rarity) }
    val hero = best?.let { b -> CROWNS.firstOrNull { it.id == b.id } }
    val topColor = rarityColor(best?.rarity.orEmpty())

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xF20B0D12))
                .clickable { if (ms >= total * 0.95f) onClose() else skipped = true },
        ) {
            // ── ① 星轨 + ② 光爆
            val trail = (ms / 900f).coerceIn(0f, 1f)
            val burst = ((ms - 900f) / 350f).coerceIn(0f, 1f)
            if (trail < 1f || burst < 1f) Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                if (trail > 0f && trail < 1f) {
                    val e = TRAIL.transform(trail)
                    // 从左下斜着划到屏心
                    val x = -w * 0.2f + e * (w * 0.7f)
                    val y = h * 1.05f - e * (h * 0.55f)
                    // 拖尾：十来个逐渐变淡的点
                    repeat(14) { i ->
                        val k = i / 14f
                        val tx = x - k * w * 0.34f
                        val ty = y + k * h * 0.26f
                        drawCircle(topColor, (10f - k * 8f) * density, Offset(tx, ty), alpha = (1f - k) * 0.9f)
                    }
                    drawCircle(Color.White, 7f * density, Offset(x, y))
                }
                if (burst > 0f) {
                    val r = burst * size.minDimension * 0.75f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(topColor.copy(alpha = (1f - burst) * 0.75f), Color.Transparent),
                            center = Offset(w / 2f, h * 0.5f), radius = r.coerceAtLeast(1f),
                        ),
                        radius = r, center = Offset(w / 2f, h * 0.5f),
                    )
                    repeat(24) { i ->
                        val a = (PI2 * i / 24f)
                        val rr = r * (0.55f + (i % 5) * 0.09f)
                        drawCircle(
                            topColor, (3.4f - burst * 2f).coerceAtLeast(0.5f) * density,
                            Offset(w / 2f + kotlin.math.cos(a) * rr, h * 0.5f + kotlin.math.sin(a) * rr),
                            alpha = (1f - burst) * 0.9f,
                        )
                    }
                }
            }

            // ── ③ 翻卡
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 红色档：整张立绘顶上来
                if (hero != null && rank(best.rarity) >= 3) {
                    val hp = ((ms - 1250f) / 520f).coerceIn(0f, 1f)
                    if (hp > 0f) Box(
                        Modifier.fillMaxWidth(0.72f).aspectRatio(0.72f)
                            .clip(RoundedCornerShape(20.dp))
                            .graphicsLayer {
                                alpha = hp
                                scaleX = 0.86f + 0.14f * FLIP.transform(hp)
                                scaleY = 0.86f + 0.14f * FLIP.transform(hp)
                            },
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(hero.art),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000)),
                            ),
                        )
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(hero.crown, style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
                            Text(hero.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                // 其余：一行一张小卡
                d.results.forEachIndexed { i, g ->
                    if (hero != null && g.id == hero.id && rank(g.rarity) >= 3) return@forEachIndexed
                    val cp = ((ms - 1250f - i * 140f) / 300f).coerceIn(0f, 1f)
                    if (cp <= 0f) return@forEachIndexed
                    val glow = rarityColor(g.rarity)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).graphicsLayer {
                            cameraDistance = 900.dp.toPx()
                            rotationY = -90f + 90f * FLIP.transform(cp)
                            alpha = cp
                        },
                    ) {
                        Row(
                            Modifier.background(
                                Brush.horizontalGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent)),
                            ).padding(14.dp, 12.dp),
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
            }
            Text(
                if (ms >= total * 0.95f) t("点一下关闭 · 还剩 %d 张曦光").format(d.tickets) else t("点一下跳过"),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp),
                style = MaterialTheme.typography.labelMedium, color = Color(0xB3FFFFFF),
            )
        }
    }
}

private const val PI2 = 6.2831855f
private val TRAIL = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

/** 稀有度排序：蓝 1 < 紫 2 < 金 3 < 红 4。星轨的颜色取这一批里最高的那个。 */
internal fun rank(r: String): Int = when (r) {
    "红", "character", "legendary" -> 4
    "金", "gold", "tickets" -> 3
    "紫", "rare", "epic" -> 2
    else -> 1
}

private val FLIP = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1.02f)

/** 四档颜色，跟老板定的对齐：红=角色、金=曦光、紫=稀有装扮、蓝=普通装扮。 */
internal fun rarityColor(r: String): Color = when (rank(r)) {
    4 -> Color(0xFFFF5C6E)      // 红
    3 -> Color(0xFFFFC24D)      // 金
    2 -> Color(0xFFB07AE8)      // 紫
    else -> Color(0xFF5FA8F5)   // 蓝
}

internal fun rarityLabel(r: String): String = when (rank(r)) {
    4 -> t("角色")
    3 -> t("曦光")
    2 -> t("稀有")
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
