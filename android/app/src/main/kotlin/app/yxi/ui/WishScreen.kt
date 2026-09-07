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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
    // 出货：**先擦星星**（老板 2026-09-06 的九宫格设定），擦完再走原来那套结算卡。
    // ⚠️ 动画只是表现层 —— 东西在 draw 接口返回那一刻就已经是他的了，
    //    擦到一半退出、杀进程、断网，收藏页里都在。所以这里不重发请求、也不把擦完当领取条件。
    got?.let { d ->
        // 系统关了动画就别擦、也别放视频，直接给结果（跟 [WishResult] 同一个开关）
        val reduced = reducedMotion()
        var wiped by remember(d) { mutableStateOf(reduced) }
        // ⚠️ **两个 Dialog 必须交叠一小段**。它俩是各自独立的窗口，直接 `if/else` 换的话，
        //    中间有一两帧**谁都没盖住屏幕**，底下浅色的祈愿页会透出来 ——
        //    实测录屏在换卡那一刻有两帧 `#FCF9FC` 的白闪（22 秒录像第 13.20–13.27 秒）。
        //    片尾淡得再准也救不了这一下，因为问题不在颜色而在"没人盖住"。
        var covered by remember(d) { mutableStateOf(reduced) }
        LaunchedEffect(wiped) { if (wiped && !covered) { kotlinx.coroutines.delay(220); covered = true } }
        if (!covered) {
            val best = d.results.maxByOrNull { rank(it.rarity) }
            // ⚠️ 必须走 Dialog：直接摆一个 fillMaxSize 的 Box 会被后面的页面内容盖住
            //    （Compose 里同级后画的在上面），实测就是"擦星星画在祈愿页底下"。
            //    结算卡 WishResult 也是这么做的。
            Dialog(
                onDismissRequest = { },
                // ⚠️ `decorFitsSystemWindows = false` 不能少:结算卡那个 Dialog 传了,
                //    这个不传的话**状态栏和导航栏两条带子在擦拭期间不是舞台底色**,
                //    换成结算卡时那两条会「啪」地变 —— 中间淡得再准,边上照样露馅。
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                // 整屏底色由 [WipeReveal] 自己画 —— 片尾要跟着一起淡到结算卡底色，
                // 分两处画就会在换卡那一刻露出一圈没淡的浅灰。
                WipeReveal(
                    best?.rarity.orEmpty(),
                    Modifier.fillMaxSize(),
                    onDone = { wiped = true },
                )
            }
        }
        // 结算卡后组合 = 后加的窗口 = 盖在上面；等它盖住了，上面那层才撤（见 covered）
        if (wiped) WishResult(d, skipEffect = true) { got = null }
    }
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
    var coll by remember { mutableStateOf(Wish.Collection(emptySet())) }
    // ⚠️ key 必须是 [library] 不是 Unit：老板的用法就是「抽出重复 → 立刻点卡牌库看命座」。
    //    只在进页面拉一次的话，那一刻显示的是抽卡之前的旧数据 —— 结算页刚说完
    //    「已有 · 命座 +1」、卡牌库里数字没动，比不显示还糟。每次开库对一次。
    LaunchedEffect(library) { if (library) Wish.collection(ctx)?.let { coll = it } }
    if (library) Dialog(
        onDismissRequest = { library = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            CardLibrary(coll, live = pool != null)
            Text(
                "✕", Modifier.align(Alignment.TopEnd).padding(18.dp)
                    .clip(RoundedCornerShape(100.dp)).clickable { library = false }.padding(10.dp),
                style = MaterialTheme.typography.titleMedium, color = Muted,
            )
        }
    }

    // 手上几张曦光 / 离保底还差几抽 —— 真相源是 /api/me；刚抽完用返回值先更新，不等下一次拉取
    var tickets by remember { mutableStateOf(Account.me?.tickets ?: 0) }
    var micro by remember { mutableStateOf(Account.me?.micro ?: 0) }
    var perTicket by remember { mutableStateOf(Account.me?.microPerTicket ?: 10) }
    var pityLeft by remember { mutableStateOf(Account.me?.pityRemaining ?: 0) }
    LaunchedEffect(Account.me) {
        Account.me?.let {
            tickets = it.tickets; pityLeft = it.pityRemaining
            micro = it.micro; perTicket = it.microPerTicket
        }
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
                    t("奖池的 UP 是「角色」（第一个是云曦）—— 抽到她会一并带上她的立绘头像、\n") +
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
                            // ⚠️ 零头必须露出来。重复返还给的是微曦，攒不满一张时曦光那个数**一动不动** ——
                            //    不显示零头，玩家会以为返还没到账（他一眼就能看出数字没变，这号人很敏感）。
                            if (micro > 0) Text(
                                t("另有 %d 微曦（满 %d 点自动换 1 张）").format(micro, perTicket),
                                style = MaterialTheme.typography.labelSmall, color = Muted,
                            )
                            Text(
                                t("离保底还差 %d 抽").format(pityLeft.coerceAtLeast(0)),
                                style = MaterialTheme.typography.labelSmall, color = Muted,
                            )
                        }
                    }
                }
                // 曦光不够就直说，并且说清差多少、去哪儿拿 ——
                // ⚠️ 只把按钮变灰是**静默失败**：用户不知道为什么点不动，也不知道下一步该干嘛
                //    （STYLE.md「一切失败都要说出来」「看得见、点得着」）。
                if (tickets < p.singlePullCost) Text(
                    t("曦光不够，还差 %d 张。签到和活动中心能拿。").format(p.singlePullCost - tickets),
                    Modifier.padding(18.dp, 0.dp, 18.dp, 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = Amber,
                ) else if (tickets < p.tenPullCost) Text(
                    t("再攒 %d 张就能十连。").format(p.tenPullCost - tickets),
                    Modifier.padding(18.dp, 0.dp, 18.dp, 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                )
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
                                            // 只在服务端**确实给了**的时候才覆盖（见 Wish.kt 的哨兵）
                                            if (d.micro >= 0) micro = d.micro
                                            if (d.microPerTicket > 0) perTicket = d.microPerTicket
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(7.dp).clip(RoundedCornerShape(100.dp))
                                                .background(rarityColor(it2.rarity)),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(it2.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "%.2f%%".format(it2.rate * 100),
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                            color = Muted,
                                        )
                                    }
                                }
                                // ⚠️ 未就绪的单独一段，**不给概率** —— 它现在抽不到，
                                //    给个数字就是骗人；上面那些 rate 已经是真实可抽概率了。
                                if (p.upcoming.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        t("即将推出（现在抽不到）"),
                                        style = MaterialTheme.typography.labelSmall, color = Muted,
                                    )
                                    p.upcoming.forEach { u ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier.size(7.dp).clip(RoundedCornerShape(100.dp))
                                                    .background(rarityColor(u.rarity).copy(alpha = 0.35f)),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                u.name, Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall, color = Muted,
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
}

