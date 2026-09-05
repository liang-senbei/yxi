package app.yxi.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.BuildConfig
import app.yxi.agent.Update
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.Sftp
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.*
import kotlinx.coroutines.launch

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

/**
 * 设置页。把原来散在各处的入口收在一起：版本、更新、公钥、后台放行。
 *
 * ⚠️ 「检查更新」有**三种**结果，必须分清（决策 D23）：
 * 有新版本 / 已是最新 / **连不上没查到**。
 * 把「没查到」显示成「已是最新」是在骗用户 —— 他会以为自己是最新版，
 * 而实际可能落后好几版、正带着已知的 bug 在用。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun SettingsScreen(
    store: HostStore,
    keys: KeyManager,
    host: Host?,
    /** 共用的连接（[app.yxi.ui.rememberHostSession]）。检查更新直接搭它，不再自己建一条 */
    ssh: SshSession?,
    /** 界面此刻显示的连接错误 —— 诊断报告要带上它，见 [DevMode.diagnose] */
    connectError: String? = null,
    /** 点「会员中心」跳过去（那一页在 MainActivity 那层管） */
    onMember: () -> Unit = {},
    /**
     * 这一页是「我的」还是「设置」。
     *
     * ⚠️ **两者不是一个东西**（用户 2026-09-04：「设置和我的不是一个东西」）：
     * · `true`  = **我的**：只有「我」（头像 / 昵称 / 签名）和会员中心 —— 关于**你这个人**的。
     * · `false` = **设置**：版本、手机功能、界面、关于与帮助、开发者 —— 关于**这个 App** 的。
     * 走同一个 composable 是因为两页共用一堆私有小部件（[Card] / [SectionLabel] / [Hint2]…），
     * 拆成两个文件反而要把它们全提出去。
     */
    mine: Boolean = true,
    /** 「我的」最底下那一行「设置」—— 跳到设置页（同一个 composable 的 `mine = false`） */
    onPrefs: () -> Unit = {},
    /** 「我的」宫格里的「工单中心」 */
    onTickets: () -> Unit = {},
    /** 「我的」里的「趋势」——每天烧了多少 token / 多少钱 */
    onTrend: () -> Unit = {},
    /** 「我的」里的「钱包」——余额 / 自动续费 / 商城 / 订单 */
    onWallet: () -> Unit = {},
    /** 「我的」宫格里的「邮件」——我们发给你的站内信 */
    onMail: () -> Unit = {},
    /** 祈愿（抽奖） */
    onWish: () -> Unit = {},
    /** 活动中心（签到等） */
    onActivity: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Update.Result?>(null) }
    var sftp by remember { mutableStateOf<Sftp?>(null) }
    // 开发者模式：连点三下版本号 → 输口令。藏起来是因为诊断文本里有主机地址、
    // 用户名这些不该随手给旁人看的东西，不是因为它危险
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    var askPass by remember { mutableStateOf(false) }
    var dev by remember { mutableStateOf(DevMode.unlocked(ctx)) }

    // 铃铛那节收起时的副标题：一眼看出盯着几台
    val watchN = store.hosts.collectAsState().value.count { it.watch }
    val watchSummary = if (watchN > 0) t("盯着 %d 台").format(watchN) else t("一台都没开")

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (mine) t("我的") else t("设置"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )
        if (mine) {

        // 每次进「我的」拉一次 /api/me —— 红点、余额、曦光都靠它。不拉的话只有冷启动那一次是准的，
        // 新来的信要重启 App 才亮点，等于没通知。失败就用缓存的那份，别打断人。
        LaunchedEffect(Unit) { if (app.yxi.agent.Account.signedIn) app.yxi.agent.Account.refresh(ctx) }

        // ── 「我」：头像 / 昵称 / 签名（学 QQ 和 Gemini 的个人页：账号那块单独一张卡，摆最上面）
        var editMe by remember { mutableStateOf(false) }
        if (editMe) MeDialog { editMe = false }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { editMe = true },
        ) {
            Row(
                Modifier.padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MeAvatar(52.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        Me.name(ctx).ifBlank {
                            if (app.yxi.agent.Account.signedIn) t("点这里起个名") else t("点这里登录")
                        },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    )
                    Text(
                        Me.sign(ctx).ifBlank { t("写句个性签名") },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    // UID：找客服、后台按它反查兑换记录都要（logto_yxi 2026-09-04）。
                    // ⚠️ 用 Logto 的 `sub`（12 位、不可变），**不另造一套**。长按复制。
                    app.yxi.agent.Account.me?.userId?.takeIf { it.isNotBlank() }?.let { uid ->
                        Text(
                            "UID $uid",
                            Modifier.padding(top = 3.dp).combinedClickable(
                                onClick = { editMe = true },
                                onLongClick = {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("uid", uid))
                                    android.widget.Toast.makeText(ctx, t("UID 复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
        // 会员那条单独一张（学 Gemini 的「升级到 AI Plus」）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { onMember() },
        ) {
            Row(
                Modifier.padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                YxiIcon(Ico.Crown, size = 22.dp, tint = androidx.compose.ui.graphics.Color(0xFFE8912D))
                Text(t("会员中心"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    // ⚠️ 登录了但资料还没回来 → 「已登录」，别写成「未登录」（#240）
                    if (!app.yxi.agent.Account.signedIn) t("未登录")
                    else app.yxi.agent.Account.me?.tier?.name ?: t("已登录"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                )
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        }

        // ── 钱包 ────────────────────────────────────────────────────
        // ⚠️ **余额不写 ¥0.00**。服务端现在根本没有这个字段，写个 0 就是在说
        //    「你的余额是零」——那是假的（design/STYLE.md「不骗人」）。没开通就说没开通。
        //    字段形状已经发给 cc-logto_yxi 对齐：balance 用**分**（整数）+ currency，别用浮点。
        var soon by remember { mutableStateOf<String?>(null) }
        soon?.let { what ->
            AlertDialog(
                onDismissRequest = { soon = null },
                title = { Text(what) },
                text = { Text(t("功能开发中，敬请期待。")) },
                confirmButton = { TextButton({ soon = null }) { Text(t("知道了")) } },
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { onWallet() },
        ) {
            Row(
                Modifier.padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val acc = app.yxi.agent.Account.me
                YxiIcon(Ico.Wallet, size = 22.dp, tint = androidx.compose.ui.graphics.Color(0xFF35B6A0))
                Column(Modifier.weight(1f)) {
                    Text(t("钱包"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // ⚠️ **余额可能是负的**（余额花光之后我们又撤销了一张余额券 —— 服务端
                        //    只允许 reason=refund 那条路扣成负数，用户主动消费仍然不许透支）。
                        //    欠着钱的人不该看到一句讲充值好处的话 —— 那会让他以为一切正常。
                        if ((acc?.balanceCents ?: 0L) < 0L) t("有一笔兑换被撤销了 · 下次充值先抵这笔")
                        else t("余额兑换码兑入 · 商城买会员码 · 到期自动续费"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
                // ⚠️ 服务端给的是 **balanceCents（分）**，只在**显示的这一刻**除 100 变成元。
                //    别在别处提前转成 Double —— 字段名里带单位就是为了防这个（logto_yxi 2026-09-04）。
                // ⚠️ 三态，别把后两个混成一个（#240 那个坑我在这儿又踩了一次）：
                //    没登录 → 「未登录」；登录了但资料还没回来 → 「读取中」；拿到了才显示金额。
                when {
                    !app.yxi.agent.Account.signedIn -> Text(
                        t("未登录"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                    acc == null -> Text(
                        t("读取中"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                    else -> Text(
                        app.yxi.agent.Account.yuan(acc.balanceCents),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        // 欠款用 error，不用 Amber —— Amber 在 STYLE.md §1.2 是「需要你动手」，
                        // 挪用会稀释那个色；这里要说的是「这个数不对劲」，那是 error 的活
                        color = if (acc.balanceCents < 0L) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        }

        // ── 趋势：卡上直接画一条最近半个月的小柱状图，点开是整页
        //    ⚠️ 拿不到（那台机器没装 ccusage）就**只显示标题不画图**，不画一条假曲线
        var spark by remember(ssh) { mutableStateOf<List<app.yxi.agent.Day>?>(null) }
        LaunchedEffect(ssh) {
            spark = ssh?.let { app.yxi.ssh.catching { app.yxi.agent.Usage.daily(it, 14) }.getOrNull() }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { onTrend() },
        ) {
            Row(
                Modifier.padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                YxiIcon(Ico.Bolt, size = 22.dp, tint = app.yxi.ui.theme.Copper)
                Column(Modifier.weight(1f)) {
                    Text(t("趋势"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        spark?.lastOrNull()?.let { t("今天 %s · $%.2f").format(it.tokenText, it.costUSD) }
                            ?: t("每天烧了多少 token、多少钱"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
                spark?.takeIf { it.size >= 2 }?.let {
                    TrendSpark(it, Modifier.width(90.dp).height(34.dp))
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        }

        // ── 四宫格（学 QQ / 米哈游那种个人页）：邮件 · 祈愿 · 活动 · 工单 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 老板 2026-09-05：**只要红点、不要数字**，点进去就消。
            // 未读数是服务端的（/api/me），「看过没」的水位在本机（[Badges]）——两者一比就是亮不亮。
            val unread = app.yxi.agent.Account.me?.unreadMail ?: 0
            LaunchedEffect(unread) { Badges.clamp(ctx, Badges.MAIL, unread) }
            GridEntry(Ico.Mail, t("邮件"), Color(0xFF4C8DF6), Modifier.weight(1f), dot = Badges.dot(ctx, Badges.MAIL, unread)) { onMail() }
            GridEntry(Ico.Wish, t("祈愿"), Color(0xFFB07AE8), Modifier.weight(1f)) { onWish() }
            GridEntry(Ico.Gift, t("活动中心"), Color(0xFFE8912D), Modifier.weight(1f)) { onActivity() }
            // 工单格同一套：有没看过的官方回复就亮（unreadTickets 由 /api/me 给）
            val unreadT = app.yxi.agent.Account.me?.unreadTickets ?: 0
            LaunchedEffect(unreadT) { Badges.clamp(ctx, Badges.TICKETS, unreadT) }
            GridEntry(Ico.Chat, t("工单"), Color(0xFF7A69E8), Modifier.weight(1f), dot = Badges.dot(ctx, Badges.TICKETS, unreadT)) { onTickets() }
        }

        Spacer(Modifier.height(4.dp))
        // ── 设置：「我的」最底下那一行（用户 2026-09-04）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp)).clickable { onPrefs() },
        ) {
            Row(
                Modifier.padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                YxiIcon(Ico.Gear, size = 22.dp, tint = MaterialTheme.colorScheme.outline)
                Text(t("设置"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        }

        } else {

        SectionLabel(t("版本"))

        // ── 版本 ───────────────────────────────────────────────────
        Card(t("版本"), Glyph.Info, subtitle = t("%s（versionCode %d）").format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        val now = System.currentTimeMillis()
                        // 超过 1.2 秒就重新数 —— 否则平时零星点几下也会攒够三下
                        taps = if (now - lastTap < 1200) taps + 1 else 1
                        lastTap = now
                        if (taps >= 3) { taps = 0; if (dev) dev = false.also { DevMode.setUnlocked(ctx, false) } else askPass = true }
                    },
                ) {
                    Text(t("版本 %s").format(BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // versionCode 才是更新比较用的那个数，写出来免得对不上号时抓瞎
                        "versionCode ${BuildConfig.VERSION_CODE}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim,
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            checking = true; result = null
                            val f = sftp ?: runCatching { ssh?.openSftp() }.getOrNull()
                            sftp = f
                            // ⚠️ 报的必须是**真正去连的地址**，不是用户起的名字。
                            // 这里原来打印 alias —— 用户名字栏填的是 IP、地址栏填的是别名「天亮」，
                            // 于是错误信息理直气壮地报了一个它压根没连过的 IP，
                            // 排查因此往端口/防火墙上跑偏了好几轮。见 TROUBLESHOOTING #71。
                            // ⚠️ 公网下载页优先（换个客户也能更新）；它说「有新版」就用它，
                            // 否则再问所连的服务器（防火墙后 / 没外网时的退路）。
                            val pub = runCatching { Update.publicVerbose(BuildConfig.VERSION_CODE) }
                                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
                            result = when {
                                pub is Update.Result.Newer -> pub
                                f == null -> pub ?: Update.Result.Failed(t("连不上 %s，没查成").format(host?.display))
                                else -> runCatching { Update.checkVerbose(f, BuildConfig.VERSION_CODE) }
                                    .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; Update.Result.Failed(t("查的时候出错：%s").format(it.message)) }
                                    .let { local -> if (local is Update.Result.Failed && pub != null) pub else local }
                            }
                            checking = false
                        }
                    },
                    // ⚠️ 不再要求「连上主机」——更新走公网，没连也能查
                    enabled = !checking,
                    shape = Pill, modifier = Modifier.height(42.dp),
                ) { Text(if (checking) t("查着…") else t("检查更新")) }
            }
            if (host == null) {
            }
            when (val r = result) {
                null -> Unit
                is Update.Result.UpToDate ->
                    Line(t("✓ 已是最新（服务器上就是 %s）").format(BuildConfig.VERSION_NAME), Teal)
                is Update.Result.Failed ->
                    // ⚠️ 明确说「没查到」，不能含糊成「已是最新」
                    Line(t("✗ 没查到：%s").format(r.why), MaterialTheme.colorScheme.error)
                is Update.Result.Newer -> {
                    Line(t("有新版本 %s · %s").format(r.update.versionName, r.update.sizeText), Copper)
                    UpdateBanner(ssh, r.update) { result = null }
                }
            }
        }

        // ── 公钥 ───────────────────────────────────────────────────
        SectionLabel(t("手机功能"))
        Card(t("这台手机的公钥"), Glyph.Key, subtitle = t("贴进目标机就能免密连")) {
            Hint2(t("贴进目标机的 ~/.ssh/authorized_keys 就能免密连。撤销 = 删掉那一行。"))
            Button({ showKey = true }, shape = Pill, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text(t("查看 / 复制 / 换一把"))
            }
        }

        // ── 后台放行 ────────────────────────────────────────────────
        Card(t("手机主动响"), Glyph.Bell, subtitle = watchSummary) {
            val battery = ignoringBattery(ctx)
            val notif = notificationsOn(ctx)
            // ⚠️ **这一行是后加的，因为原来那两行在骗人。**
            // 用户把通知权限和后台都放行了，看到两个绿勾 + 「缺任何一项都不会响」，
            // 合理地以为搞定了 —— 但真正决定响不响的是**每台主机的铃铛**（`watch`），
            // 它默认是关的，而且**这一页上根本没提过它**。
            // 前台服务只在「有任意一台开了铃铛」时才启动，一台都没开 = 连那条常驻通知都没有，
            // 现象就是「什么都不显示」。见 TROUBLESHOOTING #91。
            val watching = store.hosts.collectAsState().value.count { it.watch }
            StatusRow(t("盯着的机器"), watching > 0, ok = t("%d 台").format(watching)) {
                // 没法直接跳到主机页（这里拿不到导航），说清楚去哪点就行
            }
            if (watching == 0) Hint2(
                t("⚠️ 一台都没开 —— 前面两项放行了也不会响。") +
                    t("去「主机」那一栏，点每台机器右边的铃铛把它打开。")
            )
            // ⚠️ 这一条也得摆出来。它同样能让手机「明明设置好了却不响」——
            // 开着 + 置顶了几个 = 其余会话一律不响。不写在这页上，
            // 下次又是「我明明放行了」的排查（#91）。
            var onlyPinned by remember { mutableStateOf(Pinned.onlyPinned(ctx)) }
            val pinCount = store.hosts.collectAsState().value
                .sumOf { Pinned.get(ctx, it.id).size }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("只通知置顶的会话"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(onlyPinned, { onlyPinned = it; Pinned.setOnlyPinned(ctx, it) })
            }
            Hint2(
                when {
                    !onlyPinned -> t("现在是**每个**会话都会响。会话多的时候通知栏会很吵。")
                    pinCount == 0 -> t("⚠️ 一条都没置顶 —— 现在等于全部通知。") +
                        t("去会话列表点卡片右上角的图钉，置顶几个你真正在等的。")
                    else -> t("只有置顶的 %d 个会响，其余的静悄悄干活。").format(pinCount) +
                        t("置顶在会话列表里点卡片右上角的图钉。")
                }
            )
            StatusRow(t("通知权限"), notif) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            // 「像微信那样弹在屏幕顶上」靠的是「Claude 找你」这条频道的重要级（HIGH）。
            // 用户在系统里把它降过级、或者荣耀把横幅关了，就只进列表不弹 —— 这一行把它摆出来。
            val banner = app.yxi.watch.EventService.bannerOn(ctx)
            StatusRow(t("弹窗提醒（横幅）"), banner, ok = t("会弹")) { app.yxi.watch.EventService.openBannerSettings(ctx) }
            if (!banner) Hint2(t("这条频道被降级了，通知只会进列表不弹出来。点「去开启」，把重要程度调回「高」并打开横幅。"))
            // 与其解释「会不会弹」，不如当场弹一条给用户看
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("发一条试试"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Surface(
                    color = CopperContainer, shape = Pill,
                    modifier = Modifier.clip(Pill).clickable { app.yxi.watch.EventService.testNotify(ctx) },
                ) {
                    Text(t("弹一条"), Modifier.padding(12.dp, 5.dp), style = MaterialTheme.typography.labelSmall, color = OnCopperContainer)
                }
            }
            Hint2(t("正常情况下它会像微信来消息一样弹在屏幕顶上、震两下；锁屏时会点亮屏幕。没弹出来就是上面哪一项没放行。"))
            StatusRow(t("后台不受限制"), battery) { askIgnoreBattery(ctx) }
            if (!battery) {
                // ⚠️ 荣耀/华为的「电池优化白名单」只是**其中一道**。真正掐后台的是
                // 「应用启动管理」，那个 Android 没有标准 intent，只能告诉用户路径。
                // 不写出来的话，用户按上面那个开关开完了、以为搞定了，实际还是不响。
                Hint2(t("荣耀/华为还有一道单独的开关：设置 → 应用和服务 → 应用启动管理 → ") +
                    t("找到 Yxi → 关掉「自动管理」，然后三项（自启动/关联启动/后台活动）全打开。"))
            }
            if (!battery || !notif) {
                // ⚠️ 这两项缺一个，「主动响」就是**静默失效** —— 不会报错，只是不响了
                Hint2(
                    t("缺任何一项，Claude 需要你时手机都不会响，而且不会有任何提示。") +
                        t("荣耀 / 华为 的后台管控尤其狠。")
                )
            }
        }

        // 语音识别 —— **在这台手机上算**，不联网、不依赖服务器、不依赖 Google 服务。
        // ⚠️ 模型不打进 APK（解开 229MB），要用才下。
        run {
            val asr = app.yxi.agent.AsrModel
            LaunchedEffect(Unit) { asr.refresh(ctx) }
            val supported = app.yxi.agent.OnDeviceAsr.supported
            LaunchedEffect(Unit) { asr.loadEngine(ctx) }
            Card(
                t("语音识别"), Glyph.Mic,
                subtitle = asr.engine.label + " · " + when {
                    !supported -> t("这台设备不支持（只打包了 arm64）")
                    asr.installed -> t("已就绪 · 在手机上识别，离线也能用")
                    asr.progress >= 0f -> t("下载中 %d%%").format((asr.progress * 100).toInt())
                    else -> t("没下模型 · 现在用的是系统识别")
                },
            ) {
                // 用哪套 —— 自动挡挑不出「用户想要哪套」，给个明确的开关（用户要的）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.yxi.agent.AsrModel.Engine.entries.forEach { e ->
                        val on = asr.engine == e
                        Surface(
                            color = if (on) CopperContainer else SurfaceContainerHigh,
                            shape = Pill,
                            modifier = Modifier.clip(Pill).clickable { asr.setEngine(ctx, e) },
                        ) {
                            Text(
                                e.label,
                                Modifier.padding(13.dp, 9.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else Muted,
                            )
                        }
                    }
                }
                Hint2(
                    when (asr.engine) {
                        app.yxi.agent.AsrModel.Engine.Auto ->
                            t("自动：手机上下了模型就用手机（离线、最快），没下就用服务器上的 `yxi-asr`，都没有才退回系统识别。")
                        app.yxi.agent.AsrModel.Engine.Device ->
                            t("手机上算：不联网、不经过任何服务器。要先下模型（约 %d MB）。").format(app.yxi.agent.AsrModel.SIZE_MB)
                        app.yxi.agent.AsrModel.Engine.Server ->
                            t("服务器上算：同一个模型跑在你自己的机器上，最准。要那台机器装过 `yxi-asr`（装机脚本里有）。")
                        app.yxi.agent.AsrModel.Engine.System ->
                            t("系统识别：不占空间，但依赖 Google 服务 —— 关了 GMS 的手机上它是不存在的。")
                    }
                )
                Hint2(
                    t("模型是开源的 SenseVoice，中英粤日都认、自带标点。")
                )
                if (!supported) {
                    Hint2(t("⚠️ 只打包了 arm64 的原生库（模拟器和很老的机器用不了），这台机器会继续用系统识别。"))
                } else if (asr.installed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = SurfaceContainerHigh, shape = Pill,
                            modifier = Modifier.clip(Pill).clickable { asr.remove(ctx) },
                        ) {
                            Text(
                                t("删掉模型（腾出约 %d MB）").format(230),
                                Modifier.padding(16.dp, 9.dp),
                                style = MaterialTheme.typography.labelLarge, color = Muted,
                            )
                        }
                    }
                } else {
                    MorphButton(
                        phase = if (asr.progress >= 0f) MorphPhase.Run
                        else if (asr.error != null) MorphPhase.Fail else MorphPhase.Idle,
                        label = t("下载模型（%d MB）").format(app.yxi.agent.AsrModel.SIZE_MB),
                        modifier = Modifier.fillMaxWidth(), height = 46.dp,
                        msg = asr.error.orEmpty(),
                        progress = asr.progress,
                    ) { asr.start(ctx) }
                    Hint2(t("⚠️ 下载走流量，建议连 Wi-Fi。下好之后就一直在手机上，换服务器也不用重下。"))
                }
            }
        }

        SectionLabel(t("界面"))
        Card(t("界面风格"), Glyph.Palette, subtitle = Skin.style.label) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Skin.Style.entries.forEach { st ->
                    val on = Skin.style == st
                    Surface(
                        color = if (on) CopperContainer else SurfaceContainerHigh,
                        shape = Pill,
                        modifier = Modifier.clip(Pill).clickable { Skin.set(ctx, st) },
                    ) {
                        Text(
                            st.label,
                            Modifier.padding(16.dp, 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else Muted,
                        )
                    }
                }
            }
            Hint2(t("⚠️ 终端永远是深底 —— 彩色输出在浅底上读不了。所以浅色风格下切到终端会亮暗跳一下。"))
        }

        // ⚠️ 这一栏**不翻译**：正在看不懂当前语言的人，得能认出另一个选项。
        // 「简体中文 / English」两个名字都用它们自己的语言写，谁都找得到自己那个。
        Card(t("语言"), Glyph.Globe, subtitle = I18n.lang.label) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                I18n.Lang.entries.forEach { l ->
                    val on = I18n.lang == l
                    Surface(
                        color = if (on) CopperContainer else SurfaceContainerHigh,
                        shape = Pill,
                        modifier = Modifier.clip(Pill).clickable { I18n.set(ctx, l) },
                    ) {
                        Text(
                            l.label,
                            Modifier.padding(16.dp, 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onTertiaryContainer else Muted,
                        )
                    }
                }
            }
        }

        // 时区：全 App 的绝对时间都从 Tz 走，这里一改处处跟着换（相对时间「几分钟前」不受影响）
        Card(t("时区"), Glyph.Globe, subtitle = app.yxi.agent.Tz.zone.label) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                app.yxi.agent.Tz.Zone.entries.forEach { z ->
                    val on = app.yxi.agent.Tz.zone == z
                    Surface(
                        color = if (on) CopperContainer else SurfaceContainerHigh,
                        shape = Pill,
                        modifier = Modifier.clip(Pill).clickable { app.yxi.agent.Tz.set(ctx, z) },
                    ) {
                        Text(
                            z.label,
                            Modifier.padding(16.dp, 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onTertiaryContainer else Muted,
                        )
                    }
                }
            }
            Hint2(t("额度恢复时间、邮件、订单、实验室、工单这些绝对时间都按这个时区显示；「几分钟前」那种不受影响。"))
        }

        if (dev) DevCard(ctx, host, store, keys, connectError)

        SectionLabel(t("关于与帮助"))
        Card(t("关于"), Glyph.Info, subtitle = t("Yxi —— 手机上的 Claude Code 指挥台")) {
            Hint2(
                t("Yxi —— 手机上的 Claude Code 指挥台。\n") +
                    t("全部走 SSH：不开新端口、不要证书、不经过任何第三方服务器。\n") +
                    t("服务器上唯一需要装的是 yxi-hook（就为了让手机能主动响）。")
            )
        }
        }   // if (mine) … else …
    }

    if (showKey) PublicKeySheetPublic(keys) { showKey = false }

    if (askPass) {
        var input by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askPass = false },
            title = { Text(t("开发者模式")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        input, { input = it; wrong = false },
                        singleLine = true,
                        label = { Text(t("口令")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    if (wrong) Line(t("口令不对"), MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton({
                    if (DevMode.check(input)) {
                        DevMode.setUnlocked(ctx, true); dev = true; askPass = false
                    } else wrong = true
                }) { Text(t("进")) }
            },
            dismissButton = { TextButton({ askPass = false }) { Text(t("算了")) } },
        )
    }
}

/**
 * 开发者卡片 —— **它存在的意义是让用户能把「手机那头到底怎么了」一键给我。**
 * 我在服务器上看不到手机的任何东西：包没飞到就等于什么都没发生。
 */
@Composable
private fun DevCard(ctx: Context, host: Host?, store: HostStore, keys: KeyManager, uiError: String?) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    Card(t("开发者"), Glyph.Wrench, subtitle = t("诊断连接、复制报告")) {
        Hint2(
            t("诊断会把整条连接路径一步步走一遍：解析地址 → 连 TCP → SSH 招呼 → 认证，") +
                t("再挨个试同一个 IP 上的几个端口。里面不含密码和私钥，可以直接贴出来。\n") +
                t("测的是 ") + (host?.let { "${it.username}@${it.connectHost}:${it.port}" } ?: t("（还没有主机）")) +
                t(" —— 要换一台就去「会话」页顶部切换。")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true; copied = false
                        report = DevMode.diagnose(ctx, host, store, keys, uiError)
                        running = false
                    }
                },
                enabled = !running, shape = Pill, modifier = Modifier.weight(1f).height(44.dp),
            ) { Text(if (running) t("测着…（约 30 秒）") else t("跑一次诊断")) }

            if (report.isNotEmpty()) {
                Button(
                    onClick = { DevMode.copy(ctx, report); copied = true },
                    shape = Pill, modifier = Modifier.height(44.dp),
                ) { Text(if (copied) t("已复制") else t("复制")) }
            }
        }
        // 逃生口（用户 2026-09-04：输入框里的字删不掉了，要一个能处理异常的按钮）：清掉所有会话的草稿。
        // 输入框的文字就是草稿，清了再进对话就是空的。不动服务器、不动会话。
        var cleared by remember { mutableStateOf<Int?>(null) }
        Button(
            onClick = { cleared = Drafts.clearAll(ctx) },
            shape = Pill, modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
        ) { Text(cleared?.let { t("清掉了 %d 条草稿，重进对话就是空的").format(it) } ?: t("清空所有输入框草稿（输入框卡住时用）")) }
        if (report.isNotEmpty()) {
            Surface(color = SurfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                // 诊断文本很长，给它自己的滚动区，别把整页撑成一条
                Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(
                        report,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Hint2(t("再连点三下版本号 = 退出开发者模式。"))
    }
}

/**
 * 设置里的一节。**照参考款 的样子做**：左边一个线性引导图标，右边一段内容，
 * 标题更粗、留白更松。
 *
 * ⚠️ 卡片底色用得很淡（`SurfaceContainerLow`）—— 参考款靠留白和图标分节，
 * 不靠重描边。深色主题下它就是比页面底稍亮一点的一块，浅色主题下是白卡。
 */
@Composable
private fun Card(
    title: String,
    icon: String? = null,
    /** 收起时标题下面那行灰字，一眼看出这节是干嘛的。可空。 */
    subtitle: String? = null,
    /** 默认收起。个别想一进来就摊开的（比如版本）传 true。 */
    startExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // ⚠️ **默认收起，点一下才展开。** 用户要的是「一条条排列，点对应的才展开」——
    // 全摊开的话一屏放不下两节，得一直滚。收起后一屏能看全，想改哪项点哪项。
    var open by rememberSaveable(title) { mutableStateOf(startExpanded) }
    Surface(
        color = SurfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Column {
            // 标题行：整行可点，右边一个会转的箭头
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(18.dp, 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) GlyphIcon(icon, Muted, 22.dp) else Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                    // 收起时才显示副标题 —— 展开后内容自己会说话，副标题就多余了
                    if (subtitle != null && !open) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 1)
                    }
                }
                // 箭头：收起时朝下 ▾，展开时朝上 ▴。⚠️ 用几何符号不用 emoji（Glyph 的规矩）
                Text(
                    if (open) "▴" else "▾",
                    style = MaterialTheme.typography.titleSmall, color = Muted,
                )
            }
            // 展开区：带个淡入淡出，别硬生生蹦出来
            androidx.compose.animation.AnimatedVisibility(visible = open) {
                Column(
                    Modifier.padding(start = 54.dp, end = 18.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
private fun Hint2(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = Dim)

@Composable
private fun StatusRow(label: String, on: Boolean, ok: String = t("已放行"), onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !on, onClick = onFix),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Surface(color = if (on) SurfaceContainerHigh else CopperContainer, shape = Pill) {
            Text(
                if (on) ok else t("去开启"),
                Modifier.padding(12.dp, 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (on) Teal else OnCopperContainer,
            )
        }
    }
}

/**
 * 请求「后台不受限制」。
 *
 * ⚠️ **不能用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`** —— 那个打开的是
 * 「**已经**被放行的应用」列表，而我们恰恰还没被放行，所以用户点进去
 * **根本找不到这个 App**，看起来像是跳错了地方。（用户原话：「没有看见我们的 app」）
 *
 * 要的是带 `package:` 的 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：
 * 它直接对本应用弹一个授权框。厂商 ROM 上可能被拿掉，所以留两级退路：
 * 本应用的设置页 → 全局电池优化列表。
 */
private fun askIgnoreBattery(ctx: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:" + ctx.packageName)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + ctx.packageName)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (i in tries) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun ignoringBattery(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(true)

private fun notificationsOn(ctx: Context): Boolean = runCatching {
    // ⚠️ 只查 POST_NOTIFICATIONS 不够：用户在系统里把 App 的通知整个关掉时权限仍然是「已授予」。
    // `areNotificationsEnabled` 才是「现在到底发不发得出去」。
    val enabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    if (Build.VERSION.SDK_INT < 33) enabled
    else enabled && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}.getOrDefault(true)

/**
 * 工单中心**单独一页** —— 侧边栏里那一项（用户 2026-09-04：侧边栏里少了这个）。
 *
 * 它是这个 App 里**唯一一条你和开发之间的通道**。2026-09-05 老板拍板从「SSH 落到用户自己服务器的
 * `~/.yxi/tickets.jsonl`」搬进会员服务（hk13）：以前只有用户本人看得见，我们收不到、也回不了，等于没提。
 * 现在：提单选分类 → 我们在后台回复 → 有回复没看时「我的」里工单格亮红点（水位见 [Badges]）→ 用户还能追问。
 * ⚠️ 这一步在语义上是**把用户写的内容送出他的机器** —— 所以界面上明写只带文字 + 版本号 + 机型，
 *    主机 / 密钥 / 会话内容一律不上传（[app.yxi.agent.Tickets] 的隐私红线）。
 */
@Composable
fun TicketsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<app.yxi.agent.Tickets.Ticket>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loading = true; failed = false
        val r = app.yxi.agent.Tickets.load(ctx)
        if (r == null) failed = true else { items.clear(); items.addAll(r.first); cursor = r.second }
        loading = false
    }
    // 「点进去就消」：进页、以及在这页里每看掉一条回复，都把红点水位抬到当前未读数（同邮件页，见 [Badges]）
    val unreadNow = app.yxi.agent.Account.me?.unreadTickets
    LaunchedEffect(unreadNow) { unreadNow?.let { Badges.mark(ctx, Badges.TICKETS, it) } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("工单中心"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )
        NewTicketCard { reload++ }
        when {
            loading && items.isEmpty() -> TicketHint(t("正在取…"))
            // ⚠️「拿不到」和「没有」分开说（STYLE.md：不骗人）
            failed && items.isEmpty() -> TicketHint(t("取不到 —— 网络不通，或者登录过期了。"))
            items.isEmpty() -> TicketHint(t("还没提过工单。哪里不好用，上面写一条。"))
            else -> {
                SectionLabel(t("我的工单"))
                items.forEach { tk ->
                    key(tk.id) {
                        TicketRow(
                            tk, expanded = open == tk.id,
                            onToggle = {
                                open = if (open == tk.id) null else tk.id
                                // 点开就算看过回复了：本地先翻掉红点，服务端幂等标记
                                if (open == tk.id && tk.unread) scope.launch {
                                    if (app.yxi.agent.Tickets.markRead(ctx, tk.id)) {
                                        // ⚠️ 改列表里**此刻**那一条，别拿闭包里捕获的 tk 写回去 ——
                                        //    markRead 在飞的时候列表可能已经 reload 过，旧 tk 会把新回复盖掉
                                        val i = items.indexOfFirst { it.id == tk.id }
                                        if (i >= 0) items[i] = items[i].copy(unread = false)
                                    }
                                }
                            },
                            onChanged = { reload++ },
                        )
                    }
                }
                // 还有更旧的：传上一页最后一条的 id
                cursor?.let { cur ->
                    Text(
                        t("看更早的"),
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                app.yxi.agent.Tickets.load(ctx, before = cur)?.let { items.addAll(it.first); cursor = it.second }
                            }
                        }.padding(16.dp),
                        style = MaterialTheme.typography.labelLarge, color = Muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketHint(text: String) {
    Surface(
        color = SurfaceContainerLow, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** 提一条：分类（必选）+ 正文。提交成功清空、通知上层刷列表。 */
@Composable
private fun NewTicketCard(onSubmitted: () -> Unit) {
    val ctx = LocalContext.current
    var cat by remember { mutableStateOf<app.yxi.agent.Tickets.Category?>(null) }
    var text by remember { mutableStateOf("") }
    Card(t("提一条"), Glyph.Wrench, subtitle = t("哪里不好用，选个分类写下来"), startExpanded = true) {
        // 分类是老板定的四个，必须选一个 —— 后台按它分派，不选就全成了「其他」大杂烩
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            app.yxi.agent.Tickets.Category.entries.forEach { c -> ChoiceChip(c.label, cat == c) { cat = c } }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            text, { text = it },
            placeholder = { Text(t("比如：点了发送切出去，消息没发出去")) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Spacer(Modifier.height(8.dp))
        // ⚠️ 这句是承诺不是说明：工单只带这三样，别的什么都不传（agent/Tickets.kt 的隐私红线）
        Hint2(t("只会带上你写的文字、App 版本号和机型。主机、密钥、会话内容都不会上传。"))
        Spacer(Modifier.height(10.dp))
        SubmitButton(
            label = t("提交"),
            modifier = Modifier.fillMaxWidth(), height = 46.dp,
            successLabel = t("已提交"),
        ) {
            val body = text.trim()
            val c = cat
            when {
                c == null -> Result.failure(RuntimeException(t("先选一个分类")))
                body.isBlank() -> Result.failure(RuntimeException(t("先写点什么")))
                else -> {
                    val err = app.yxi.agent.Tickets.add(
                        ctx, c, body,
                        version = "%s(%d)".format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        device = Build.MODEL + " / Android " + Build.VERSION.RELEASE,
                    )
                    if (err == null) { text = ""; cat = null; onSubmitted(); Result.success(t("已提交")) }
                    else Result.failure(RuntimeException(err))
                }
            }
        }
    }
}

/** 单选筹片（同 MailScreen 的 MailChip）：选中 primaryContainer，没选 surfaceContainerHigh */
@Composable
private fun ChoiceChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(Pill)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(14.dp, 7.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 一条工单：分类 · 状态 · 时间 / 正文 / 展开后是回复串 + 追问框 */
@Composable
private fun TicketRow(
    tk: app.yxi.agent.Tickets.Ticket,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** 追问发出去了 —— 上层重新拉列表 */
    onChanged: () -> Unit,
) {
    Surface(
        color = SurfaceContainerLow, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(20.dp)).clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 有没看的官方回复：左边一颗红点（跟「我的」宫格上那颗同色）
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (tk.unread) MaterialTheme.colorScheme.error else Color.Transparent),
                )
                Text(tk.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                val (st, stColor) = when (tk.status) {
                    "closed" -> t("已关闭") to Muted
                    "replied" -> t("已回复") to Copper
                    else -> t("待处理") to Amber
                }
                Text(st, style = MaterialTheme.typography.labelSmall, color = stColor)
                // ⚠️ 时间一律走 Tz（用户切了时区这里要跟着变），别自己格式化
                Text(
                    app.yxi.agent.Tz.dateTime(tk.createdAt), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Text(
                tk.text, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (expanded) {
                tk.replies.forEach { r -> ReplyBubble(r) }
                // 关闭的也能追问 —— 服务端把它当重开（契约 support-tickets.md），所以框照留，只提前说一句
                if (tk.status == "closed") Hint2(t("这条已关闭 —— 再补一句会重新打开。"))
                FollowUp(tk.id, onChanged)
            }
        }
    }
}

@Composable
private fun ReplyBubble(r: app.yxi.agent.Tickets.Reply) {
    Surface(
        color = if (r.official) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp, 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (r.official) t("Yxi 官方") else t("我"), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = if (r.official) Copper else Muted,
                )
                Text(app.yxi.agent.Tz.dateTime(r.at), style = MaterialTheme.typography.labelSmall, color = Dim)
            }
            Text(r.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 追问：一行输入 + 发送。发出去了上层刷列表；失败不清空、把原因说出来。 */
@Composable
private fun FollowUp(id: String, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(id) { mutableStateOf("") }
    var busy by remember(id) { mutableStateOf(false) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f),
            placeholder = { Text(t("再补一句…")) }, maxLines = 4, shape = MaterialTheme.shapes.medium,
        )
        val can = !busy && text.isNotBlank()
        Text(
            if (busy) t("发送中…") else t("发送"),
            Modifier.clip(Pill)
                .background(if (can) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = can) {
                    busy = true; err = null
                    scope.launch {
                        val e = app.yxi.agent.Tickets.reply(ctx, id, text.trim())
                        busy = false
                        if (e == null) { text = ""; onSent() } else err = e
                    }
                }
                .padding(16.dp, 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (can) MaterialTheme.colorScheme.onPrimary else Muted,
        )
    }
    err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

/** QQ 那种分组小标题：卡片上面一行小灰字，把一堆设置分出层次 */
/** 「我的」上那一格：圆角方块里一枚描边图标，底下一行字。四格一排。 */
@Composable
private fun GridEntry(
    ico: Ico,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    /** 图标右上角那颗小红点（老板 2026-09-05：只要点，不要数字） */
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                YxiIcon(ico, size = 24.dp, tint = tint)
                // 9dp 红点，外面一圈底色描边 —— 点压在描边图标的线上时靠这圈把它和线分开
                if (dot) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-3).dp).size(9.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.error)
                        .border(1.5.dp, bg, CircleShape),
                )
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(24.dp, 10.dp, 24.dp, 0.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}
