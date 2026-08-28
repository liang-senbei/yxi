package app.yxi.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    var floating by remember(host.id) { mutableStateOf(false) }
    var pinned by remember(host.id) { mutableStateOf(Pinned.get(ctx, host.id)) }
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
                runCatching { SessionProbe.snapshot(s) }
                    .onSuccess { onSessions(it); status = "" }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                    modifier = Modifier.height(44.dp).clip(Pill).clickable { floating = true },
                ) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(t("悬浮"), style = MaterialTheme.typography.labelLarge)
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
                    if (s != null) runCatching { SessionProbe.snapshot(s) }
                        .onSuccess { onSessions(it); status = "" }
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
            val tops = pinned.mapNotNull { byName[it] }
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
            listOf(SessionState.NeedsYou, SessionState.Working, SessionState.Done, SessionState.Idle)
                .forEach { st ->
                    val group = sessions.filter { it.state == st && it.name !in pinned }
                    if (group.isEmpty()) return@forEach
                    item(key = "h-${st.name}") { GroupHeader(st, group.size) }
                    items(group.size, key = { group[it].name }) { i ->
                        // 换组时滑过去而不是瞬移 —— 至少让用户看见「它动了」
                        SessionCard(
                            group[i],
                            modifier = Modifier.animateItem(),
                            // 点卡片 = 进对话；气泡按钮 = 不进对话直接回一句
                            onOpen = { onOpenChat(group[i].name, group[i].cwd) },
                            onReply = { replyTo = group[i] },
                            onPin = { pinned = pinned + group[i].name; Pinned.set(ctx, host.id, pinned) },
                            muted = group[i].name in muted,
                        )
                    }
                }
        }
        }
    }

    if (floating) {
        Switcher(
            ssh = ssh,
            current = null,
            hostId = host.id,
            onPick = { onOpenChat(it.name, it.cwd) },
            onDismiss = { floating = false },
        )
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

    if (newSession) {
        NewSessionDialog(
            recent = sessions.map { it.cwd }.distinct().take(8),
            onDismiss = { newSession = false },
            onCreate = { dir ->
                newSession = false
                val s = ssh ?: return@NewSessionDialog
                val base = dir.trimEnd('/').substringAfterLast('/').ifBlank { "work" }
                    .filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "work" }
                val full = "cc-$base"
                val d = dir.replace("'", "'\''")
                scope.launch {
                    // 有就直接开，没有就在那个目录新建并跑起 claude
                    runCatching {
                        s.exec("tmux has-session -t '$full' 2>/dev/null || { tmux new-session -d -s '$full' -c '$d'; tmux send-keys -t '$full' 'claude' Enter; }")
                    }
                    onOpenChat(full, dir)
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
    muted: Boolean = false,
    /** 拖动排序时给卡片加一层「被拎起来」的样子（抬高 + 微微透明）。 */
    dragging: Boolean = false,
    /**
     * 轻点开对话能不能触发。⚠️ **拖动中要禁掉**：否则长按拎起来、没拖就松手，
     * `clickable` 照样会把它当一次点击 → 误开对话（实测踩过，用户报「长按不能排序」，
     * 其实是长按松手被当成点击开了对话）。拎起来的一刻就把点击关掉，就不会误触。
     */
    openEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        // ⚠️ **长按不在这里绑发消息** —— 长按归「拖动排序」，由外面的 Modifier 接管。
        // 轻点 = 进对话；右侧气泡按钮 = 不进对话直接回一句。两条路各自独立。
        modifier = modifier.fillMaxWidth().clickable(enabled = openEnabled, onClick = onOpen),
    ) {
        Row(
            Modifier.padding(16.dp, 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun NewSessionDialog(recent: List<String>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("新会话开在哪个目录")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recent.isNotEmpty()) {
                    Text(t("最近"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    recent.forEach { dir ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                            modifier = Modifier.fillMaxWidth().clip(Pill).clickable { onCreate(dir) },
                        ) {
                            Text(
                                dir, Modifier.padding(14.dp, 8.dp),
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    path, { path = it }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    placeholder = { Text(t("/opt/workspace/…")) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = path.isNotBlank(), onClick = { onCreate(path.trim()) }) { Text(t("开起来")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(t("取消")) } },
    )
}