/**
 * 抽卡结果的表演 —— 老板 2026-09-04：「抽奖动画要够吸引人」、2026-09-05：「多学学米哈游」。
 * 特效那五层在 [DropStage]（规格 `logto_yxi/design/drop-effect.md`），这里只管**时间轴和内容**：
 *
 * | 起 | 止 | 做什么 |
 * |---:|---:|---|
 * | 120 | 700 | 星轨划过 —— **颜色就是这一批里最高的稀有度**，整段唯一的悬念点 |
 * | 680 | 1300 | 光爆 + 冲击波 |
 * | 700 | 2600 | 粒子 + 神圣光柱 |
 * | 1900 | 3000 | 立绘显形（红档），光效收干净 |
 * | 3050 | 3570 | 小卡 / 文字落定 |
 *
 * ⚠️⚠️ **悬念在划过来那 0.6 秒里，不在最后揭晓那一瞬** —— 这是米哈游那套最有效的一招。
 *    所以星轨、光爆、光柱、粒子**四层必须同色**（[Tier]）：只染一层就是贴了个颜色。
 * ⚠️⚠️ **落定那一帧必须干净**：[DONE] 之后 [DropStage] 一个像素都不画，
 *    停住的画面只有完整立绘 + 几行普通字。**一直炫的东西看第二遍就烦。**
 * ⚠️ **结果在动画开始之前就定死了** —— 服务端摇的（见 [Wish]）。这里只是表演，
 *    颜色预告用的也是已经拿到的结果，不是「边演边摇」。
 * ⚠️ **随时点一下可以跳过**：好看归好看，第 20 次十连没人想再看一遍。
 * ⚠️ 系统关了动效走 [DropStage] 的 reduced 分支：只闪一下光爆，立绘直接淡入。
 */
