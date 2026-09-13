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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import app.yxi.agent.AccountApi
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
    var installingKey by remember { mutableStateOf<Host?>(null) }
    var note by remember { mutableStateOf("") }       // 不属于某条连接的错（Conn 都没建出来）
    var importing by remember { mutableStateOf<HostImportPlan?>(null) }
    var pickingTransfer by remember { mutableStateOf(false) }
    importing?.let { plan -> HostImportDialog(plan, { importing = null }) {
        val merged = plan.merge(hosts)
        Store.save(merged)
        hosts = merged; importing = null
        note = "已导入 ${plan.additions.size} 台服务器，请编辑认证设置后连接。"
    } }
    var hostMenu by remember { mutableStateOf(false) }
    var recoveringHosts by remember { mutableStateOf(false) }
    if (recoveringHosts) HostRecoveryDialog({ recoveringHosts = false }) { copy ->
        check(state.conns.isEmpty()) { "请先断开现有连接再恢复服务器列表" }
        hosts = Store.recoverHosts(copy)
        recoveringHosts = false
    }
    NativeOverlay(hostMenu || pickingTransfer)
    val keys = remember { FileHostKeys() }
    // 账号行的资料：进来就拉一次（幂等；Me 页里还会再拉）。侧栏收起再展开会重跑，无害
    LaunchedEffect(Unit) { MeAuth.load() }

    fun connOf(h: Host) = state.conns.firstOrNull { it.host.id == h.id }
    fun save(list: List<Host>) { Store.save(list); hosts = list }
    fun safely(action: () -> Unit) { runCatching(action).onFailure { note = "操作未保存：${it.message}" } }
    fun disconnect(h: Host) {
        // 这台的指纹弹窗还挂着就按「取消」答掉，不然 jsch 的 connect 线程会一直等到超时
        keys.pending?.takeIf { it.host == keys.jschHost(h) }?.answer?.complete(false)
        val c = connOf(h) ?: return
        c.close(); state.conns.remove(c)
        if (state.conn === c) { state.conn = null; state.session = null }
    }
    fun connect(h: Host) {
        if (h.keyPath.isBlank() && h.password.isBlank()) {
            editing = h; note = "请先填写这台服务器的认证信息，再连接。"; return
        }
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

    LaunchedEffect(Unit) {
        if (!state.startupRestored) {
            state.startupRestored = true
            if (hosts.none { it.id == state.hostScope }) state.scopeHost("")
            if (Store.pref("reconnectOnStart", "1") == "1") {
                hosts.firstOrNull { it.id == Store.pref("lastHost", "") }?.let { h ->
                    val previousSession = Store.pref("lastSession", "")
                    connect(h)
                    state.restoreSession = previousSession.takeIf { it.isNotEmpty() }
                    state.restoreRuntime = Store.pref("lastRuntime", "").takeIf { it.isNotBlank() }
                }
            }
        }
    }
    LaunchedEffect(state.conn, state.conn?.sessions, state.restoreSession) {
        val c = state.conn
        val wanted = state.restoreSession
        if (c != null && wanted != null) c.sessions.firstOrNull { if (state.restoreRuntime != null) it.runtimeId == state.restoreRuntime else it.name == wanted }?.let { state.select(c, it) }
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
        UpdateBanner()   // 有新版时才画（Codex 把更新横幅放侧栏顶部）
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton({ hostMenu = true }) {
                    Text((hosts.firstOrNull { it.id == state.hostScope }?.label ?: "所有主机") + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(hostMenu, { hostMenu = false }) {
                    DropdownMenuItem(text = { Text("所有主机") }, onClick = { state.scopeHost(""); hostMenu = false })
                    hosts.forEach { h ->
                        DropdownMenuItem(text = { Text(h.label + (if (h.region.isBlank()) "" else " · ${h.region}") + " · " + (connOf(h)?.status?.label?.ifBlank { "未连接" } ?: "未连接")) }, onClick = {
                            hostMenu = false; state.scopeHost(h.id)
                            if (connOf(h) == null || connOf(h)?.status == Conn.Status.Failed) connect(h)
                            connOf(h)?.let { c -> if (state.conn !== c) state.select(c, null) }
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("导入服务器…") }, onClick = {
                        hostMenu = false; pickingTransfer = true
                        try { chooseHostTransferFile(false)?.let { file ->
                            check(file.length() <= 4 * 1024 * 1024) { "导入文件最多4MiB" }
                            importing = HostTransfer.preview(file.readText(), hosts)
                        } } catch (e: Exception) { note = e.message ?: "导入文件无法读取" }
                        finally { pickingTransfer = false }
                    })
                    DropdownMenuItem(text = { Text("导出服务器（不含认证）…") }, enabled = hosts.isNotEmpty(), onClick = {
                        hostMenu = false; pickingTransfer = true
                        try { chooseHostTransferFile(true)?.let { file ->
                            val target = file.canonicalFile
                            check(!target.toPath().startsWith(Store.dir.canonicalFile.toPath())) { "请选择应用配置目录之外的位置" }
                            check(hosts.none { it.keyPath.isNotBlank() && java.io.File(it.keyPath).canonicalFile == target }) { "不能覆盖现有私钥文件" }
                            DurableFile.replace(target, HostTransfer.export(hosts))
                            note = "已导出服务器地址，未包含密码或私钥路径。"
                        } } catch (e: Exception) { note = e.message ?: "导出失败" }
                        finally { pickingTransfer = false }
                    })
                }
            }
            IconButton({ editing = Host(id = UUID.randomUUID().toString(), alias = "", hostname = "", keyPath = defaultKey()) }, Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "加主机", Modifier.size(18.dp), tint = t.textSecondary)
            }
        }
        if (note.isNotBlank()) Text(note, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (Store.warning.isNotBlank()) Text(Store.warning, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (Store.hostRecoveryNeeded()) TextButton({ recoveringHosts = true }, enabled = state.conns.isEmpty()) { Text(if (state.conns.isEmpty()) "从受保护副本恢复服务器" else "恢复前请先断开连接") }
        TextButton({
            val c = if (state.hostScope.isEmpty()) state.conn else state.conns.firstOrNull { it.host.id == state.hostScope }
            if (c?.status == Conn.Status.Connected) creatingOn = c else note = "先选择并连接要运行 Agent 的主机"
        }, Modifier.fillMaxWidth()) { Text("＋ 新对话") }
        TextButton({ state.showTaskSwitcher = true }, Modifier.fillMaxWidth()) {
            Text("切换任务", Modifier.weight(1f))
            Text("Ctrl+K", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        }
        // 会话过滤（ZCode 的搜索框 / Codex 的过滤）：按会话名 / 主机名滤，主机全不匹配就整组藏掉
        var filter by remember { mutableStateOf("") }
        if (hosts.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(10.dp, 2.dp, 10.dp, 6.dp).clip(RoundedCornerShape(8.dp)).background(t.border),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, "搜索", Modifier.padding(start = 8.dp).size(13.dp), tint = t.textMuted)
            BasicTextField(
                filter, { filter = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = t.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
                modifier = Modifier.fillMaxWidth().padding(8.dp, 6.dp),
                decorationBox = { inner -> Box { if (filter.isEmpty()) Text("搜索会话", style = MaterialTheme.typography.bodySmall, color = t.textMuted); inner() } },
            )
        }
        if (hosts.isEmpty()) Text("尚未添加设备，点 + 加一台", Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (hosts.isNotEmpty()) WorkbenchTabs(listOf("全部", "待处理", "归档"), state.navigation.mode, { state.navigation.setMode(it) }, Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        if (state.navigation.error.isNotBlank()) Text(state.navigation.error, Modifier.padding(10.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val f = filter.trim()
            hosts.forEachIndexed { i, h ->
                if (state.hostScope.isNotEmpty() && state.hostScope != h.id) return@forEachIndexed
                val c = connOf(h)
                val sessions = c?.sessions?.filter { f.isEmpty() || h.label.contains(f, true) || h.region.contains(f, true) || it.short.contains(f, true) || it.name.contains(f, true) || it.cwd.contains(f, true) || state.navigation.title(taskNavigationKey(h, it))?.contains(f, true) == true } ?: emptyList()
                val hostMatch = f.isEmpty() || h.label.contains(f, ignoreCase = true) || h.region.contains(f, ignoreCase = true)
                if (!hostMatch && sessions.isEmpty()) return@forEachIndexed   // 滤空的整组不画，省得滚动列表里全是空组
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(h.tint(i)))   // 连接颜色条，整组都带着
                    Column(Modifier.weight(1f)) {
                        HostHeader(
                            h, c, current = c != null && state.conn === c,
                            // 没连 / 连失败了：点一下就连；连着的分组头点了不动（会话行才是入口）
                            onClick = { if (c == null || c.status == Conn.Status.Failed) connect(h) },
                            onConnect = { connect(h) }, onDisconnect = { disconnect(h) }, onNew = { creatingOn = c },
                            onEdit = { editing = h }, onColor = { coloring = h }, onDelete = { deleting = h },
                            onInstallKey = { installingKey = h },
                        )
                        if (c?.status == Conn.Status.Failed) {
                            Text(c.error, Modifier.padding(start = 24.dp, end = 10.dp, bottom = 4.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
                            // 删旧指纹是单独一步；删完立刻重连，新指纹会再弹一次「确认这是 X 吗」让用户核对
                            if (c.keyChanged) TextButton({ keys.forget(h); connect(h) }, Modifier.padding(start = 12.dp)) { Text("我确认过了，删除旧指纹") }
                        }
                        // 用量紧凑条（PRD P0-13）：连着才有数据，ccusage 探不到就整块不画
                        if (c?.status == Conn.Status.Connected) UsageStrip(c)
                        // 断线重连中列表照旧摆着（服务器上的会话还在），不清；搜索时只画滤剩下的
                        if (c != null) {
                            ProjectTree(state, c, sessions, searching = f.isNotEmpty())
                            if (f.isEmpty() && c.status == Conn.Status.Connected && c.sessions.isEmpty())
                                Text("这台机器上还没有会话", Modifier.padding(start = 24.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                        }
                    }
                }
            }
        }
        BottomNav(state)
    }

    editing?.let { h ->
        HostForm(h, isNew = hosts.none { it.id == h.id }, onSave = { n ->
            save(if (hosts.any { it.id == n.id }) hosts.map { if (it.id == n.id) n else it } else hosts + n)
            // 地址 / 认证改了，连着的那条作废重连；只改名字或颜色不用动
            if (connOf(h) != null && n.copy(alias = h.alias, color = h.color, region = h.region) != h) connect(n)
            editing = null
        }, onClose = { editing = null })
    }
    coloring?.let { h ->
        ColorDialog(h.tint(hosts.indexOf(h)), onPick = { col -> safely { save(hosts.map { if (it.id == h.id) it.copy(color = col) else it }); coloring = null } }, onClose = { coloring = null })
    }
    deleting?.let { h ->
        WorkbenchDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${h.label}？") },
            text = { Text("只删这里的记录和指纹，服务器上的会话不受影响。") },
            confirmButton = { TextButton({ safely { save(hosts.filter { it.id != h.id }); disconnect(h); keys.forget(h); if (state.hostScope == h.id) state.scopeHost(""); deleting = null } }) { Text("删除", color = t.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    creatingOn?.let { c -> NewSessionDialog(c, onDismiss = { creatingOn = null }) { s ->
        val key = taskNavigationKey(c.host, s)
        if (state.navigation.title(key) == null) state.navigation.rename(key, "新对话 · " + if (s.isCodex) "Codex" else "Claude Code")
        creatingOn = null
        state.select(c, s)
    } }
    installingKey?.let { h ->
        CopyIdDialog(h, keys, onSaved = { n ->
            save(hosts.map { if (it.id == n.id) n else it })
            if (connOf(h) != null) connect(n)   // 装成了就切到密钥重连（密码还留着当后备）
        }, onClose = { installingKey = null })
    }
    keys.pending?.let { p -> FingerprintDialog(p, alias = hosts.firstOrNull { keys.jschHost(it) == p.host }?.label ?: p.host) }
}

/** 分组头：状态点 + 别名 + 状态文字 + ⋯ 菜单（右键也出）。当前主机底色 selected，悬停 hover。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostHeader(
    h: Host, c: Conn?, current: Boolean, onClick: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onNew: () -> Unit, onEdit: () -> Unit, onColor: () -> Unit, onDelete: () -> Unit,
    onInstallKey: () -> Unit,
) {
    val t = Tokens.current
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
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
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(h.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.region.isNotBlank()) Text(h.region, style = MaterialTheme.typography.labelSmall, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (st.label.isNotEmpty()) Text(st.label, style = MaterialTheme.typography.labelSmall, color = if (st == Conn.Status.Failed) t.danger else t.textMuted)
        Box {
            IconButton({ menu = true }, Modifier.size(24.dp)) { Icon(Icons.Default.MoreHoriz, "菜单", Modifier.size(16.dp), tint = t.textSecondary) }
            DropdownMenu(menu, { menu = false }) {
                @Composable fun item(label: String, enabled: Boolean = true, act: () -> Unit) =
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; act() }, enabled = enabled)
                if (c == null || st == Conn.Status.Failed) item("连接", act = onConnect) else item("断开", act = onDisconnect)
                item("新建会话", enabled = st == Conn.Status.Connected, act = onNew)
                item("装公钥免密…", act = onInstallKey)
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

/**
 * 左栏底部的整页入口 —— 对齐手机底部四栏里剩下的那两个（老板 09-08：手机导航栏的功能桌面版都要有）。
 *
 * ⚠️ 「会话」和「主机」不在这儿：桌面上它们**就是上面那片侧栏本身**（主机分组 → 会话行），
 * 再放两个按钮进来等于让人点一下才看见本来就摆在眼前的东西。
 * 所以这里只有「配置」「我的」——它们在手机上是整屏，在这儿也该是整页（[Page]）。
 */
@Composable
private fun BottomNav(state: AppState) {
    val t = Tokens.current
    HorizontalDivider(color = t.border)
    Row(Modifier.fillMaxWidth().padding(6.dp, 4.dp)) {
        NavItem(Icons.Default.Tune, "配置", state.page == Page.Config, Modifier.weight(1f)) {
            // 再点一次回工作区 —— 整页入口没有「返回」按钮，点亮的那个自己就是开关
            state.page = if (state.page == Page.Config) Page.Workspace else Page.Config
        }
        NavItem(Icons.Default.Person, "我的", state.page == Page.Me, Modifier.weight(1f)) {
            state.page = if (state.page == Page.Me) Page.Workspace else Page.Me
        }
    }
    AccountRow(state)
}

/**
 * 左下角的账号行（Codex 同款，老板 09-12 截图）：字母头像 + 名字 + 会员档 + 版本号。
 * 点开菜单：版本（信息行）/ 使用情况 / 配置 / 设置 / 退出登录。头像用首字母，不异步拉网络图（完整资料在「我的」页）。
 */
@Composable
private fun AccountRow(state: AppState) {
    val t = Tokens.current
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
    val me = MeAuth.me
    val signedIn = MeAuth.signedIn
    val name = if (signedIn) me?.nickname?.ifBlank { null } ?: "Yxi 用户" else "未登录"
    val tier = me?.let {
        when (it.tier) { AccountApi.Tier.Ultra -> "Ultra"; AccountApi.Tier.Pro -> "Pro"; else -> "免费" }
    }
    val ver = Updater.version.takeIf { it != "dev" }?.let { "v$it" } ?: "开发版"
    Row(Modifier.fillMaxWidth().clickable { menu = true }.padding(10.dp, 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (signedIn) t.userBubble else t.border), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (signedIn) t.userBubbleText else t.textMuted)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tier ?: "点一下登录", fontSize = 10.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(ver, fontSize = 10.sp, color = t.textMuted)
    }
    DropdownMenu(menu, { menu = false }) {
        DropdownMenuItem(text = { Text("Yxi $ver", style = MaterialTheme.typography.labelSmall, color = t.textMuted) }, onClick = {}, enabled = false)
        HorizontalDivider(color = t.border)
        DropdownMenuItem(text = { Text("使用情况") }, onClick = { menu = false; state.page = Page.Me })
        DropdownMenuItem(text = { Text("配置") }, onClick = { menu = false; state.page = Page.Config })
        DropdownMenuItem(text = { Text("模型与线路") }, onClick = { menu = false; state.page = Page.Routes })
        DropdownMenuItem(text = { Text("设置…  Ctrl+,") }, onClick = { menu = false; state.showSettings = true })
        if (signedIn) DropdownMenuItem(text = { Text("退出登录", color = t.danger) }, onClick = { menu = false; MeAuth.signOut() })
        else DropdownMenuItem(text = { Text("登录") }, onClick = { menu = false; state.page = Page.Me })
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = Tokens.current
    val fg = if (on) t.accent else t.textSecondary
    Row(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) t.surface2 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}
