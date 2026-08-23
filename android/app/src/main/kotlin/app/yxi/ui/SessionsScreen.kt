package app.yxi.ui

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
    /** (会话名, cwd)。⚠️ **cwd 必须一起传** —— 对话模式靠它找转录文件 */
    onOpenTerminal: (String?, String) -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var status by remember { mutableStateOf("连接中…") }
    var ssh by remember { mutableStateOf<SshSession?>(null) }
    var sendTo by remember { mutableStateOf<Session?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val connect = rememberSshConnector(store, keys, host)

    LaunchedEffect(host.id) {
        val c = connect()
        if (c == null) { status = "这台主机还没有可用的认证方式"; return@LaunchedEffect }
        val s = c.session
        runCatching { s.connect(); ssh = s }.onFailure {
            status = c.explain(it)
            return@LaunchedEffect
        }
        // 每 5 秒刷一次。一次往返拿全部，不是一个会话一个请求
        while (true) {
            // ⚠️ **手指在列表上的时候不要刷。** 会话换组（干活中 → 等你）会让下面的卡片整体上移，
            // 而刷新和点击之间只有几十毫秒 —— 我自己就因此点进过别人的会话。
            // 用户看到的位置和点下去的位置必须是同一个。
            if (!listState.isScrollInProgress) {
                runCatching { SessionProbe.snapshot(s) }
                    .onSuccess { sessions = it; status = "" }
                    .onFailure { status = "刷新失败：${it.message}" }
            }
            delay(5_000)
        }
    }
    DisposableEffect(host.id) { onDispose { ssh?.disconnect() } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(host.alias, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (status.isEmpty()) "${sessions.size} 个会话 · 点读对话 · 长按发消息" else status,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,   // 窄屏上会折成两行把下面顶下去
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            listOf(SessionState.NeedsYou, SessionState.Working, SessionState.Done, SessionState.Idle)
                .forEach { st ->
                    val group = sessions.filter { it.state == st }
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
                        )
                    }
                }
        }
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