@Composable
private fun WishResult(d: Wish.Draw, skipEffect: Boolean = false, onClose: () -> Unit) {
    val motion = !reducedMotion()
    // ⚠️ 特效到 [DONE] 就收干净；小卡在那之后才落定，所以总长按小卡算
    val total = DONE + 50f + d.results.size * 110f + 520f
    val clock = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) clock.animateTo(1f, tween(total.toInt(), easing = LinearEasing)) }
    // skipEffect：前面已经放过「擦星星」了，这里就直接落到卡片，别再放一遍星轨光爆 ——
    // 两段动画叠着看，第二段就成了等待
    var skipped by remember { mutableStateOf(skipEffect || !motion) }
    val p = if (skipped) 1f else clock.value
    val ms = p * total

    // 这一批里最高的稀有度 —— 星轨的颜色就是它
    val best = d.results.maxByOrNull { rank(it.rarity) }
    val hero = best?.let { b -> CROWNS.firstOrNull { it.id == b.id } }
    val topColor = rarityColor(best?.rarity.orEmpty())
    val tier = remember(best?.rarity) { Tier.of(best?.rarity.orEmpty()) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 底部那行「点一下关闭」浮在滚动内容之上，得知道它多高才能给列表留出位置 ——
        // 写死 dp 在大字体下会被它盖住最后一张卡（字号一大就折成两行）。
        var footerH by remember { mutableStateOf(0) }
        Box(
            // ⚠️ **必须跟 [WishReveal.Stage] 是同一个常量**：短片片尾淡到的就是它。
            //    这里要是留 0xF2 的半透明,切卡瞬间背后的祈愿页会透出 5%(浅色主题下接近白),
            //    颜色从 #0B0D12 跳到 #151619,接缝就出在这一下。
            Modifier.fillMaxSize().background(WishReveal.Stage)
                .clickable { if (ms >= total * 0.95f) onClose() else skipped = true },
        ) {
            // ── 星轨 / 光爆 / 冲击波 / 粒子 / 光柱，全在 [DropStage] 里，四层同色
            DropStage(tier, ms, reduced = !motion, modifier = Modifier.fillMaxSize())

            // ── 立绘 / 小卡
            Column(
                // ⚠️ 内容比屏幕矮时**居中**（单抽一张卡要在正中），比屏幕高时**能滚**
                //    （十连 = 立绘 + 九张小卡，大字体下必然超一屏）。
                //    verticalScroll 会把 minHeight 原样传给孩子，所以 Arrangement.Center
                //    在没超屏时照样居中 —— 不用再套一层 BoxWithConstraints + heightIn。
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(22.dp, 22.dp, 22.dp, 0.dp)
                    .padding(bottom = with(LocalDensity.current) { footerH.toDp() }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 红色档：整张立绘顶上来
                if (hero != null && rank(best.rarity) >= 3) {
                    // 1900 起显形，到 DONE 正好满 —— 光效收干净的同一刻立绘刚好站定
                    val hp = ((ms - 1900f) / (DONE - 1900f)).coerceIn(0f, 1f)
                    // ⚠️ 出货那一下也用**整张 16:9**，不裁（老板要求，同卡牌库）
                    if (hp > 0f) Box(
                        Modifier.fillMaxWidth(0.94f).aspectRatio(16f / 9f)
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
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
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
                            Text(
                                hero.crown + (if (best.byPity) " · " + t("保底") else ""),
                                style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF),
                            )
                            Text(hero.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                // 其余：一行一张小卡
                d.results.forEachIndexed { i, g ->
                    if (hero != null && g.id == hero.id && rank(g.rarity) >= 3) return@forEachIndexed
                    // ⚠️ 小卡**等特效收干净之后**才翻（DONE + 50）——
                    //    一边炸一边翻卡，两样都看不清
                    val cp = ((ms - DONE - 50f - i * 110f) / 300f).coerceIn(0f, 1f)
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
                                    rarityLabel(g.rarity) + (if (g.isNew) " · NEW" else "") +
                                        (if (g.byPity) " · " + t("保底") else ""),
                                    style = MaterialTheme.typography.labelSmall, color = glow,
                                )
                            }
                            // 重复的东西折成了什么。三种情况，别混：
                            //   micro   → 折成微曦。⚠️ **数字变大但价值变小**（5 微曦 = 半抽），
                            //             所以文案必须带换算，只写「折 5 微曦」会被当成赚了。
                            //   tickets → 老契约的直接折曦光（服务端已切到 micro，留着兼容历史记录）。
                            //   null 且不是新的 → 六命前的重复角色：**只进命座、不返还**，
                            //             这一张的价值就是命座本身，得说出来，不然像是白抽。
                            val dup = g.dupConvertedTo
                            if (dup != null) {
                                val (k, n2) = dup
                                Text(
                                    when (k) {
                                        "micro" -> t("已有 · 折 %d 微曦（%s）").format(n2, pulls(n2, d.microPerTicket))
                                        // 老契约的直接折曦光。**这一支实际到不了** ——
                                        // 结算页只渲染刚抽回来的新结果，历史记录走 [WishHistory]
                                        // （那边才是真的要兼容两种 kind 的地方）。留着是防服务端回滚。
                                        "tickets" -> t("已有 · 折 %d 曦光").format(n2)
                                        else -> t("已有")
                                    },
                                    Modifier.padding(end = 10.dp),
                                    style = MaterialTheme.typography.labelSmall, color = Muted,
                                )
                            } else if (!g.isNew) Text(
                                // ⚠️ 判据要带 kind：返还为 0 今天只可能是六命前的重复角色，
                                //    但那取决于奖池配置（某个稀有度配成 0，重复**装扮**也会走到这儿，
                                //    而装扮没有命座）。跟 [WishHistory] 的判据保持一致。
                                if (g.kind == "character") t("已有 · 命座 +1") else t("已有"),
                                Modifier.padding(end = 10.dp),
                                style = MaterialTheme.typography.labelSmall, color = Muted,
                            )
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
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { footerH = it.height }
                    // 渐变底衬：滚动时卡片会从这行字下面经过，没底衬两样都看不清
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF20B0D12))))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 26.dp, bottom = 22.dp),
                style = MaterialTheme.typography.labelMedium, color = Color(0xB3FFFFFF),
                textAlign = TextAlign.Center,
            )
        }
    }
}


