package app.yxi.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var status by remember { mutableStateOf("连接中…") }
    var sendTo by remember { mutableStateOf<Session?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ⚠️ 探不到 ccusage 就一直是 null，界面上整块不出现（不显示 0、不显示「未知」）
    var usage by remember(host.id) { mutableStateOf<app.yxi.agent.Usage?>(null) }
    var sftp by remember(host.id) { mutableStateOf<app.yxi.ssh.Sftp?>(null) }
    var update by remember(host.id) { mutableStateOf<app.yxi.agent.Update?>(null) }
    // 列表 / 悬浮排列。⚠️ 两者**并存**不是替代 —— 悬浮好看但同屏信息量少三分之一，
    // 20 个会话的时候还是列表能一眼扫完（决策 D16b 里就写明了这个代价）
    var floating by remember(host.id) { mutableStateOf(false) }
    var pinned by remember(host.id) { mutableStateOf(Pinned.get(ctx, host.id)) }

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
                    .onSuccess { sessions = it; status = "" }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        status = "刷新失败：${it.message}"
                    }
            }
            delay(5_000)
        }
    }
    // 更新检查：连上之后看一眼就完事，不轮询
    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        val f = runCatching { s.openSftp() }.getOrNull() ?: return@LaunchedEffect
        sftp = f
        update = app.yxi.agent.Update.check(f, app.yxi.BuildConfig.VERSION_CODE)
    }

    // 用量单独一条慢节奏 —— 它 5 小时才变一格，没必要跟着 5 秒刷
    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        while (true) {
            app.yxi.agent.Usage.probe(s)?.let { usage = it; UsageCache.put(ctx, host.id, it) }
            delay(120_000)
        }
    }
    DisposableEffect(host.id) { onDispose { sftp?.close(); ssh?.disconnect() } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            var hostMenu by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.clickable(enabled = hosts.size > 1) { hostMenu = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                    if (status.isEmpty()) "${sessions.size} 个会话 · 点读对话 · 长按发消息" else status,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,   // 窄屏上会折成两行把下面顶下去
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                    modifier = Modifier.height(44.dp).clickable { floating = true },
                ) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("悬浮", style = MaterialTheme.typography.labelLarge)
                    }
                }
                listOf("文件" to onOpenFiles, "终端" to { onOpenTerminal(null, ".") }).forEach { (label, go) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                        modifier = Modifier.height(44.dp).clickable(onClick = go),
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
                UpdateBanner(sftp, it) { update = null }
            }
        }
        usage?.let {
            Box(Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp)) { UsageCard(it) }
        }

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
                    Surface(
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.12f),
                        shape = Pill,
                        modifier = Modifier.clickable(onClick = onRetry),
                    ) {
                        Text(
                            "重连",
                            Modifier.padding(16.dp, 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // ⚠️ 置顶的**从原来的组里拿出来**单独放最上面。留在原组只加个图标的话，
            // 会话一多（实测 22 个）照样要翻半天才找到 —— 那就等于没置顶
            val tops = sessions.filter { it.name in pinned }
            if (tops.isNotEmpty()) {
                item(key = "h-pinned") { PinnedHeader(tops.size) }
                items(tops.size, key = { "p-" + tops[it].name }) { i ->
                    SessionCard(
                        tops[i], pinned = true,
                        modifier = Modifier.animateItem(),
                        onOpen = { onOpenChat(tops[i].name, tops[i].cwd) },
                        onTerminal = { onOpenTerminal(tops[i].name, tops[i].cwd) },
                        onSend = { sendTo = tops[i] },
                        onPin = { pinned = pinned - tops[i].name; Pinned.set(ctx, host.id, pinned) },
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
                            // 点卡片 = 对话模式（主界面）；「开终端」按钮才去终端
                            onOpen = { onOpenChat(group[i].name, group[i].cwd) },
                            onTerminal = { onOpenTerminal(group[i].name, group[i].cwd) },
                            onSend = { sendTo = group[i] },
                            onPin = { pinned = pinned + group[i].name; Pinned.set(ctx, host.id, pinned) },
                        )
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

    sendTo?.let { target ->
        SendSheet(target, onSend = { text ->
            scope.launch { ssh?.let { SessionProbe.send(it, target.name, text) } }
            sendTo = null
        }, onDismiss = { sendTo = null })
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
        Text("📌", style = MaterialTheme.typography.labelMedium)
        Text("置顶", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
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
@Composable
private fun SessionCard(
    s: Session,
    onOpen: () -> Unit,
    onTerminal: () -> Unit,
    onSend: () -> Unit,
    pinned: Boolean = false,
    onPin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val needs = s.state == SessionState.NeedsYou
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        // 点=开终端，**长按=发消息**。发消息对任意会话都可用，
        // 不只是「等你」那组——只是那组把按钮摆出来了而已
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onSend),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.short, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // 图钉一直在（不是只在置顶时才出现）—— 只在置顶时显示的话，
                // 用户根本不知道有这个功能
                Surface(
                    color = if (pinned) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = Pill,
                    modifier = Modifier.padding(end = 8.dp).clickable(onClick = onPin),
                ) {
                    Text(
                        "📌",
                        Modifier.padding(10.dp, 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pinned) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.outline,
                    )
                }
                if (s.attached) {
                    Text(
                        "已连",
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
            // 只有「等你」那组带按钮 —— 其余安静
            if (needs) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onSend, shape = Pill, modifier = Modifier.weight(1f).height(44.dp)) { Text("回它一句") }
                    OutlinedButton(onTerminal, shape = Pill, modifier = Modifier.weight(1f).height(44.dp)) { Text("开终端") }
                }
            }
        }
    }
}

/** 不进终端就能给任意会话发一句话 —— 这是我们比 Moshi 强的地方（PRD §1.6）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(target: Session, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(18.dp, 0.dp, 18.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("发给 ${target.short}", style = MaterialTheme.typography.titleLarge)
            Text(
                "不用先 attach —— 直接送进那个会话（tmux send-keys）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text("说一句…") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
            Button(
                { if (text.isNotBlank()) onSend(text.trim()) },
                enabled = text.isNotBlank(),
                shape = Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("发送") }
        }
    }
}

/**
 * 置顶的会话名。**按主机分开存** —— 换台机器同名会话未必是同一件事。
 *
 * ⚠️ 只存在手机本地，不写进服务器。置顶是「我关心哪几个」，
 * 是这台手机的偏好，不是那台机器的状态 —— 写过去会污染别人的视图。
 */
private object Pinned {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    fun get(ctx: Context, hostId: String): Set<String> =
        p(ctx).getStringSet("pinned:$hostId", emptySet()) ?: emptySet()
    fun set(ctx: Context, hostId: String, v: Set<String>) =
        p(ctx).edit().putStringSet("pinned:$hostId", v).apply()
}
