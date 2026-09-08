package app.yxi.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * 左栏：主机分组 → 该主机上的 cc-* 会话行（Codex 的「项目 → 线程」）。
 * 主机没连也列着（灰点）；点分组头 = 连上（可以同时连几台，都进 `state.conns`）；右键 / ⋯ 出菜单。
 * 刷新和重连的循环在 [Conn] 自己身上，不在这里 —— 侧栏收起时这个 Composable 整个不在组合里，挂这儿的循环会停。
 * 宽度由外面定（288dp），这里只管内容。
 */
@Composable
fun Sidebar(state: AppState, modifier: Modifier = Modifier) {
    val t = Tokens.current
    var hosts by remember { mutableStateOf(Store.hosts()) }
    var editing by remember { mutableStateOf<Host?>(null) }
    var coloring by remember { mutableStateOf<Host?>(null) }
    var deleting by remember { mutableStateOf<Host?>(null) }
    var creatingOn by remember { mutableStateOf<Conn?>(null) }
    var note by remember { mutableStateOf("") }       // 不属于某条连接的错（Conn 都没建出来）
    val keys = remember { FileHostKeys() }

    fun connOf(h: Host) = state.conns.firstOrNull { it.host.id == h.id }
    fun save(list: List<Host>) { hosts = list; Store.save(list) }
    fun disconnect(h: Host) {
        // 这台的指纹弹窗还挂着就按「取消」答掉，不然 jsch 的 connect 线程会一直等到超时
        keys.pending?.takeIf { it.host == keys.jschHost(h) }?.answer?.complete(false)
        val c = connOf(h) ?: return
        c.close(); state.conns.remove(c)
        if (state.conn === c) { state.conn = null; state.session = null }
    }
    fun connect(h: Host) {
        disconnect(h); note = ""
        // 私钥文件没了会在 Conn 构造时就炸（toConfig 读文件），不算连接错误
        val c = runCatching { Conn(h, keys) }.getOrElse { note = "连不了 ${h.label}：${it.message}"; return }
        // conns 的顺序 = 侧栏顺序（Ctrl+Tab / Ctrl+1…9 按它摊平）
        val order = hosts.map { it.id }
        val at = state.conns.indexOfFirst { order.indexOf(it.host.id) > order.indexOf(h.id) }
        state.conns.add(if (at < 0) state.conns.size else at, c)
        c.start()
        if (state.conn == null) state.conn = c
    }

    // Ctrl+N：对当前主机弹「新建会话」。seen 放 AppState 而不是 remember：
    // Sidebar 收起时 remember 会丢，重新展开时初始化成当前值 == newSessionRequest → 弹窗不弹
    LaunchedEffect(state.newSessionRequest) {
        if (state.newSessionRequest == state.newSessionSeen) return@LaunchedEffect
        state.newSessionSeen = state.newSessionRequest
        creatingOn = state.conn?.takeIf { it.status == Conn.Status.Connected } ?: state.conns.firstOrNull { it.status == Conn.Status.Connected }
        if (creatingOn == null) note = "先连上一台主机，再新建会话"
    }

    Column(modifier.background(t.surface1)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("主机", style = MaterialTheme.typography.titleMedium, color = t.textPrimary, modifier = Modifier.weight(1f))
            IconButton({ editing = Host(id = UUID.randomUUID().toString(), alias = "", hostname = "", keyPath = defaultKey()) }, Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "加主机", Modifier.size(18.dp), tint = t.textSecondary)
            }
        }
        if (note.isNotBlank()) Text(note, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (hosts.isEmpty()) Text("尚未添加设备，点 + 加一台", Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            hosts.forEachIndexed { i, h ->
                val c = connOf(h)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(h.tint(i)))   // 连接颜色条，整组都带着
                    Column(Modifier.weight(1f)) {
                        HostHeader(
                            h, c, current = c != null && state.conn === c,
                            // 没连 / 连失败了：点一下就连；连着的分组头点了不动（会话行才是入口）
                            onClick = { if (c == null || c.status == Conn.Status.Failed) connect(h) },
                            onConnect = { connect(h) }, onDisconnect = { disconnect(h) }, onNew = { creatingOn = c },
                            onEdit = { editing = h }, onColor = { coloring = h }, onDelete = { deleting = h },
                        )
                        if (c?.status == Conn.Status.Failed) {
                            Text(c.error, Modifier.padding(start = 24.dp, end = 10.dp, bottom = 4.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
                            // 删旧指纹是单独一步；删完立刻重连，新指纹会再弹一次「确认这是 X 吗」让用户核对
                            if (c.keyChanged) TextButton({ keys.forget(h); connect(h) }, Modifier.padding(start = 12.dp)) { Text("我确认过了，删除旧指纹") }
                        }
                        // 断线重连中列表照旧摆着（服务器上的会话还在），不清
                        c?.sessions?.forEach { s ->
                            SessionRow(s, selected = state.conn === c && state.session?.name == s.name) { state.select(c, s) }
                        }
                        if (c?.status == Conn.Status.Connected && c.sessions.isEmpty())
                            Text("这台机器上还没有会话", Modifier.padding(start = 24.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                }
            }
        }
    }

    editing?.let { h ->
        HostForm(h, isNew = hosts.none { it.id == h.id }, onSave = { n ->
            save(if (hosts.any { it.id == n.id }) hosts.map { if (it.id == n.id) n else it } else hosts + n)
            // 地址 / 认证改了，连着的那条作废重连；只改名字或颜色不用动
            if (connOf(h) != null && n.copy(alias = h.alias, color = h.color) != h) connect(n)
            editing = null
        }, onClose = { editing = null })
    }
    coloring?.let { h ->
        ColorDialog(h.tint(hosts.indexOf(h)), onPick = { col -> save(hosts.map { if (it.id == h.id) it.copy(color = col) else it }); coloring = null }, onClose = { coloring = null })
    }
    deleting?.let { h ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${h.label}？") },
            text = { Text("只删这里的记录和指纹，服务器上的会话不受影响。") },
            confirmButton = { TextButton({ disconnect(h); keys.forget(h); save(hosts.filter { it.id != h.id }); deleting = null }) { Text("删除", color = t.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    creatingOn?.let { c -> NewSessionDialog(c, onDismiss = { creatingOn = null }) { s -> creatingOn = null; state.select(c, s) } }
    keys.pending?.let { p -> FingerprintDialog(p, alias = hosts.firstOrNull { keys.jschHost(it) == p.host }?.label ?: p.host) }
}

/** 分组头：状态点 + 别名 + 状态文字 + ⋯ 菜单（右键也出）。当前主机底色 selected，悬停 hover。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostHeader(
    h: Host, c: Conn?, current: Boolean, onClick: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onNew: () -> Unit, onEdit: () -> Unit, onColor: () -> Unit, onDelete: () -> Unit,
) {
    val t = Tokens.current
    var menu by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val st = c?.status ?: Conn.Status.Idle
    Row(
        Modifier.fillMaxWidth().hoverable(src)
            .background(if (current) t.selected else if (hovered) t.hover else Color.Transparent)
            // 左右键分开用 onClick 配 matcher：clickable 不认按键，右键会连菜单带「连接」一起触发
            .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { menu = true }
            .onClick(onClick = onClick)
            .padding(start = 9.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(st)
        Text(
            h.label, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (st.label.isNotEmpty()) Text(st.label, style = MaterialTheme.typography.labelSmall, color = if (st == Conn.Status.Failed) t.danger else t.textMuted)
        Box {
            IconButton({ menu = true }, Modifier.size(24.dp)) { Icon(Icons.Default.MoreHoriz, "菜单", Modifier.size(16.dp), tint = t.textSecondary) }
            DropdownMenu(menu, { menu = false }) {
                @Composable fun item(label: String, enabled: Boolean = true, act: () -> Unit) =
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; act() }, enabled = enabled)
                if (c == null || st == Conn.Status.Failed) item("连接", act = onConnect) else item("断开", act = onDisconnect)
                item("新建会话", enabled = st == Conn.Status.Connected, act = onNew)
                item("编辑", act = onEdit)
                item("连接颜色…", act = onColor)
                item("删除", act = onDelete)
            }
        }
    }
}

/** 状态点：未连灰 / 连接中黄 / 已连绿 / 重连中黄闪 / 失败红。 */
@Composable
private fun StatusDot(st: Conn.Status) {
    val t = Tokens.current
    val color = when (st) {
        Conn.Status.Connected -> t.success
        Conn.Status.Connecting, Conn.Status.Reconnecting -> t.warning
        Conn.Status.Failed -> t.danger
        Conn.Status.Idle -> t.textMuted.copy(alpha = 0.5f)
    }
    val alpha = if (st == Conn.Status.Reconnecting)
        rememberInfiniteTransition().animateFloat(1f, 0.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse)).value
    else 1f
    Box(Modifier.size(8.dp).background(color.copy(alpha = color.alpha * alpha), CircleShape))
}