/**
 * 系统设置里「动画时长」调到 0（无障碍 / 省电）时返回 true。
 * ⚠️ 抽卡的**每一段**表现都要听它：擦拭、开片视频、星轨光爆。
 *    只关其中一段等于没关 —— 用户还是得坐着等完。
 */
@Composable
internal fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * 把微曦说成"几抽" —— 光给微曦数没用，玩家心里的单位是**抽**。
 * ⚠️ 基数从服务端来（`microPerTicket`），别写死 10：那是奖池旋钮，改了不发版。
 */
private fun pulls(micro: Long, per: Int): String {
    if (per <= 0) return ""
    val whole = micro / per
    val rest = micro % per
    return when {
        rest == 0L -> t("%d 抽").format(whole)
        whole == 0L && rest * 2 == per.toLong() -> t("半抽")
        else -> t("%.1f 抽").format(micro.toFloat() / per)
    }
}

/** 稀有度排序：蓝 1 < 紫 2 < 金 3 < 红 4。星轨的颜色取这一批里最高的那个。 */
internal fun rank(r: String): Int = when (r) {
    "红", "character", "legendary" -> 4
    "金", "gold", "tickets" -> 3
    "紫", "rare", "epic" -> 2
    else -> 1
}

private val FLIP = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1.02f)

/**
 * 四档颜色：红=角色、金=曦光、紫=稀有装扮、蓝=普通装扮。
 * ⚠️ **只有 [Tier] 一处定义**（cc-logto_yxi 2026-09-05 定死的值）——
 *    概率公示的圆点、小卡的字、特效那四层必须是同一组色，
 *    分两处写迟早会变成「公示上是这个紫、抽出来是另一个紫」。
 * ⚠️ 别跟 `STYLE.md` §1.4 的**会员档位色**混用，那是另一回事。
 */
internal fun rarityColor(r: String): Color = Tier.of(r).hi

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
