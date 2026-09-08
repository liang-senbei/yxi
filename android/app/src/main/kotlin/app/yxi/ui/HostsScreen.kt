package app.yxi.ui

import app.yxi.agent.AccountApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import app.yxi.ui.theme.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.Vault
import java.util.UUID

private val Pill = RoundedCornerShape(100.dp)

/** 主机列表。**不是预置列表** —— 随时能加「以后才有的」服务器（PRD §2.4）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    store: HostStore,
    keys: KeyManager,
    /** 此刻在用的那台（看板/对话连的就是它）—— 多台时要一眼看出来 */
    current: String? = null,
    onOpen: (Host) -> Unit,
    /**
     * 下拉刷新：重读 hosts.json + 把共享的两条连接断掉重连（老板 2026-09-06：「改了密钥进会话还是认证失败，
     * 手往下拉一下就该同步」）。凭据改了本来就会自动重连（[Host.connKey]），这是给「不放心、想手动来一下」的。
     */
    onRefresh: (() -> Unit)? = null,
    /** 点内网设备 = 进从机页（主机是谁 + 那台设备）。 */
    onOpenSlave: (Host, app.yxi.agent.TailscaleStatus.Device) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var batteryHint by remember { mutableStateOf(false) }
    val hosts by store.hosts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    /** 免费档想加第 3 台时的提示 */
    var overLimit by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf<Host?>(null) }
    var editing by remember { mutableStateOf<Host?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("主机"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconTextButton(t("公钥")) { showKey = true }
            Spacer(Modifier.width(8.dp))
            IconTextButton("＋", primary = true) {
                // ⚠️ **免费档最多绑 2 台**（老板定的会员权益）。这是**客户端软限制** ——
                //    主机和密钥按设计只存在这台手机上，服务端不知道也不该知道你绑了几台
                //    （隐私政策白纸黑字写着配置只存本地）。所以改 APK 能绕过，这是已知且接受的。
                //    绝不为了堵它把主机列表传服务端。
                val tier = if (app.yxi.agent.Account.signedIn) app.yxi.agent.Account.me?.tier else null
                if (tier == null || tier == app.yxi.agent.AccountApi.Tier.Free) {
                    if (hosts.size >= 2) { overLimit = true; return@IconTextButton }
                }
                adding = true
            }
        }

        if (hosts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    t("还没有主机\n点右上角 ＋ 加一台"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    if (onRefresh == null || refreshing) return@PullToRefreshBox
                    // 重连是异步的、这里等不到结果；转一小会儿让人知道「动了」，卡片上的状态随后自己变
                    scope.launch { refreshing = true; onRefresh(); delay(900); refreshing = false }
                },
                modifier = Modifier.weight(1f),
            ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(hosts, key = { it.id }) { h ->
                    HostRow(
                        h,
                        current = h.id == current,
                        ctx = ctx,
                        store = store,
                        keys = keys,
                        onClick = { onOpen(h) },
                        onEdit = { editing = h },
                        onOpenSlave = { d -> onOpenSlave(h, d) },
                        onWatch = {
                            val on = !h.watch
                            store.upsert(h.copy(watch = on))
                            app.yxi.watch.EventService.sync(ctx, store.hosts.value.any { it.watch })
                            // 第一次打开铃铛时提醒放行后台 —— 见 BatteryHint 的注释
                            if (on && !ignoringBattery(ctx)) batteryHint = true
                        },
                    )
                }
            }
            }   // PullToRefreshBox
        }
    }

    if (overLimit) androidx.compose.material3.AlertDialog(
        onDismissRequest = { overLimit = false },
        title = { Text(t("免费档最多绑 2 台")) },
        text = {
            Text(
                t("再加就要 Pro 或 Ultra —— 它们不限台数。\n（主机和密钥一直只存在这台手机上，这条限制也只在手机上判。）"),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { androidx.compose.material3.TextButton({ overLimit = false }) { Text(t("知道了")) } },
    )
    if (adding) {
        AddHostSheet(store, keys, ctx, onDone = { adding = false })
    }
    editing?.let { h ->
        AddHostSheet(
            store, keys, ctx, editing = h,
            onInstallKey = { editing = null; installTarget = h },
            onDone = { editing = null },
        )
    }
    if (showKey) {
        PublicKeySheetPublic(keys) { showKey = false }
    }
    if (batteryHint) BatteryHint(ctx) { batteryHint = false }
    installTarget?.let { h ->
        // 连接一律走共用的 connector —— 这里曾经自己 new 了个 SshSession 且 prompt 传 null，
        // 结果「给没连过的新主机装公钥」永远失败（见 TROUBLESHOOTING #24 / #25）
        InstallKeySheet(rememberSshConnector(store, keys, h), keys, store, h) { installTarget = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(
    h: Host,
    /** 是不是此刻在用的那台 */
    current: Boolean,
    ctx: android.content.Context,
    store: HostStore,
    keys: KeyManager,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onWatch: () -> Unit,
    /** 点内网设备 = 把它当从机点进去看会话（老板 2026-09-07） */
    onOpenSlave: (app.yxi.agent.TailscaleStatus.Device) -> Unit = {},
) {
    // ⚠️ **长按 = 展开额度**（用户要的）。原来长按是「改主机」，挪进展开区里那个按钮。
    var expanded by remember(h.id) { mutableStateOf(false) }
    /// 长按一次 +1 —— [HostQuota] 靠它知道「该重查了」
    var expandAt by remember(h.id) { mutableStateOf(0) }
    Surface(
        // ⚠️ **在用的那台要一眼认出来**（用户 2026-09-04：多台主机时当前在用的用不同颜色显示）。
        //    ⚠️ 第一版是「强调色底 + 左边一条 [hostColor] 色条」，用户看了说
        //    「竖线很丑，在用的主机用跟对话输入框一样的那套颜色就行」——
        //    所以色条去掉了，底色改成**跟输入框同一条流动渐变**（[glowBrush]，见 design/STYLE.md §2.2）。
        //    好处不只是好看：那股「气」在这个 App 里始终代表**此刻活着的那一个**，语义是一致的。
        color = if (current) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).combinedClickable(
            onClick = onClick,
            // ⚠️ 每次长按都把 `expandAt` +1 传给 [HostQuota] —— 它据此重查。
            // 不能只靠「展开时子树被重建」：AnimatedVisibility 什么时候真销毁子树
            // 是它的实现细节，赌它等于赌「长按了却没刷新」。
            onLongClick = { expanded = !expanded; if (expanded) expandAt++ },
        ),
    ) {
        Column(
            Modifier
                .then(
                    // 在用的那台：铺一层跟输入框同源的流动渐变，其余的什么都不画
                    if (current) Modifier.background(glowBrush(busy = false, waiting = false)) else Modifier,
                )
                .padding(18.dp, 14.dp, 16.dp, 14.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(h.alias, style = MaterialTheme.typography.titleMedium)
                    if (current) Text(
                        t("在用"),
                        Modifier.clip(Pill).background(MaterialTheme.colorScheme.tertiaryContainer).padding(8.dp, 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    // 走 Tailscale 内网时标出来 —— 地址栏显示的是 100.x，不标用户会以为公网 IP 被改了
                    if (h.viaTailscale) Text(
                        t("内网"),
                        Modifier.clip(Pill).background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp, 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(3.dp))
                // ⚠️ 地址栏里混进中文/全角字符是**最贵的一种错**：连不上，
                // 而错误信息在别处，用户看着列表觉得一切正常。所以在列表里就标出来 ——
                // 这台主机的地址是「天亮」（一个 SSH 别名），在列表里躺了好几天没人发现。
                val bad = app.yxi.ssh.HostInput.suspiciousChar(h.connectHost)
                Text(
                    h.display,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (bad != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                )
                if (bad != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t("⚠️ 地址里有 %s —— 连不上。长按改。").format(bad),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 铃铛：让手机为这台机器主动响。⚠️ 需要那台机器上装了 server/install.sh
            Surface(
                color = if (h.watch) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Pill,
                modifier = Modifier.padding(end = 8.dp).clip(Pill).clickable(onClick = onWatch),
            ) {
                // ⚠️ 开/关不能只靠换图形，**颜色也要变** —— 铃铛和静音铃铛在 18dp
                // 下轮廓很像，光看形状容易看错。而这两个状态后果差很远：
                // 关着 = Claude 需要你时手机不会响，且没有任何提示
                Box(Modifier.padding(9.dp, 5.dp)) {
                    GlyphIcon(
                        if (h.watch) Glyph.Bell else Glyph.BellOff,
                        if (h.watch) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                        18.dp,
                    )
                }
            }
            Surface(
                color = if (h.useKey) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Pill,
            ) {
                Text(
                    if (h.useKey) t("密钥") else t("密码"),
                    Modifier.padding(11.dp, 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (h.useKey) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
        // ⚠️ **默认什么都不画**（用户要的）。长按展开才显示 5h / 7d 两档额度。
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            HostQuota(h, ctx, store, keys, onEdit, expandAt, onOpenSlave)
        }
        }
    }
}

/**
 * 长按主机后展开的那块：5 小时 / 本周两档额度 + 「改主机」。
 *
 * ⚠️ **额度是现连现查的**：主机页平时不为每台机器保一条连接（那太重）。
 * 展开的这一下才连一次、借个空闲会话跑 `/usage`。先把上次缓存的画出来（如果有），
 * 再在后台刷新 —— 不让人对着空白等。查不到就说清楚原因，别干等。
 */
@Composable
private fun HostQuota(
    h: Host, ctx: android.content.Context, store: HostStore, keys: KeyManager,
    onEdit: () -> Unit,
    /** 长按一次变一次 —— 变了就重查 */
    refreshAt: Int,
    /** 点内网设备 = 把它当从机点进去看会话 */
    onOpenSlave: (app.yxi.agent.TailscaleStatus.Device) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val connect = rememberSshConnector(store, keys, h, aliveIntervalMs = 15_000)
    var q by remember(h.id) { mutableStateOf(QuotaCache.get(ctx, h.id)?.first) }
    var busy by remember(h.id) { mutableStateOf(false) }
    var note by remember(h.id) { mutableStateOf<String?>(null) }
    /// 这份数字是什么时候取的。⚠️ **必须显示** —— 缓存值和刚取的值长得一模一样，
    /// 不写时间的话用户没法判断「到底刷新了没有」（他的原话：不够同步）。
    var fetchedAt by remember(h.id) {
        // ⚠️ `QuotaCache.get` 的第二个值是**距今多少分钟**，不是时间戳 —— 换算回来
        val ageMin = QuotaCache.get(ctx, h.id)?.second
        mutableStateOf(if (ageMin == null) 0L else System.currentTimeMillis() / 1000 - ageMin * 60)
    }

    /**
     * 服务器体检。**跟额度分开跑，而且先跑。**
     *
     * ⚠️ 额度要跑 `claude -p '/usage'`（几秒到十几秒，还要连一次 API），
     * 而体检只读 `/proc`、一个进程都不 fork ——
     * **机器越卡，越是只有体检出得来**，正好是最需要它的时候。
     * 两件事绑在一起的话，体检会被额度拖死。
     */
    var health by remember(h.id) { mutableStateOf<app.yxi.agent.Health.Report?>(null) }
    var healthBusy by remember(h.id) { mutableStateOf(false) }
    var healthNote by remember(h.id) { mutableStateOf<String?>(null) }
    /** 一键修复扫出来的东西。null = 还没扫；空表 = 没什么可收的 */
    var junk by remember(h.id) { mutableStateOf<List<app.yxi.agent.Health.Junk>?>(null) }
    var confirmFix by remember(h.id) { mutableStateOf(false) }
    /** 勾了哪几**类**要收拾。⚠️ 默认空 —— 这是杀进程，让人主动勾比让人记得取消安全。 */
    var picked by remember(h.id) { mutableStateOf<Set<String>>(emptySet()) }
    var fixing by remember(h.id) { mutableStateOf(false) }
    /** 「探测 / 装机」框（用户要的常驻入口，**默认不执行**） */
    var probe by remember(h.id) { mutableStateOf(false) }

    LaunchedEffect(h.id, refreshAt) {
        healthBusy = true; healthNote = null
        val c = connect()
        if (c == null) { healthNote = t("这台主机还没有可用的认证方式"); healthBusy = false; return@LaunchedEffect }
        val err = runCatching { c.session.connect() }.exceptionOrNull()
        if (err != null) {
            if (err is kotlinx.coroutines.CancellationException) throw err
            healthNote = c.explain(err); healthBusy = false; return@LaunchedEffect
        }
        app.yxi.ssh.catching { c.session.exec(app.yxi.agent.Health.COMMAND) }
            .onSuccess { out ->
                val v = app.yxi.agent.Health.parse(out)
                if (v == null) healthNote = t("读不出这台机器的状态（不是 Linux？）")
                else health = app.yxi.agent.Health.score(v)
            }
            .onFailure { healthNote = t("体检失败：%s").format(it.message ?: "") }
        // 顺手扫一遍「有什么可以安全收掉的」—— 不收，只看
        app.yxi.ssh.catching { c.session.exec(app.yxi.agent.Health.SCAN_COMMAND) }
            .onSuccess { junk = app.yxi.agent.Health.junkFrom(it) }
        runCatching { c.session.disconnect() }
        healthBusy = false
    }

    // ⚠️ **每次长按展开都重查一次**（用户明确要的：「长按服务器就更新一次用量」）。
    // 缓存那份先摆着别让面板空着，同时 `busy` 把旧数字压暗 + 画转圈 ——
    // 新旧值长得一样时，没有这个可见状态用户看不出到底刷没刷。
    //
    // ⚠️ 慢是**正常**的：`claude -p '/usage'` 要去连一次 API，几秒到十几秒都有。
    LaunchedEffect(h.id, refreshAt) {
        busy = true; note = null
        val c = connect()
        if (c == null) { note = t("这台主机还没有可用的认证方式"); busy = false; return@LaunchedEffect }
        val err = runCatching { c.session.connect() }.exceptionOrNull()
        if (err != null) {
            if (err is kotlinx.coroutines.CancellationException) throw err
            note = c.explain(err); busy = false; return@LaunchedEffect
        }
        val (got, why) = app.yxi.ssh.catching { app.yxi.agent.Quota.fetchDetailed(c.session) }
            .getOrDefault(null to app.yxi.agent.Quota.Why.Failed(""))
        runCatching { c.session.disconnect() }
        if (got != null) { q = got; QuotaCache.put(ctx, h.id, got); fetchedAt = System.currentTimeMillis() / 1000 }
        else {
            // ⚠️ **说真原因。** 这里原来一律写「没有空闲会话可借来查额度」——
            // 而这条路**根本不借会话**（`Quota.fetch` 是直接 exec）。
            // 说错原因比不说更糟：用户会照着去关会话，然后发现没用。
            note = when (why) {
                app.yxi.agent.Quota.Why.NoClaude ->
                    t("这台机器上没装 claude —— 额度是问它要的")
                app.yxi.agent.Quota.Why.Timeout ->
                    t("查询超时：claude -p '/usage' 要连 API，30 秒没回来")
                app.yxi.agent.Quota.Why.Unparsable ->
                    t("认不出 /usage 的输出 —— 多半是 Claude Code 换排版了")
                is app.yxi.agent.Quota.Why.Failed ->
                    t("查不到：%s").format(why.message.ifBlank { t("连接出错") })
                null -> null
            }
        }
        busy = false
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 体检：分数 + 那几个数 + 扣在哪儿 ──
        // ⚠️ 放在额度**前面**：它出得快得多，机器越卡这个差距越大。
        health?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 分数用颜色说话：绿→黄→橙→红
                val tone = when {
                    r.score >= 85 -> Color(0xFF5FB570)
                    r.score >= 65 -> Color(0xFFD6C34A)
                    r.score >= 40 -> Color(0xFFE0913F)
                    else -> MaterialTheme.colorScheme.error
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${r.score}", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = tone,
                    )
                    Text(
                        " /100", style = MaterialTheme.typography.labelSmall,
                        color = Dim, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (app.yxi.agent.Health.verdict(r.score)) {
                            "easy" -> t("松快")
                            "tight" -> t("有点紧")
                            "strained" -> t("很吃力")
                            else -> t("快扛不住了")
                        },
                        style = MaterialTheme.typography.labelLarge, color = tone,
                    )
                    Text(
                        t("负载 %.1f/%d核 · 内存 %d%% · 交换 %d%% · 被抢 %d%% · 盘 %d%%").format(
                            r.vitals.load1, r.vitals.cores, r.vitals.memUsedPct,
                            r.vitals.swapUsedPct, r.vitals.steal, r.vitals.diskUsedPct,
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Dim, maxLines = 2,
                    )
                }
            }
            // 扣分理由。⚠️ 不 fixable 的要**明说修不了**，
            // 否则用户按了一键修复没反应，只会更困惑。
            r.issues.forEach { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("−${i.cost}", style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace), color = Dim)
                    Text(
                        issueText(i) + if (i.fixable) "" else t("（机器里面修不了，得找服务商）"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i.fixable) Muted else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 一键修复：有东西可收才出现
            junk?.takeIf { it.isNotEmpty() }?.let { list ->
                val mb = list.sumOf { it.rssKb } / 1024
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = Pill,
                    modifier = Modifier.fillMaxWidth().clip(Pill).clickable(enabled = !fixing) { confirmFix = true },
                ) {
                    Text(
                        // 按**类别**报数，跟点进去看到的一致 —— 外面说「3 项」进去却是 1 组会对不上
                        if (fixing) t("收拾中…")
                        else t("可以收拾 %d 类 · 约 %d MB").format(list.map { it.what }.distinct().size, mb),
                        Modifier.padding(14.dp, 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (healthBusy && health == null) MorphButton(MorphPhase.Run, "", Modifier.fillMaxWidth(), height = 44.dp)
        healthNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        q?.let {
            // ⚠️ **查新的时候把旧数字压暗。** 缓存值和实时值长得一模一样，
            // 满色画着的话，用户分不出面板上这两根条是刚取的还是上次的（他报的就是这个）。
            Column(Modifier.alpha(if (busy) .4f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 档位 + 工具，学 Moshi 那个「Max 20x · Claude Code」的头。读不到档位就只写工具名。
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (it.plan.isNotBlank()) Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer, shape = Pill,
                ) {
                    Text(
                        it.plan, Modifier.padding(9.dp, 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text("Claude Code", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            QuotaBar(t("5 小时"), it.sessionPct, it.sessionResets)
            QuotaBar(t("本周"), it.weekPct, it.weekResets)
            }
        }
        // ⚠️ `heightIn` 是为了**不跳**：转圈那会儿这行比平时高，查完塌回去会闪一下。
        Row(
            Modifier.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                // ⚠️ 查额度**慢是常态**（`claude -p '/usage'` 要连一次 API，几秒到十几秒）。
                // 原来这里只有一行灰字「查着…」，太轻了 —— 用户看不出在动，
                // 以为面板上那两根缓存的条就是实时的。换成「下载并安装」那个
                // [MorphButton] 的 Run 态（转圈 + 跑条），跟装更新一个动效，一眼就知道在取新的。
                busy -> MorphButton(MorphPhase.Run, "", Modifier.weight(1f), height = 44.dp)
                // ⚠️ **先判 note 再判 fetchedAt**，别反过来。反过来的话
                // 「有缓存 + 这次刷新失败」只会画出时间戳，错误被**整个吞掉** ——
                // 用户以为刷成功了，其实盯着的还是旧数字。
                note != null -> Text(note!!, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Dim)
                fetchedAt > 0 -> Text(
                    t("%s 取的").format(ago(fetchedAt)), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = Dim,
                )
                else -> Spacer(Modifier.weight(1f))
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                modifier = Modifier.clip(Pill).clickable { probe = true },
            ) {
                Text(t("探测 / 装机"), Modifier.padding(14.dp, 7.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(onClick = onEdit),
            ) {
                Text(t("改主机"), Modifier.padding(14.dp, 7.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }

        // Tailscale 内网设备列表：点按钮才拉，不轮询
        var tsOpen by remember(h.id) { mutableStateOf(false) }
        var tsDevices by remember(h.id) { mutableStateOf<List<app.yxi.agent.TailscaleStatus.Device>?>(null) }
        var tsBusy by remember(h.id) { mutableStateOf(false) }
        var tsNote by remember(h.id) { mutableStateOf<String?>(null) }

        fun loadTailscale() {
            if (tsBusy) return
            scope.launch {
                tsBusy = true; tsNote = null; tsDevices = null
                val c = connect()
                if (c == null) { tsNote = t("没有可用的认证方式"); tsBusy = false; return@launch }
                val err = runCatching { c.session.connect() }.exceptionOrNull()
                if (err != null) {
                    if (err is kotlinx.coroutines.CancellationException) throw err
                    tsNote = c.explain(err); tsBusy = false; return@launch
                }
                // ⚠️ disconnect 放 finally：`catching` 会把 CancellationException 原样抛出，用户在拉取途中把卡片收起
                //    协程一取消就跳过了下面那行 disconnect，jsch 的 Session 连着 TCP 就漏了（审查查出）
                val result = try {
                    app.yxi.ssh.catching { app.yxi.agent.TailscaleStatus.fetch(c.session) }
                } finally {
                    runCatching { c.session.disconnect() }
                }
                result
                    .onSuccess { list ->
                        if (list == null) tsNote = t("这台机器没装 Tailscale 或没登录")
                        else tsDevices = list
                    }
                    .onFailure { tsNote = t("查询失败：%s").format(it.message ?: "") }
                tsBusy = false
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
            modifier = Modifier.clip(Pill).clickable { if (!tsOpen) { tsOpen = true; loadTailscale() } else tsOpen = false },
        ) {
            Text(
                if (tsOpen) t("收起内网设备") else t("内网设备"),
                Modifier.padding(14.dp, 7.dp),
                style = MaterialTheme.typography.labelMedium, color = Muted,
            )
        }

        if (tsOpen) {
            if (tsBusy) MorphButton(MorphPhase.Run, "", Modifier.fillMaxWidth(), height = 36.dp)
            tsNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            tsDevices?.let { devices ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { d ->
                        // ⚠️ **本机那条不给点**：点它等于「从这台机器 ssh 回它自己」，没有意义。
                        //    离线的也不给点 —— 点了必然失败，不如让它看着就是不可点的。
                        val canOpen = !d.isSelf && d.online && d.ip.isNotBlank()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = canOpen) { onOpenSlave(d) }
                                .padding(vertical = 2.dp),
                        ) {
                            // 状态点
                            Box(
                                Modifier.size(8.dp).background(
                                    if (d.online) Color(0xFF5FB570) else Color(0xFF999999),
                                    RoundedCornerShape(4.dp),
                                )
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.hostName + if (d.isSelf) t("（本机）") else "",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    d.ip + " · " + d.os +
                                        if (!d.online && d.lastSeen != null) t(" · 最后在线 %s").format(app.yxi.agent.Tz.dateTime(d.lastSeen))
                                        else "",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Dim,
                                )
                            }
                            // 能点的才给箭头 —— 「看它上面的会话」这件事得看得出来能点
                            if (canOpen) Text("›", style = MaterialTheme.typography.labelLarge, color = Muted)
                        }
                    }
                    // 刷新按钮
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                        modifier = Modifier.clip(Pill).clickable(enabled = !tsBusy) { loadTailscale() },
                    ) {
                        Text(t("刷新"), Modifier.padding(14.dp, 5.dp), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
        }
    }

    // 探测 / 装机：主机页平时不保连接，这一下现连，关框就断（跟额度一个路子）
    if (probe) {
        val sess = remember { mutableStateOf<app.yxi.ssh.SshSession?>(null) }
        var perr by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val c = connect() ?: run { perr = t("这台主机还没有可用的认证方式"); return@LaunchedEffect }
            val e = runCatching { c.session.connect() }.exceptionOrNull()
            if (e != null) { if (e is kotlinx.coroutines.CancellationException) throw e; perr = c.explain(e); return@LaunchedEffect }
            sess.value = c.session
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { sess.value?.let { s0 -> scope.launch { runCatching { s0.disconnect() } } } }
        }
        ProbeDialog(sess.value, error = perr) { probe = false }
    }

    // ⚠️ **杀之前把要杀的逐条摆出来。** 一键修复要是能弄丢东西，
    // 它就不是「方便」而是陷阱 —— 所以先看清、再点。
    // ⚠️ **逐条可选，而且默认全不选。**
    // 「一键全杀」在只有一类东西时还行，一旦扫出好几类就太粗 ——
    // 用户可能只想收掉 Gradle 缓存、但保留正在跑的那个搜索。
    // 默认不选是因为这是**杀进程**：让人主动勾，比让人记得取消安全。
    if (confirmFix) {
        val list = junk.orEmpty()
        // 按类别归堆：一堆 Gradle 守护进程列成十行没意义，
        // 而「Gradle 编译守护进程 · 3 个 · 3100 MB」一眼就够做决定。
        val groups = list.groupBy { it.what }.toList().sortedByDescending { (_, v) -> v.sumOf { it.rssKb } }
        val chosen = groups.filter { it.first in picked }.flatMap { it.second }
        AlertDialog(
            onDismissRequest = { confirmFix = false },
            title = { Text(t("挑要收拾的")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t("清理缓存和失控进程不会丢数据。会话会被关闭，对话存档保留，可随时接回。"),
                        style = MaterialTheme.typography.labelSmall, color = Dim,
                    )
                    Column(
                        Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        groups.forEach { (what, items) ->
                            val on = what in picked
                            val mb = items.sumOf { it.rssKb } / 1024
                            Surface(
                                color = if (on) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = Pill,
                                modifier = Modifier.fillMaxWidth().clip(Pill).clickable {
                                    picked = if (on) picked - what else picked + what
                                },
                            ) {
                                Row(
                                    Modifier.padding(14.dp, 9.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (on) "✓" else "○", style = MaterialTheme.typography.labelLarge)
                                    Column(Modifier.weight(1f)) {
                                        Text(junkText(what), style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            // 最久的那个跑了多久 —— 判断「是不是跑飞了」看这个
                                            // ⚠️ 会话那类 ageSec 是「多久没动过」不是「跑了多久」，
                                            // 照进程的话术写会把「闲了 17 天」说成「跑了 17 天」，正好反了
                                            if (what == "idle")
                                                t("%d 个 · %d MB · 最久 %d 天没动过").format(
                                                    items.size, mb, (items.maxOf { it.ageSec }) / 86400,
                                                )
                                            else t("%d 个 · %d MB · 最久跑了 %d 分钟").format(
                                                items.size, mb, (items.maxOf { it.ageSec }) / 60,
                                            ),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = Dim,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // ⚠️ steal 高的时候要**明说这个按钮救不了它**，别让人白按一次再失望
                    health?.vitals?.takeIf { it.steal >= 10 }?.let {
                        Text(
                            t("⚠️ 这台机器有 %d%% 的 CPU 被宿主机抢走了 —— 那个收拾不掉，得让服务商迁移实例。").format(it.steal),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                // ⚠️ 一个都没勾就**禁用**，别让人点了个没反应的按钮
                TextButton(enabled = chosen.isNotEmpty(), onClick = {
                    confirmFix = false
                    val cmd = app.yxi.agent.Health.killCommand(chosen) ?: return@TextButton
                    scope.launch {
                        fixing = true
                        val c = connect()
                        val s0 = c?.session
                        if (s0 != null && runCatching { s0.connect() }.isSuccess) {
                            app.yxi.ssh.catching { s0.exec(cmd) }
                            // 收完立刻重新体检一次，让分数当场动给用户看
                            app.yxi.ssh.catching { s0.exec(app.yxi.agent.Health.COMMAND) }
                                .onSuccess { out ->
                                    app.yxi.agent.Health.parse(out)?.let { health = app.yxi.agent.Health.score(it) }
                                }
                            app.yxi.ssh.catching { s0.exec(app.yxi.agent.Health.SCAN_COMMAND) }
                                .onSuccess { junk = app.yxi.agent.Health.junkFrom(it) }
                            runCatching { s0.disconnect() }
                        }
                        picked = emptySet()
                        fixing = false
                    }
                }) {
                    Text(
                        if (chosen.isEmpty()) t("先勾几个")
                        else t("收拾 %d 个 · %d MB").format(chosen.size, chosen.sumOf { it.rssKb } / 1024),
                    )
                }
            },
            dismissButton = { TextButton({ confirmFix = false }) { Text(t("算了")) } },
        )
    }
}

/** 展开区的一档额度条：`5 小时  ██░░░░  已用 10% · 剩 90%   6:50pm 重置`。 */
@Composable
private fun QuotaBar(label: String, pct: Int, resets: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(48.dp))
        androidx.compose.foundation.layout.Box(
            Modifier.weight(1f).height(5.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, Pill),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth(pct / 100f).height(5.dp)
                    .background(if (pct > 85) Amber else Teal, Pill),
            )
        }
        Text(
            t("已用 %d%% · 剩 %d%%").format(pct, 100 - pct),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (pct > 85) Amber else OnSurfaceVariant,
        )
    }
    if (resets.isNotBlank()) {
        Text("      " + resets + t(" 重置"), style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 1)
    }
}


/**
 * 加 / 改主机：任意 IP、**任意端口**、用户名、密码或密钥 —— 四样都不能写死。
 *
 * ⚠️ **必须能改、能删。** 早先只有「加」：点一下是连接、长按是装公钥，
 * 于是地址打错、或者要换个端口，用户**一点办法都没有**（连删都删不掉，
 * 只能卸载重装）。而「地址里混进全角字符」正是最常见的一种错 ——
 * 我们既提示了「删掉重加」，就得真有地方能删。见 TROUBLESHOOTING #68。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostSheet(
    store: HostStore,
    keys: KeyManager,
    ctx: android.content.Context,
    editing: Host? = null,
    onInstallKey: () -> Unit = {},
    onDone: () -> Unit,
) {
    var alias by remember { mutableStateOf(editing?.alias ?: "") }
    var hostname by remember { mutableStateOf(editing?.hostname ?: "") }
    var port by remember { mutableStateOf((editing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(editing?.username ?: "root") }
    var usePassword by remember { mutableStateOf(editing?.useKey == false) }
    var password by remember { mutableStateOf("") }
    var tailscaleIp by remember { mutableStateOf(editing?.tailscaleIp ?: "") }
    var useTailscale by remember { mutableStateOf(editing?.useTailscale ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            // ⚠️ **表要能滚。** 半开的表放不下这么多字段，「保存」落在屏幕外 ——
            // 用户得先把表往上拖才够得着，而没有任何东西提示他要拖。
            // imePadding：软键盘弹起来时同样会盖住「保存」。见 TROUBLESHOOTING #68。
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (editing != null) t("改主机") else t("加新主机"), style = MaterialTheme.typography.titleLarge)

            Field(alias, { alias = it }, t("名字（随便起，只给你自己看）"))
            // ⚠️ 别名≠地址：手机上没有 ~/.ssh/config，「station」「天亮」这类 SSH 别名解析不了，
            // 下面那栏必须是真地址。标签曾经写「主机名 / IP」，等于在邀请用户填别名。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    Field(
                        hostname,
                        {
                            // ⚠️ **打字的时候只做「全角→半角」这种安全归一，绝不动结构。**
                            // 试过「一次进来一大段就当粘贴、顺手拆出 :port」——
                            // 不成立：输入法整词上屏、adb 成块送，都会触发，
                            // 结果是打到 `…147:22` 时端口被切走、剩下的 `22` 落回地址栏。
                            // **在用户手指底下改他正在打的东西，跟「列表在手指下重排」是同一类错误。**
                            // 拆分放到保存时做 —— 那时整串才是完整的。
                            hostname = app.yxi.ssh.HostInput.normalize(it)
                        },
                        t("IP 或域名，如 38.244.50.31"), mono = true,
                    )
                }
                // ⚠️ 端口不能写死 22 —— 客户那台 Windows 走 2222
                Box(Modifier.width(96.dp)) {
                    Field(port, { port = it.filter(Char::isDigit).take(5) }, t("端口"), mono = true, number = true)
                }
            }
            run {
                val p = app.yxi.ssh.HostInput.parse(hostname)
                if (p.user != null || p.port != null) {
                    Text(
                        t("保存时会拆成：地址 %s").format(p.host) +
                            (p.user?.let { t(" · 用户名 %s").format(it) } ?: "") +
                            (p.port?.let { t(" · 端口 %s").format(it) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            app.yxi.ssh.HostInput.suspiciousChar(hostname)?.let {
                Text(
                    t("地址里有个连不上的字符：%s —— 多半是中文输入法打出来的，删掉重打").format(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Field(username, { username = it }, t("用户名"), mono = true)

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill) {
                Row(Modifier.padding(4.dp)) {
                    SegItem(t("密钥"), !usePassword, Modifier.weight(1f)) { usePassword = false }
                    SegItem(t("密码"), usePassword, Modifier.weight(1f)) { usePassword = true }
                }
            }

            if (usePassword) {
                Field(password, { password = it }, if (editing?.sealedPassword != null) t("密码（留空 = 不改）") else t("密码"), password = true)
                Hint(t("密码用设备密钥加密后保存，不落明文。连上后可以一键装公钥，之后免密。"))
            } else {
                Hint(t("用 App 自己的 ed25519 密钥。先去右上角「公钥」把它贴进目标机的 authorized_keys。"))
            }

            // 公网 / Tailscale 内网。只换连的 IP，认证还是上面选的那种
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill) {
                Row(Modifier.padding(4.dp)) {
                    SegItem(t("公网"), !useTailscale, Modifier.weight(1f)) { useTailscale = false }
                    SegItem(t("Tailscale 内网"), useTailscale, Modifier.weight(1f)) { useTailscale = true }
                }
            }
            if (useTailscale) {
                Field(
                    tailscaleIp, { tailscaleIp = app.yxi.ssh.HostInput.normalize(it) },
                    t("Tailscale 内网 IP，如 100.111.242.66"), mono = true,
                )
                // 跟上面公网地址同一套检查：中文输入法打出的全角句号在这儿就提醒，别等到列表页才发现连不上
                app.yxi.ssh.HostInput.suspiciousChar(tailscaleIp)?.let {
                    Text(
                        t("地址里有个连不上的字符：%s —— 多半是中文输入法打出来的，删掉重打").format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Hint(t("手机也要装 Tailscale 并登进同一个 tailnet（目标机上 tailscale status 能看到）。认证不变，还是上面选的密钥 / 密码。"))
            }

            Button(
                onClick = {
                    // 保存时再拆一次 —— 手打的 `ip:2222` / `root@ip` 到这一刻才是完整的
                    val parsed = app.yxi.ssh.HostInput.parse(hostname)
                    val hn = parsed.host
                    if (hn.isEmpty()) return@Button
                    // 地址栏里带的 `:port` / `root@` 优先于另外两栏里的值 ——
                    // 用户刚敲进去的那一串才是他最新的意思
                    val pt = parsed.port ?: port.toIntOrNull() ?: 22
                    store.upsert(
                        Host(
                            id = editing?.id ?: UUID.randomUUID().toString(),
                            alias = alias.trim().ifEmpty { hn },
                            hostname = hn,
                            port = pt,
                            username = (parsed.user ?: username).trim().ifEmpty { "root" },
                            useKey = !usePassword,
                            sealedPassword = when {
                                !usePassword -> null
                                password.isNotEmpty() -> Vault.seal(password)
                                // 编辑时密码栏留空 = 不动原来那份密文
                                else -> editing?.sealedPassword
                            },
                            // ⚠️ **改了地址或端口，存的主机指纹就必须作废。**
                            // 那把指纹属于旧机器；留着的话下次连新机器会报「指纹变了」——
                            // 那是中间人警告的措辞，会把一次正常的改配置说成攻击。
                            hostKey = editing?.hostKey?.takeIf {
                                editing.hostname == hn && editing.port == pt &&
                                    editing.tailscaleIp.orEmpty() == tailscaleIp.trim()
                            },
                            watch = editing?.watch ?: false,
                            // 关掉开关也留着 IP —— 下次再开不用重填
                            tailscaleIp = tailscaleIp.trim().ifEmpty { null },
                            useTailscale = useTailscale,
                        )
                    )
                    onDone()
                },
                enabled = hostname.isNotBlank() && (!useTailscale || tailscaleIp.isNotBlank()),
                shape = Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(t("保存")) }

            if (editing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { IconTextButton(t("装公钥"), onClick = onInstallKey) }
                    Box(Modifier.weight(1f)) {
                        // 删除要两下 —— 手机上误触一下就没了，而主机记录里有密码密文
                        IconTextButton(if (confirmDelete) t("再点一次删除") else t("删除")) {
                            if (!confirmDelete) { confirmDelete = true; return@IconTextButton }
                            store.remove(editing.id)
                            // 删的可能正是唯一开着铃铛的那台 —— 不同步，前台服务会继续盯一台不存在的机器
                            app.yxi.watch.EventService.sync(ctx, store.hosts.value.any { it.watch })
                            onDone()
                        }
                    }
                }
                Hint(t("长按主机就能回到这里。改地址或端口会作废已记住的指纹，下次连接重新确认一次。"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicKeySheetPublic(keys: KeyManager, onDone: () -> Unit) {
    // 换钥匙之后要重刷，所以是 state 不是 remember 常量
    var gen by remember { mutableStateOf(0) }
    val line = remember(gen) {
        runCatching { keys.publicKeyLine() }
            .getOrElse { t("生成失败：%s: %s").format(it::class.simpleName, it.message ?: "(no message)") }
    }
    val fp = remember(gen) { runCatching { keys.fingerprint() }.getOrDefault("") }
    var copied by remember { mutableStateOf(false) }
    var confirmRegen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun copy() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ssh public key", line))
        copied = true
    }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            // ⚠️ 要能滚：矮屏（模拟器 720x1280）上底部两个按钮会被挤出屏幕，够不着
            Modifier.verticalScroll(rememberScrollState()).padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(t("这台手机的公钥"), style = MaterialTheme.typography.titleLarge)
            Hint(t("点一下整块就复制。贴进目标机的 ~/.ssh/authorized_keys（一行）。撤销就删掉那一行，不用改 App 任何设置。"))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { copy() },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text(
                        if (copied) t("✓ 已复制到剪贴板") else t("点这里复制"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (fp.isNotEmpty()) {
                Text(
                    t("指纹 %s").format(fp),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ copy() }, shape = Pill, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(if (copied) t("已复制") else t("复制公钥"))
                }
                OutlinedButton(
                    { confirmRegen = true }, shape = Pill,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(t("换一把")) }
            }
        }
    }

    // ⚠️ 换钥匙是**不可逆**的：旧私钥直接丢，所有装过旧公钥的服务器立刻连不上。
    // 所以必须先问一句，且把后果说清楚——不是「确定吗」这种没信息量的提示。
    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text(t("换一把新密钥？")) },
            text = {
                Text(
                    t("旧私钥会被丢掉，换不回来。\n\n") +
                        t("所有已经装过旧公钥的服务器都会立刻连不上，") +
                        t("要么重新装一次新公钥，要么手工删掉 authorized_keys 里那行 yxi@android。\n\n") +
                        t("只有在怀疑私钥泄露、或想换台手机重来时才需要这么做。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton({
                    keys.regenerate(); gen++; copied = false; confirmRegen = false
                }) { Text(t("换")) }
            },
            dismissButton = { TextButton({ confirmRegen = false }) { Text(t("算了")) } },
        )
    }
}

// ——— 小件 ———

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    mono: Boolean = false,
    number: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value, onValue,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SegItem(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        shape = Pill,
        modifier = modifier.height(44.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun IconTextButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        shape = Pill,
        modifier = Modifier.height(44.dp).clip(Pill).clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 18.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 一键装公钥：用密码连一次，把 App 的公钥追加进 `authorized_keys`，之后免密。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallKeySheet(
    connect: Connect,
    keys: KeyManager,
    store: HostStore,
    host: Host,
    onDone: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(t("给 %s 装公钥").format(host.alias), style = MaterialTheme.typography.titleLarge)
            Hint(t("用密码连一次，把这台手机的公钥写入目标机 ~/.ssh/authorized_keys，之后免密登录。"))
            Field(password, { password = it }, t("密码"), password = true)
            if (password.isNotEmpty()) {
                SubmitButton(
                    label = t("连接并安装"),
                    modifier = Modifier.fillMaxWidth(),
                    successLabel = t("装好了，已切密钥"),
                    // 用密码连一次，把公钥追加进 authorized_keys，成功就切到密钥认证。失败给人话原因。
                    work = {
                        val c = connect(app.yxi.ssh.HostConfig.Auth.Password(password))
                        if (c == null) Result.failure(RuntimeException(t("建不了连接")))
                        else runCatching {
                            c.session.connect()
                            val n = c.session.installPublicKey(keys.publicKeyLine()).trim()
                            c.session.disconnect()
                            store.upsert(host.copy(useKey = true, sealedPassword = app.yxi.ssh.Vault.seal(password)))
                            t("装好了（%s 行公钥）").format(n)
                        }.recoverCatching { if (it is kotlinx.coroutines.CancellationException) throw it; throw RuntimeException(c.explain(it)) }
                    },
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(t("连接并安装"), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** 系统有没有把这个 app 从省电优化里放出来。 */
private fun ignoringBattery(ctx: android.content.Context): Boolean = runCatching {
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(true)   // 查不到就别烦用户

/**
 * ⚠️ **这是「手机主动响」唯一会静默失效的地方，所以必须当面说清楚。**
 *
 * 前台服务能扛住系统的低内存回收（`START_STICKY`，实测系统杀掉 15 秒后自己回来），
 * 但**扛不住 force-stop** —— 而荣耀 MagicOS / 华为 EMUI 的后台管控就是 force-stop。
 * 被那样杀掉之后 Android 不会再拉起它，通知就**无声无息地停了**：
 * 用户不会收到任何错误，只会觉得「怎么最近不响了」。
 *
 * 所以放行后台不是「优化建议」，是这个功能能不能用的前提。
 */
@Composable
private fun BatteryHint(ctx: android.content.Context, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(t("还要放行后台运行")) },
        text = {
            Text(
                t("这台机器上的 Claude 需要你时，手机靠一条常驻连接来响。\n\n") +
                    t("系统的省电优化会把它掐掉 —— 荣耀 / 华为 尤其狠，") +
                    t("而且掐掉之后不会有任何提示，你只会觉得「怎么不响了」。\n\n") +
                    t("去设置里把 Yxi 设成「允许后台活动 / 不受限制」。"),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton({
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                onDone()
            }) { Text(t("去设置")) }
        },
        dismissButton = { TextButton(onDone) { Text(t("知道了")) } },
    )
}

/** 扣分理由的话术。⚠️ 在这儿拼，不在 [app.yxi.agent.Health] 里 —— 那边是纯逻辑，
 *  而且插值拼出来的句子 `t()` 永远匹配不上、i18n-check 也看不见。 */
@Composable
private fun issueText(i: app.yxi.agent.Health.Issue): String = when (i.code) {
    "steal" -> when (i.level) {
        2 -> t("CPU 被宿主机抢走 %d%% —— 这台云主机所在的物理机严重超卖").format(i.value)
        1 -> t("CPU 被宿主机抢走 %d%%").format(i.value)
        else -> t("CPU 被宿主机抢走 %d%%，偏高").format(i.value)
    }
    "swap" -> when (i.level) {
        2 -> t("交换分区用掉 %d%% —— 机器在颠簸，什么都会变慢").format(i.value)
        1 -> t("交换分区用掉 %d%%").format(i.value)
        else -> t("开始用交换分区了（%d%%）").format(i.value)
    }
    "load" -> if (i.level >= 1) t("负载是核数的 %.1f 倍，进程在排长队").format(i.value / 10.0)
    else t("负载是核数的 %.1f 倍").format(i.value / 10.0)
    "mem" -> if (i.level >= 1) t("内存用掉 %d%%，快没了").format(i.value)
    else t("内存用掉 %d%%").format(i.value)
    "disk" -> if (i.level >= 1) t("根分区用掉 %d%%，快写不进去了").format(i.value)
    else t("根分区用掉 %d%%").format(i.value)
    else -> ""
}

/** 一键收拾里那几类东西的名字。 */
@Composable
private fun junkText(code: String): String = when (code) {
    "gradle" -> t("Gradle 编译守护进程")
    "kotlin" -> t("Kotlin 编译守护进程")
    "rg" -> t("跑飞的 rg 全盘搜索")
    "hog" -> t("一直霸着 CPU 的进程")
    "idle" -> t("很久没动过的会话")
    else -> code
}
