package app.yxi.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.yxi.ui.theme.Dim
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import app.yxi.ui.theme.Copper
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.agent.SessionState
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.KnownHosts
import app.yxi.ssh.SshSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Pill = RoundedCornerShape(100.dp)

/**
 * 会话看板：**等你 / 干活中 / 已完成** 三段。
 *
 * 只有「等你」那组带操作按钮，其余安静 —— 琥珀色是全 app 唯一
 * 「需要你动手」的信号，别处不用（PRD 附录 J.1）。
 *
 * ⚠️ 这个界面**不需要在服务器上装任何东西**：`tmux list-sessions` 和
 * `~/.cloud-status` 下的状态文件都是现成的（后者由 `cc-state` 写，早就在跑）。
 * ⚠️ 注意：Kotlin 的块注释**可嵌套**，注释里别写含 `/` 紧跟 `*` 的路径（见 TROUBLESHOOTING #23）。
 */
@Composable
fun SessionsScreen(
    store: HostStore,
    keys: KeyManager,
    host: Host,
    /** 主机下拉：换主机不用退出去（D22）。只有一台时不显示箭头 */
    /** ⚠️ 连接由 [app.yxi.MainActivity] 持有 —— 切 tab 时这个 composable 会销毁，连接不能跟着断 */
    ssh: SshSession?,
    /** 会话列表。⚠️ 同样由 [app.yxi.MainActivity] 持有，理由见下面 `status` 那段注释 */
    sessions: List<Session>,
    onSessions: (List<Session>) -> Unit,
    connectError: String? = null,
    onRetry: () -> Unit = {},
    hosts: List<Host> = listOf(host),
    onPickHost: (Host) -> Unit = {},
    /** (会话名, cwd)。⚠️ **cwd 必须一起传** —— 对话模式靠它找转录文件 */
    onOpenTerminal: (String?, String) -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // ⚠️ **别在这儿 `remember` 会话列表。** 它跟连接一样得活得比这个界面久 ——
    // 存在这里的话，切回来是空列表，要等一次往返才有内容，
    // 中间那一下就是用户说的「骨架屏闪光」。现在由 [app.yxi.MainActivity] 持有。
    var status by remember { mutableStateOf(if (sessions.isEmpty()) t("连接中…") else "") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sftp by remember(host.id) { mutableStateOf<app.yxi.ssh.Sftp?>(null) }
    var update by remember(host.id) { mutableStateOf<app.yxi.agent.Update?>(null) }
    // 列表 / 悬浮排列。⚠️ 两者**并存**不是替代 —— 悬浮好看但同屏信息量少三分之一，
    // 20 个会话的时候还是列表能一眼扫完（决策 D16b 里就写明了这个代价）
    var pinned by remember(host.id) { mutableStateOf(Pinned.get(ctx, host.id)) }
    var view by remember(host.id) { mutableStateOf(Board.view(ctx, host.id)) }
    var collapsed by remember(host.id) { mutableStateOf(Board.collapsed(ctx, host.id)) }
    var groups by remember(host.id) { mutableStateOf(app.yxi.agent.Groups.Table()) }
    /// 正在给哪个会话选组
    var grouping by remember { mutableStateOf<Session?>(null) }
    /** 要终止哪个会话（滑动后弹确认框）。null = 没在问 */
    var killing by remember { mutableStateOf<Session?>(null) }
    /** 收藏了哪些。⚠️ **跟置顶各管各的** —— 置顶管位置，收藏管「还要不要它」。 */
    var faved by remember(host.id) { mutableStateOf(Favorites.get(ctx, host.id)) }
    var muted by remember(host.id) { mutableStateOf(Mute.get(ctx, host.id)) }
    var refreshing by remember { mutableStateOf(false) }
    // 「回它一句」目标 —— 非空就弹底部输入框，送键到那个会话（不进对话）
    var replyTo by remember { mutableStateOf<Session?>(null) }
    var newSession by remember { mutableStateOf(false) }

    LaunchedEffect(connectError) { connectError?.let { status = it } }

    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        // 每 5 秒刷一次。一次往返拿全部，不是一个会话一个请求
        while (true) {
            // ⚠️ **手指在列表上的时候不要刷。** 会话换组（干活中 → 等你）会让下面的卡片整体上移，
            // 而刷新和点击之间只有几十毫秒 —— 我自己就因此点进过别人的会话。
            // 用户看到的位置和点下去的位置必须是同一个。
            if (!listState.isScrollInProgress) {
                runCatching { SessionProbe.snapshotFull(s) }
                    // ⚠️ 顺手存一份给工作区左上角那个下拉用（[app.yxi.agent.Recent]）——
                    // 它原来是「点了才去抓」，打开菜单要干等一趟 SSH 往返
                    .onSuccess {
                        app.yxi.agent.Recent.put(host.id, it.sessions)
                        onSessions(it.sessions); groups = it.groups; status = ""
                    }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        status = t("刷新失败：%s").format(it.message)
                    }
            }
            delay(5_000)
        }
    }
    // 更新检查：连上之后看一眼就完事，不轮询。
    // ⚠️ **先查公网下载页**（用户定的）—— 这样换个客户也能在 App 里更新，
    // 不要求他自己的服务器上放着包。公网不通（没外网/被墙）才退回查所连服务器。
    // ⚠️ **公网检查不能挂在 ssh 上**。原来写成 `val s = ssh ?: return`，
    // 于是没连上（或还没连上）就永远查不到更新 —— 而「换个客户也能更新」正是走公网的理由。
    LaunchedEffect(Unit) {
        update = app.yxi.agent.Update.checkPublic(app.yxi.BuildConfig.VERSION_CODE)
    }
    // 回落：公网不通（没外网/被墙）时再问所连的服务器
    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        val f = runCatching { s.openSftp() }.getOrNull() ?: return@LaunchedEffect
        sftp = f
        if (update == null) update = app.yxi.agent.Update.check(f, app.yxi.BuildConfig.VERSION_CODE)
    }

    // ⚠️ **只收自己开的 sftp 通道，绝不碰 ssh。**
    // 这里原来写的是 `ssh?.disconnect()` —— 而这条连接是 [app.yxi.MainActivity] 建的、
    // 跨 tab 共用的（这个函数的参数注释上就写着「连接不能跟着断」，代码却在断它）。
    // 后果：切去「设置」再切回来、进一个会话再退出来，都会把共用连接掐掉，
    // 然后 [rememberHostSession] 的看门狗在 3 秒内发现「死了」再连一遍 ——
    // 用户看到的就是「切一次重连一次」。
    //
    // **判据：谁建的谁收。** 这个界面是拿参数拿到的 ssh，那就不归它收。
    DisposableEffect(host.id) { onDispose { sftp?.close() } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            var hostMenu by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.clickable(enabled = hosts.size > 1) { hostMenu = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(hostColor(host.id)))
                    Text(host.alias, style = MaterialTheme.typography.headlineSmall)
                    if (hosts.size > 1) Text("▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                }
                DropdownMenu(hostMenu, { hostMenu = false }) {
                    hosts.forEach { h ->
                        DropdownMenuItem(
                            text = { Text(h.alias + if (h.id == host.id) "  ✓" else "") },
                            onClick = { hostMenu = false; onPickHost(h) },
                        )
                    }
                }
                Text(
                    if (status.isEmpty()) t("%d 个会话 · 点一下进对话").format(sessions.size) else status,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,   // 窄屏上会折成两行把下面顶下去
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // ⚠️ **`weight` 和 `horizontalScroll` 必须一起给。**
            // 只给 horizontalScroll（0.9.35 我就是这么加的）会**把主机名挤没** ——
            // 可横向滚动的 Row 没有宽度上界，Compose 先按它的内容全宽量它，
            // 剩下的才给左边那个 weight 列，于是左列被压到接近 0。
            // 用户报的「主机『天亮』被挡住了」就是这么来的。
            // 1:2 的权重 = 主机名至少拿到三分之一，工具条最多三分之二、装不下就滑。
            // 不写死 dp：加减药丸、换屏幕宽度都不用再调。
            Row(
                Modifier.weight(2f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ＋ 从手机拉起一个新会话（在某目录跑起 claude），不用先到电脑前
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = Pill,
                    modifier = Modifier.height(44.dp).clip(Pill).clickable { newSession = true },
                ) {
                    Box(Modifier.padding(horizontal = 15.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("＋", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                // 换个轴看：按状态（谁在等我）⇄ 按分组（这摊活儿都谁在干）
                Surface(
                    color = if (view == BoardView.Group) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    shape = Pill,
                    modifier = Modifier.height(44.dp).clip(Pill).clickable {
                        view = if (view == BoardView.Group) BoardView.State else BoardView.Group
                        Board.setView(ctx, host.id, view)
                    },
                ) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            if (view == BoardView.Group) t("分组") else t("状态"),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (view == BoardView.Group) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                listOf(t("文件") to onOpenFiles, t("终端") to { onOpenTerminal(null, ".") }).forEach { (label, go) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                        modifier = Modifier.height(44.dp).clip(Pill).clickable(onClick = go),
                    ) {
                        Box(Modifier.padding(horizontal = 16.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // 用量卡固定在列表上方 —— 它是「今天还能干多少」的背景信息，
        // 不该跟着会话列表一起滚走
        update?.let {
            Box(Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp)) {
                // ⚠️ 传 ssh 不传 sftp —— 下载得自己开条活得久的 SFTP，别用界面这条（切页面会关）
                UpdateBanner(ssh, it) { update = null }
            }
        }
        // ⚠️ **会话页顶上不再显示额度**（用户要求）。额度只在主机页长按那台机器时查。
        // 保留 usage/quota 变量是因为下面别的地方（连不上提示、用量缓存）还用得着。

        // ⚠️ 连不上的时候要给**一个能按的东西**。自动重连是指数退避的，
        // 最长等 15 秒 —— 用户刚把网切回来时干等着，只会以为 App 坏了。
        if (ssh == null && connectError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(14.dp, 0.dp, 14.dp, 8.dp),
            ) {
                Row(
                    Modifier.padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        connectError.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    // ⚠️ 不放按钮：下拉就是刷新/重连。多一个按钮 = 多一个要解释的东西，
                    // 而下拉是这类列表上人人都会先试的手势。这里只负责**告诉他能拉**
                    Text(
                        t("↓ 下拉重连"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ⚠️ 下拉的两种含义合成一个手势：**没连上就是重连，连上了就是立刻刷一遍**。
        // 分成两个入口（按钮 + 下拉）只会让人猜该按哪个。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                if (ssh == null) onRetry() else scope.launch {
                    val s = ssh
                    if (s != null) runCatching { SessionProbe.snapshotFull(s) }
                        .onSuccess { onSessions(it.sessions); groups = it.groups; status = "" }
                    // 转一下让人看见它确实动了 —— 一闪而过的刷新等于没反馈
                    delay(400)
                }
                scope.launch { delay(900); refreshing = false }
            },
            modifier = Modifier.weight(1f),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // ⚠️ 置顶的**从原来的组里拿出来**单独放最上面。留在原组只加个图标的话，
            // 会话一多（实测 22 个）照样要翻半天才找到 —— 那就等于没置顶
            // ⚠️ 置顶按**保存的次序**排（不是 sessions 的顺序）—— 拖动排的就是它。
            // 会话没了（被杀）的置顶名跳过，但保留在存储里，回来还在原位。
            val byName = sessions.associateBy { it.name }
            // 收藏的会话活着时顺手记住它在哪个目录 —— 死了才复活得回原地
            faved.forEach { n -> byName[n]?.let { Favorites.remember(ctx, host.id, n, it.cwd) } }
            // ⚠️ **只在「状态」视图里把置顶单独拎出来。**
            // 分组视图下拎出来会让置顶的会话**画两遍**（上面一次、自己组里又一次）——
            // 这是 0.9.35 加分组时引进的 bug。
            // 而且道理上也不该拎：你切到分组视图，选的就是「按组看」这个轴，
            // 再横插一个置顶区等于同时用两个轴。分组视图里置顶只当个标记（图钉亮着）。
            val tops = if (view == BoardView.State) pinned.mapNotNull { byName[it] } else emptyList()
            if (tops.isNotEmpty()) {
                item(key = "h-pinned") { PinnedHeader(tops.size) }
                item(key = "pinned-group") {
                    ReorderablePinned(
                        tops = tops,
                        onOpen = { onOpenChat(it.name, it.cwd) },
                        onReply = { replyTo = it },
                        onUnpin = { pinned = pinned - it.name; Pinned.set(ctx, host.id, pinned) },
                        onReorder = { from, to ->
                            // 在**完整的 pinned 列表**里挪（tops 可能因为会话被杀而比 pinned 短）
                            val names = tops.map { it.name }
                            val moved = names[from]
                            val rest = pinned.toMutableList()
                            rest.remove(moved)
                            // 放到目标那张卡在完整列表里的位置
                            val anchor = names[to]
                            val at = rest.indexOf(anchor).coerceAtLeast(0)
                            rest.add(if (to > from) at + 1 else at, moved)
                            pinned = rest
                            Pinned.set(ctx, host.id, pinned)
                        },
                    )
                }
            }
            if (view == BoardView.Group) {
                // ⚠️ 组按名字排；**没编进任何组的单独兜底放最后** ——
                // 不兜的话，刚建完组的那一刻大部分会话会凭空消失，看着像丢了。
                val inAny = groups.groups.values.flatten().toSet()
                val named = groups.groups.keys.sorted()
                (named + listOf(null)).forEach { g ->
                    val members = if (g == null) sessions.filter { it.name !in inAny }
                    else sessions.filter { it.name in groups.groups[g].orEmpty() }
                    // 空组也画头 —— 建了组还没放人时得看得见它存在
                    if (g == null && members.isEmpty()) return@forEach
                    val label = g ?: t("没编组")
                    val shut = label in collapsed
                    item(key = "gh-$label") {
                        GroupNameHeader(label, members.size, shut) {
                            collapsed = if (shut) collapsed - label else collapsed + label
                            Board.setCollapsed(ctx, host.id, collapsed)
                        }
                    }
                    if (!shut) items(members.size, key = { "$label/${members[it].name}" }) { i ->
                        SwipeCard(
                            faved = members[i].name in faved,
                            onAskKill = { killing = members[i] },
                            onToggleFav = {
                                val n = members[i].name
                                faved = if (n in faved) faved - n else faved + n
                                Favorites.set(ctx, host.id, faved)
                                if (n in faved) Favorites.remember(ctx, host.id, n, members[i].cwd)
                            },
                            modifier = Modifier.animateItem(),
                        ) {
                        SessionCard(
                            members[i],
                            onOpen = { onOpenChat(members[i].name, members[i].cwd) },
                            onReply = { replyTo = members[i] },
                            pinned = members[i].name in pinned,
                            onPin = {
                                val n = members[i].name
                                pinned = if (n in pinned) pinned - n else pinned + n
                                Pinned.set(ctx, host.id, pinned)
                            },
                            onLongPress = { grouping = members[i] },
                            muted = members[i].name in muted,
                            faved = members[i].name in faved,
                        )
                        }
                    }
                }
            } else {
            listOf(SessionState.NeedsYou, SessionState.Working, SessionState.Done, SessionState.Idle)
                .forEach { st ->
                    val group = sessions.filter { it.state == st && it.name !in pinned }
                    if (group.isEmpty()) return@forEach
                    item(key = "h-${st.name}") { GroupHeader(st, group.size) }
                    items(group.size, key = { group[it].name }) { i ->
                        // 换组时滑过去而不是瞬移 —— 至少让用户看见「它动了」
                        SwipeCard(
                            faved = group[i].name in faved,
                            onAskKill = { killing = group[i] },
                            onToggleFav = {
                                val n = group[i].name
                                faved = if (n in faved) faved - n else faved + n
                                Favorites.set(ctx, host.id, faved)
                                if (n in faved) Favorites.remember(ctx, host.id, n, group[i].cwd)
                            },
                            modifier = Modifier.animateItem(),
                        ) {
                        SessionCard(
                            group[i],
                            // 点卡片 = 进对话；气泡按钮 = 不进对话直接回一句
                            onOpen = { onOpenChat(group[i].name, group[i].cwd) },
                            onReply = { replyTo = group[i] },
                            onPin = { pinned = pinned + group[i].name; Pinned.set(ctx, host.id, pinned) },
                            onLongPress = { grouping = group[i] },
                            muted = group[i].name in muted,
                            faved = group[i].name in faved,
                        )
                        }
                    }
                }
            }
            // ── 未启用：置顶过、但现在没在跑的 ──
            // ⚠️ 放在**最后**：它们不占注意力，只是「随时能拉回来」。
            // 放前面会让每天都看的活会话被一堆睡着的挤下去。
            val dormant = faved.filter { it !in byName }.sorted()
            if (dormant.isNotEmpty()) {
                item(key = "h-dormant") {
                    Row(
                        Modifier.padding(4.dp, 12.dp, 0.dp, 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.outline, Pill))
                        Text(t("未启用"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        Text(
                            "${dormant.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                items(dormant.size, key = { "dz-${dormant[it]}" }) { i ->
                    val n = dormant[i]
                    val cwd = Favorites.cwdOf(ctx, host.id, n)
                    DormantCard(
                        n, cwd,
                        onWake = {
                            val s0 = ssh ?: return@DormantCard
                            scope.launch {
                                status = t("在拉起 %s…").format(n.removePrefix("cc-"))
                                // ⚠️ 复用 ＋ 号那套：会建目录、而且**回头核对真的开在那儿**
                                //    （tmux 对不存在的目录会假装成功然后开在 $HOME，见 Dirs）
                                val made = app.yxi.ssh.catching {
                                    s0.exec(app.yxi.agent.Dirs.createCommand(cwd ?: "~", n))
                                }.map { app.yxi.agent.Dirs.madeFrom(it) }
                                    .getOrElse { app.yxi.agent.Dirs.Made.Failed("unknown", it.message.orEmpty()) }
                                if (made is app.yxi.agent.Dirs.Made.Failed) {
                                    status = t("拉不起来：%s").format(made.detail.ifBlank { made.code })
                                } else {
                                    status = ""
                                    runCatching { SessionProbe.snapshot(s0) }.onSuccess(onSessions)
                                    onOpenChat(n, cwd ?: ".")
                                }
                            }
                        },
                        onForget = {
                            faved = faved - n
                            Favorites.set(ctx, host.id, faved)
                        },
                    )
                }
            }
        }
        }
    }


    // 回它一句：不进对话，直接把这句送进那个 tmux 会话（send-keys）。
    replyTo?.let { target ->
        SendSheet(
            target = target,
            // 连接断了就算失败（exec 现在失败静默返回空，不抛，所以靠 isConnected 判成败）
            send = { msg ->
                val s = ssh
                if (s == null || !s.isConnected) Result.failure(RuntimeException(t("连接断了")))
                else runCatching { SessionProbe.send(s, target.name, msg); t("已送达") }
            },
            muted = target.name in muted,
            onToggleMute = {
                Mute.toggle(ctx, host.id, target.name)
                muted = Mute.get(ctx, host.id)
            },
            onDismiss = { replyTo = null },
        )
    }

    // 给某个会话选组。存回服务器后**给组里每个人发一句「你有队友了」** ——
    // 这一句就是「打通」发生的那一刻：不发的话，分组对 agent 而言根本不存在，
    // 它不会主动去读 groups.json，也就不知道自己能 yxi-hub say 谁。
    // 终止确认。⚠️ **杀会话 = 里面跑着的 Claude 一起没、没存的东西不会自己保存。**
    // 所以滑动只是把这个框弹出来，真正的决定在这儿。
    killing?.let { s0 ->
        val busy = s0.state == SessionState.Working || s0.state == SessionState.NeedsYou
        AlertDialog(
            onDismissRequest = { killing = null },
            title = { Text(t("终止 %s？").format(s0.name.removePrefix("cc-"))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t("会话连同里面跑着的 Claude 一起结束，占的内存放出来。转录文件留着，不会删。"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ⚠️ 正在干活/正在等你的，要**额外说一句** —— 这两种状态下杀掉最可能丢东西
                    if (busy) Text(
                        if (s0.state == SessionState.Working) t("⚠️ 它**正在干活**，现在杀会丢掉这一轮还没写完的东西。")
                        else t("⚠️ 它**正在等你回答**，杀掉这个问题就没了。"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (s0.name in faved) Text(
                        t("它是收藏的 —— 杀掉后会进「未启用」，随时点一下就能在原目录拉回来。"),
                        style = MaterialTheme.typography.labelSmall, color = Dim,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val target = s0.name
                    killing = null
                    val c = ssh ?: return@TextButton
                    scope.launch {
                        // 活着的时候记下 cwd，这样置顶的杀完还能原地拉回来
                        if (target in faved) Favorites.remember(ctx, host.id, target, s0.cwd)
                        if (SessionProbe.kill(c, target)) {
                            runCatching { SessionProbe.snapshot(c) }.onSuccess(onSessions)
                        } else status = t("终止失败 —— 会话可能已经没了")
                    }
                }) {
                    Text(t("终止"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ killing = null }) { Text(t("算了")) } },
        )
    }

    grouping?.let { target ->
        GroupPicker(
            session = target.name,
            table = groups,
            onDismiss = { grouping = null },
            onSave = { table, joined ->
                grouping = null
                groups = table                     // 先画出来，别让人等一趟 SSH
                val s = ssh ?: return@GroupPicker
                scope.launch {
                    runCatching {
                        app.yxi.agent.Groups.save(s, table)
                        if (joined != null) {
                            val n = app.yxi.agent.Groups.announce(s, table, joined)
                            if (n > 0) status = t("「%s」组已打通，通知了 %d 个").format(joined, n)
                        }
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        status = t("分组没存上：%s").format(it.message ?: "")
                    }
                }
            },
        )
    }

    if (newSession) {
        NewSessionDialog(
            ssh = ssh,
            // ⚠️ **传的是「已经开着会话的目录」，用来把它们从候选里剔掉** ——
            // 原来这个参数传的是同一批目录、却当成「推荐去处」列出来，正好反了：
            // 点进去只会跳回同一个会话，这个入口等于什么也没做。
            taken = sessions.map { it.cwd },
            onDismiss = { newSession = false },
            onCreate = { dir ->
                newSession = false
                val s = ssh ?: return@NewSessionDialog
                val base = dir.trimEnd('/').substringAfterLast('/').ifBlank { "work" }
                    .filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "work" }
                val full = "cc-$base"
                scope.launch {
                    // ⚠️ **不信 tmux 的退出码。** 目录不存在时它照样返回 0，然后开在 $HOME ——
                    // 见 Dirs.createCommand。这里只认它自己回报的那行结果，
                    // 开成了才跳进去；没开成就把原因摆在会话页顶上，别让人对着空会话猜。
                    val made = app.yxi.ssh.catching { s.exec(app.yxi.agent.Dirs.createCommand(dir, full)) }
                        .map { app.yxi.agent.Dirs.madeFrom(it) }
                        .getOrElse { app.yxi.agent.Dirs.Made.Failed("unknown", it.message.orEmpty()) }
                    when (made) {
                        is app.yxi.agent.Dirs.Made.Failed -> status = t("没开成：%s").format(
                            when (made.code) {
                                "nodir" -> t("建不了这个目录 —— 没权限，或者上级路径不对")
                                "failed" -> t("tmux 起不来这个会话")
                                "wrongdir" -> t("tmux 没开在你指定的目录（跑到 %s 去了），已经撤销")
                                    .format(made.detail.ifBlank { t("别处") })
                                "noresult" -> t("没拿到结果 —— 连接可能断了")
                                else -> made.detail.ifBlank { t("说不上来") }
                            },
                        )
                        else -> {
                            runCatching { SessionProbe.snapshot(s) }.onSuccess(onSessions)
                            onOpenChat(full, dir)
                        }
                    }
                }
            },
        )
    }

}

private fun dot(st: SessionState) = when (st) {
    SessionState.NeedsYou -> Color(0xFFFFC46B)   // 琥珀：全 app 只在需要你动手时出现
    SessionState.Working -> Color(0xFF8FD8C6)
    else -> Color(0xFF4A443D)
}

@Composable
private fun PinnedHeader(n: Int) {
    Row(
        Modifier.padding(4.dp, 12.dp, 0.dp, 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(Glyph.Pin, MaterialTheme.colorScheme.outline, 16.dp)
        Text(t("置顶"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(
            "$n",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun GroupHeader(st: SessionState, n: Int) {
    Row(
        Modifier.padding(4.dp, 12.dp, 0.dp, 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot(st), Pill))
        Text(
            st.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (st == SessionState.NeedsYou) dot(st) else MaterialTheme.colorScheme.outline,
        )
        Text(
            "$n",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * 置顶组 —— **长按拎起来、上下拖动改次序**。
 *
 * ⚠️ 为什么不塞进外面那个 `LazyColumn` 直接拖：跨 lazy item 拖动要自己算每项的
 * 屏幕位置、还要处理边缘自动滚动，很脆。置顶通常就三五个、固定在最上面，
 * 用一个**普通 Column** 装，拖动只在这几张卡之间发生，简单又稳。
 *
 * ⚠️ **轻点 = 进对话，长按 = 拖排序**。回复另有右侧气泡按钮，不占手势。
 * `detectDragGesturesAfterLongPress` 在长按后接管拖动；但 `clickable` **不认长按**——
 * 长按原地松手它照样当一次点击，所以拖动一起来就 `openEnabled=false` 掐掉它（见 #124）。
 *
 * 换位靠**累计位移 / 卡高**：拖过一张卡的高度就跟邻居换一次，边拖边换、松手落定。
 */
@Composable
private fun ReorderablePinned(
    tops: List<Session>,
    onOpen: (Session) -> Unit,
    onReply: (Session) -> Unit,
    onUnpin: (Session) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
) {
    // 拖动中：哪一张被拎着、当前累计的竖直位移
    var dragIndex by remember { mutableStateOf(-1) }
    var dragBy by remember { mutableFloatStateOf(0f) }
    // 卡高（含卡间距）——换位阈值。测量到就更新，测不到用个合理默认
    var slot by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // ⚠️ **拖动的手势协程活得比一次重组久**（`pointerInput(Unit)` 只启一次）。
    // 每换一次位，父层重组、tops 变成新列表 —— 但协程闭包捕获的是**旧的** tops/回调，
    // 于是第二次换位用的还是旧数据，拖再远也只动一格（实测踩过）。
    // `rememberUpdatedState` 让闭包每次都读到最新的，多格连拖才对。
    val curTops by rememberUpdatedState(tops)
    val curReorder by rememberUpdatedState(onReorder)

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        tops.forEachIndexed { i, sess ->
            val isDragged = i == dragIndex
            SessionCard(
                sess, pinned = true,
                dragging = isDragged,
                // 只要有一张被拎起来（dragIndex>=0），就把点击关掉 —— 免得松手误开对话
                openEnabled = dragIndex < 0,
                onOpen = { onOpen(sess) },
                onReply = { onReply(sess) },
                onPin = { onUnpin(sess) },
                modifier = Modifier
                    .onSizeChanged { with(density) { slot = it.height.toFloat() + 9.dp.toPx() } }
                    // 被拎起来的那张跟着手指走
                    .then(
                        if (isDragged)
                            Modifier.graphicsLayer {
                                translationY = dragBy
                                alpha = 0.92f
                            }
                        else Modifier
                    )
                    // ⚠️ key 用 `i` 不用 `tops.size`：换位不该重启手势（会打断拖动），
                    // 但每张卡要绑到自己那一格的 onDragStart。用 cur* 读最新数据，见上面注释。
                    .pointerInput(i) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragIndex = i; dragBy = 0f },
                            onDragEnd = { dragIndex = -1; dragBy = 0f },
                            onDragCancel = { dragIndex = -1; dragBy = 0f },
                        ) { change, delta ->
                            change.consume()
                            if (dragIndex >= 0 && slot > 0f) {
                                dragBy += delta.y
                                // 拖过一整格 → 跟那个方向的邻居换位。while 允许一次回调跨多格。
                                while (dragBy >= slot && dragIndex < curTops.lastIndex) {
                                    curReorder(dragIndex, dragIndex + 1)
                                    dragIndex += 1; dragBy -= slot
                                }
                                while (dragBy <= -slot && dragIndex > 0) {
                                    curReorder(dragIndex, dragIndex - 1)
                                    dragIndex -= 1; dragBy += slot
                                }
                            }
                        }
                    },
            )
        }
        Text(
            t("长按卡片拖动可以改置顶次序"),
            Modifier.padding(6.dp, 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SessionCard(
    s: Session,
    /** 点一下 = 进对话（在里面回它）。 */
    onOpen: () -> Unit,
    /** 图钉下面那个气泡按钮 = **不进对话直接回一句**（0.8.8 加回来，用户要求）。 */
    onReply: () -> Unit = {},
    pinned: Boolean = false,
    onPin: () -> Unit = {},
    /** 收藏了没。⚠️ 只画一颗星做标记，**不做成按钮** —— 它跟「点卡片进对话」抢同一块地方。 */
    faved: Boolean = false,
    muted: Boolean = false,
    /** 拖动排序时给卡片加一层「被拎起来」的样子（抬高 + 微微透明）。 */
    dragging: Boolean = false,
    /**
     * 轻点开对话能不能触发。⚠️ **拖动中要禁掉**：否则长按拎起来、没拖就松手，
     * `clickable` 照样会把它当一次点击 → 误开对话（实测踩过，用户报「长按不能排序」，
     * 其实是长按松手被当成点击开了对话）。拎起来的一刻就把点击关掉，就不会误触。
     */
    openEnabled: Boolean = true,
    /**
     * 长按 = 给它选组。
     * ⚠️ 置顶那块**不传这个**：那儿长按是拖动排序，两个手势会打架。
     */
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        // ⚠️ **长按不在这里绑发消息** —— 长按归「拖动排序」，由外面的 Modifier 接管。
        // 轻点 = 进对话；右侧气泡按钮 = 不进对话直接回一句。两条路各自独立。
        modifier = modifier.fillMaxWidth().combinedClickable(
            enabled = openEnabled, onClick = onOpen, onLongClick = onLongPress,
        ),
    ) {
        Row(
            Modifier.padding(16.dp, 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (faved) Text("★", style = MaterialTheme.typography.labelMedium, color = Copper)
                    Text(s.short, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    // 多久没动了 —— 一眼看出哪些会话是新鲜的、哪些搁置了
                    ago(s.lastActivity).takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (muted) {
                        Text(
                            t("🔕 静音"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (s.attached) {
                        Text(
                            t("已连"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (s.detail.isNotEmpty()) {
                    Text(
                        s.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                // ⚠️ **「卡了多久」比「多久没动」重要得多。**
                //
                // 20 个会话时真正的失败模式不是「看不过来」，是**有东西悄悄卡死了没人发现**。
                // 实测本机有会话卡在对话框上 8.4 天、另一个 4.6 天，完全没人管。
                //
                // ⚠️ 用 `stateTs`（状态**跃迁**的时刻）不是 `lastActivity`：
                // 后者是 tmux 活动/转录 mtime，会被无关的刷新带着走（#130 抱怨的就是它俩）。
                // 状态跃迁时刻才是「它从什么时候开始等你的」。
                if (s.state == SessionState.NeedsYou && s.stateTs > 0) {
                    val w = waited(s.stateTs.toLong())
                    if (w.isNotEmpty()) {
                        Text(
                            t("已经等了 %s").format(w),
                            style = MaterialTheme.typography.labelSmall,
                            // 等久了要显眼 —— 这正是最容易被漏掉的那一类
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    s.cwd,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            // 右侧竖排：图钉在上，回它一句在下（用户要「别针下面再加一个按钮」）。
            // ⚠️ 这两个 Box 各自 `clickable` 会**消费**掉点击，不会冒泡到 Surface 的 onOpen ——
            // 所以点图钉/点气泡都不会顺带把对话打开（跟图钉一直以来的行为一致）。
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 图钉一直在（不是只在置顶时才出现）—— 只在置顶时显示的话用户不知道有这功能。
                // ⚠️ **不用 emoji 📌**：各机型不一、粗细对不上、不跟主题变色。矢量图钉，置顶才上色。
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(
                            if (pinned) MaterialTheme.colorScheme.tertiaryContainer
                            else Color.Transparent
                        )
                        .clickable(onClick = onPin),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        Glyph.Pin,
                        if (pinned) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.outline,
                        18.dp,
                    )
                }
                // 回它一句：淡底 + primary 气泡，一眼看出「可点」。点它弹底部输入框，直接送键。
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = onReply),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Chat, MaterialTheme.colorScheme.primary, 18.dp)
                }
            }
        }
    }
}





/**
 * 「回它一句」底部输入框 —— 不进对话，直接把这句送进那个 tmux 会话。
 *
 * 0.8.5 曾把它连同卡片按钮一起撤掉（改成「点卡片进对话去回」）；0.8.8 按用户要求
 * 加回来，位置挪到图钉底下那个气泡按钮。区分：**轻点卡片 = 进对话细聊，气泡 = 甩一句就走**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(
    target: Session,
    /** 真去发，返回成/败。发送键 morph 成进度→成功✓/失败（[SubmitButton]）。 */
    send: suspend (String) -> Result<String>,
    muted: Boolean = false,
    onToggleMute: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("发给 %s").format(target.short), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                // 顺手静音/取消静音这个会话（长按卡片被排序占了，放这儿）
                Surface(
                    color = if (muted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = Pill, modifier = Modifier.clip(Pill).clickable(onClick = onToggleMute),
                ) {
                    Text(
                        if (muted) t("🔔 取消静音") else t("🔕 静音"),
                        Modifier.padding(14.dp, 7.dp), style = MaterialTheme.typography.labelLarge,
                        color = if (muted) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                t("不用先 attach —— 直接送进那个会话（tmux send-keys）。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            // 常用语：点一下填进下面的框，省掉在手机上打字
            SnippetChips(onPick = { text = it }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text(t("说一句…")) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
            if (text.isNotBlank()) {
                SubmitButton(
                    label = t("发送"),
                    modifier = Modifier.fillMaxWidth(),
                    successLabel = t("已送达"),
                    onSuccess = onDismiss,   // 成功打勾后自动收起
                    work = { send(text.trim()) },
                )
            } else {
                // 没字时是个灰的占位（点不动）
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(t("发送"), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/**
 * 从手机拉起一个新会话 —— 选个最近目录（或手打路径），在那儿新建 tmux 会话并跑起 claude。
 * 通勤路上想起「该让 X 项目跑个活」，不用等到电脑前。
 */
@Composable
private fun NewSessionDialog(
    ssh: SshSession?,
    /** 已经开着会话的目录 —— 这些**不出现在候选里** */
    taken: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<String>?>(null) }   // null = 还在找

    // 去服务器上问「工作区里还有哪些目录没开会话」。
    // ⚠️ 从现有会话的 cwd 反推父目录 —— 不写死 `/root/src/workspace`，
    // 换个客户、换台机器路径就不一样了。
    LaunchedEffect(ssh, taken) {
        val s0 = ssh
        val cmd = app.yxi.agent.Dirs.listCommand(app.yxi.agent.Dirs.parentsOf(taken))
        if (s0 == null || cmd == null) { dirs = emptyList(); return@LaunchedEffect }
        dirs = app.yxi.ssh.catching { s0.exec(cmd) }
            .map { app.yxi.agent.Dirs.candidates(it, taken) }
            .getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("新会话开在哪个目录")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    t("选一个还没开会话的目录，会在那儿开一个会话并把 claude 跑起来。"),
                    style = MaterialTheme.typography.labelSmall, color = Dim,
                )
                when {
                    dirs == null -> Text(
                        t("找目录中…"),
                        style = MaterialTheme.typography.labelMedium, color = Dim,
                    )
                    dirs!!.isEmpty() -> Text(
                        // ⚠️ 说清楚是「都开着了」还是「没找到」，别只给一句空
                        if (taken.isEmpty()) t("还没连上，或者那台机器上没有工作区目录 —— 下面直接填路径也行。")
                        else t("这些工作区目录都已经开着会话了 —— 下面直接填个新路径。"),
                        style = MaterialTheme.typography.labelMedium, color = Dim,
                    )
                    else -> Column(
                        Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        dirs!!.forEach { dir ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                                modifier = Modifier.fillMaxWidth().clip(Pill).clickable { onCreate(dir) },
                            ) {
                                Column(Modifier.padding(14.dp, 8.dp)) {
                                    Text(
                                        dir.substringAfterLast('/'),
                                        style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                    )
                                    Text(
                                        dir,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = Dim, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    path, { path = it }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    placeholder = { Text(t("或者直接填：/opt/workspace/…")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = path.isNotBlank(), onClick = { onCreate(path.trim()) }) { Text(t("开起来")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(t("取消")) } },
    )
}

/**
 * 看板的两种看法。
 *
 * ⚠️ 分组不是替代按状态看，是**换个轴**：按状态看回答「谁在等我」，
 * 按分组看回答「这摊活儿都谁在干」。所以是切换，不是取代。
 */
internal enum class BoardView { State, Group }

internal object Board {
    private fun p(ctx: android.content.Context) =
        ctx.getSharedPreferences("yxi", android.content.Context.MODE_PRIVATE)

    fun view(ctx: android.content.Context, hostId: String): BoardView =
        if (p(ctx).getString("boardview:$hostId", "") == "group") BoardView.Group else BoardView.State

    fun setView(ctx: android.content.Context, hostId: String, v: BoardView) =
        p(ctx).edit().putString("boardview:$hostId", if (v == BoardView.Group) "group" else "state").apply()

    /** 收起来的组名。⚠️ 存的是「收起的」不是「展开的」—— 新建的组默认展开。 */
    fun collapsed(ctx: android.content.Context, hostId: String): Set<String> =
        p(ctx).getStringSet("collapsed:$hostId", emptySet())!!.toSet()

    fun setCollapsed(ctx: android.content.Context, hostId: String, v: Set<String>) =
        p(ctx).edit().putStringSet("collapsed:$hostId", v).apply()
}

/** 分组模式下的组头：名字 + 几个 + 收起/展开箭头。整行可点 = 收起展开。 */
@Composable
internal fun GroupNameHeader(
    name: String,
    n: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onToggle)
            .padding(4.dp, 10.dp, 4.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ⚠️ 用旋转的三角不用两个字符（▸/▾）—— 后者在不同机型上宽度不一，
        // 会让整行标题左右跳。旋转是同一个字形，位置稳。
        val deg by animateFloatAsState(if (collapsed) 0f else 90f, label = "arrow")
        Text(
            "▸",
            Modifier.graphicsLayer { rotationZ = deg },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(
            "$n",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 「这个会话归哪几个组」。
 *
 * ⚠️ **多选**：一个会话可以同时在好几个组里（用户明确要的）——
 * 一个 agent 既在「后端」又在「上线」是常事，逼人二选一等于让分组没法用。
 */
@Composable
internal fun GroupPicker(
    session: String,
    table: app.yxi.agent.Groups.Table,
    onDismiss: () -> Unit,
    onSave: (app.yxi.agent.Groups.Table, String?) -> Unit,
) {
    var t by remember { mutableStateOf(table) }
    var fresh by remember { mutableStateOf("") }
    /// 这一轮新加进的组 —— 保存后要给组里的人发「你有队友了」，只发变动的那个
    var joined by remember { mutableStateOf<String?>(null) }
    val mine = t.of(session).toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("归到哪几个组")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    t("同一个组里的 agent 能互相发消息（yxi-hub）。一个会话可以同时在好几个组里。"),
                    style = MaterialTheme.typography.labelSmall, color = Dim,
                )
                if (t.groups.isNotEmpty()) Column(
                    Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    t.groups.keys.sorted().forEach { g ->
                        val on = g in mine
                        Surface(
                            color = if (on) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = Pill,
                            modifier = Modifier.fillMaxWidth().clip(Pill).clickable {
                                t = if (on) t.withoutMember(g, session) else t.withMember(g, session)
                                joined = if (on) null else g
                            },
                        ) {
                            Row(
                                Modifier.padding(14.dp, 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (on) "✓" else "  ", style = MaterialTheme.typography.labelLarge)
                                Text(g, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                Text(
                                    "${t.groups[g]?.size ?: 0}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Dim,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    fresh, { fresh = it }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    placeholder = { Text(t("新建一个组…")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fresh.isNotBlank()) Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = Pill,
                    modifier = Modifier.fillMaxWidth().clip(Pill).clickable {
                        t = t.withMember(fresh.trim(), session); joined = fresh.trim(); fresh = ""
                    },
                ) {
                    Text(
                        t("建「%s」并把它放进去").format(fresh.trim()),
                        Modifier.padding(14.dp, 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onSave(t, joined) }) { Text(t("存下")) } },
        dismissButton = { TextButton(onDismiss) { Text(t("取消")) } },
    )
}

/**
 * 会话卡片 + **左滑终止**。
 *
 * ⚠️ **滑到底不直接杀，只是把确认框弹出来。**
 * `confirmValueChange` 一律返回 false —— 卡片弹回原位，动作交给对话框。
 * 杀一个会话 = 里面跑着的 Claude 一起没、没存的东西不会自己保存，
 * 这种事不能由一个可能是误触的手势独自决定。
 */
@Composable
private fun SwipeCard(
    faved: Boolean,
    onAskKill: () -> Unit,
    onToggleFav: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                // ⚠️ **左滑不直接杀，只把确认框弹出来。** 返回 false 让卡片弹回原位。
                // 杀一个会话 = 里面跑着的 Claude 一起没，这种事不能由一个可能是误触的手势独自决定。
                SwipeToDismissBoxValue.EndToStart -> onAskKill()
                // 右滑收藏可以就地生效 —— 它只是个标记，点错了再滑一次就回来了。
                SwipeToDismissBoxValue.StartToEnd -> onToggleFav()
                else -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            // ⚠️ **背景要说清这一下会干什么**，而且左右不同色。
            // 「右滑收藏」这种手势没人猜得到，滑到一半看见字才知道 ——
            // 这是它唯一的发现途径。
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Surface(
                color = if (toStart) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    horizontalArrangement = if (toStart) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (toStart) t("终止") else if (faved) t("取消收藏") else t("收藏"),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (toStart) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
    ) { content() }
}

/**
 * 「未启用」—— 置顶过、但现在没在跑的会话。
 *
 * ⚠️ **这就是「收藏」**：置顶本来就把名字长期存着（会话被杀也不删），
 * 只是以前不显示。与其再造一个「收藏」的概念、再加一颗星星按钮，
 * 不如把已有的置顶补完：**置顶 = 这个会话对我重要 = 死了也记着，随时能拉回来**。
 */
@Composable
private fun DormantCard(name: String, cwd: String?, onWake: () -> Unit, onForget: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onWake),
    ) {
        Row(
            Modifier.padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name.removePrefix("cc-"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    // ⚠️ 没记住 cwd 的（老版本置顶的）要**说清楚**会开在 $HOME，
                    // 不然点下去开错地方，用户以为「唤起」坏了
                    cwd ?: t("不知道原来在哪个目录，会开在 ~"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Dim, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(onClick = onWake),
            ) {
                Text(
                    t("唤起"), Modifier.padding(14.dp, 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onForget),
                contentAlignment = Alignment.Center,
            ) { Text("✕", style = MaterialTheme.typography.labelMedium, color = Dim) }
        }
    }
}
