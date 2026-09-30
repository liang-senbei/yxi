package app.yxi.desktop
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Dns

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
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.runtime.key
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
import app.yxi.agent.Session

/**
 * 左栏：主机分组 → 该主机上的 cc-* 会话行（Codex 的「项目 → 线程」）。
 * 主机没连也列着（灰点）；点分组头 = 连上（可以同时连几台，都进 `state.conns`）；右键 / ⋯ 出菜单。
 * 刷新和重连的循环在 [Conn] 自己身上，不在这里 —— 侧栏收起时这个 Composable 整个不在组合里，挂这儿的循环会停。
 * 同样的道理，服务器列表、连接和各主机弹窗的状态在 [HostActions]（挂在 App 上），这里只画和转发点击。
 * 宽度由外面定（288dp），这里只管内容。
 */
@Composable
internal fun Sidebar(state: AppState, actions: HostActions, modifier: Modifier = Modifier) {
    val t = Tokens.current
    var hostMenu by remember { mutableStateOf(false) }
    NativeOverlay(hostMenu)
    // 账号行的资料：进来就拉一次（幂等；Me 页里还会再拉）。侧栏收起再展开会重跑，无害
    LaunchedEffect(Unit) { MeAuth.load() }

    var filter by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchVisible) { if (searchVisible) searchFocus.requestFocus() }
    Column(modifier.background(t.sidebar)) {
        UpdateBanner()   // 有新版时才画（Codex 把更新横幅放侧栏顶部）
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.clip(RoundedCornerShape(9.dp)).clickable { hostMenu = true }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.isLocal) "本地" else actions.hosts.firstOrNull { it.id == state.hostScope }?.label ?: "所有主机", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("⌄", Modifier.padding(start = 6.dp), color = t.textMuted)
                }
                DropdownMenu(hostMenu, { hostMenu = false }) {
                    DropdownMenuItem(text = { Text("本地 · 此电脑") }, leadingIcon = { Icon(Icons.Outlined.Computer, null) }, onClick = { state.selectLocal(); hostMenu = false })
                    DropdownMenuItem(text = { Text("所有主机") }, onClick = { state.scopeHost(""); state.page = Page.Workspace; hostMenu = false })
                    HorizontalDivider()
                    actions.hosts.forEach { h ->
                        DropdownMenuItem(text = { Text(h.label + (if (h.region.isBlank()) "" else " · ${h.region}") + " · " + (actions.connOf(h)?.status?.label?.ifBlank { "未连接" } ?: "未连接")) }, onClick = {
                            hostMenu = false; state.scopeHost(h.id)
                            if (actions.connOf(h) == null || actions.connOf(h)?.status == Conn.Status.Failed) actions.connect(h)
                            actions.connOf(h)?.let { c -> if (state.conn !== c) state.select(c, null) }
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("添加服务器…") }, onClick = { hostMenu = false; actions.addHost() })
                    DropdownMenuItem(text = { Text("导入服务器…") }, onClick = { hostMenu = false; actions.importHosts() })
                    DropdownMenuItem(text = { Text("导出服务器（不含认证）…") }, enabled = actions.hosts.isNotEmpty(), onClick = { hostMenu = false; actions.exportHosts() })
                }
            }
            IconButton({ searchVisible = !searchVisible; if (!searchVisible) filter = "" }, Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Search, "搜索会话", Modifier.size(18.dp), tint = t.textMuted)
            }
        }
        if (actions.note.isNotBlank()) Text(actions.note, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (Store.warning.isNotBlank()) Text(Store.warning, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (Store.hostRecoveryNeeded()) TextButton({ actions.recoveringHosts = true }, enabled = state.conns.isEmpty()) { Text(if (state.conns.isEmpty()) "从受保护副本恢复服务器" else "恢复前请先断开连接") }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { actions.newConversation() }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = t.textSecondary)
            Text(if (state.isLocal) "本地工作台" else "新对话", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.textPrimary)
            Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = t.textMuted)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { state.showTaskSwitcher = true }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp), tint = t.textSecondary)
            Text("切换任务", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.textPrimary)
            Text("Ctrl+K", style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        }
        NavItem(Icons.Outlined.Extension, "插件", state.page == Page.Plugins, Modifier.fillMaxWidth().padding(horizontal = 10.dp)) { state.page = Page.Plugins }
        NavItem(Icons.Default.Tune, "配置", state.page == Page.Config, Modifier.fillMaxWidth().padding(horizontal = 10.dp)) { state.page = Page.Config }
        var exploreOpen by remember { mutableStateOf(false) }
        Box {
            NavItem(Icons.Outlined.Explore, "探索", state.page in setOf(Page.Connections, Page.LocalAgents, Page.ScheduledTasks), Modifier.fillMaxWidth().padding(horizontal = 10.dp)) { exploreOpen = true }
            DropdownMenu(exploreOpen, { exploreOpen = false }) {
                DropdownMenuItem(text = { Text("定时任务") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) }, onClick = { exploreOpen = false; state.page = Page.ScheduledTasks })
                DropdownMenuItem(text = { Text("连接") }, leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)) }, onClick = { exploreOpen = false; state.page = Page.Connections })
                DropdownMenuItem(text = { Text("本地 Agent") }, leadingIcon = { Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp)) }, onClick = { exploreOpen = false; state.selectLocal() })
            }
        }
        // 会话过滤（ZCode 的搜索框 / Codex 的过滤）：按会话名 / 主机名滤，主机全不匹配就整组藏掉
        if (searchVisible) Row(
            Modifier.fillMaxWidth().padding(10.dp, 2.dp, 10.dp, 6.dp).clip(RoundedCornerShape(8.dp)).background(t.border),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, "搜索", Modifier.padding(start = 8.dp).size(13.dp), tint = t.textMuted)
            BasicTextField(
                filter, { filter = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = t.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).padding(8.dp, 6.dp),
                decorationBox = { inner -> Box { if (filter.isEmpty()) Text("搜索会话", style = MaterialTheme.typography.bodySmall, color = t.textMuted); inner() } },
            )
        }
        if (!state.isLocal && actions.hosts.isEmpty()) Text("尚未添加服务器", Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (!state.isLocal && actions.hosts.isNotEmpty()) WorkbenchTabs(listOf("全部", "待处理", "归档"), state.navigation.mode, { state.navigation.setMode(it) }, Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        if (state.navigation.error.isNotBlank()) Text(state.navigation.error, Modifier.padding(10.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        SidebarHostList(state, actions, filter, Modifier.weight(1f))
        BottomNav(state)
    }
}

/** 主机与会话列表：本地项目 / 收藏 / 各主机分组。经典和 Code 侧栏共用；[filter] 是搜索框原文。筛选（哪台主机、哪些会话和收藏）只在这里算。
 *  [saved] 是置顶 / 收藏区的外观（Code 传入 [CodeSavedLook]；经典为空，照旧画经典样式）。
 *  [host] 画一台主机的分组（Code 传入 [CodeHostGroup]；经典为空，照旧画 [ClassicHostSection]）。
 *  [local] 是本机时的列表（Code 传入 [CodeLocalSessions]，参数是去掉首尾空白的搜索词；经典为空，照旧画 [LocalWorkspaceSidebar]）。 */
@Composable
internal fun SidebarHostList(state: AppState, actions: HostActions, filter: String, modifier: Modifier = Modifier,
    saved: SavedLook? = null, host: (@Composable (HostSection) -> Unit)? = null, local: (@Composable (String) -> Unit)? = null) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        val f = filter.trim()
        if (state.isLocal) { if (local != null) local(f) else LocalWorkspaceSidebar(state, f) }
        if (!state.isLocal && actions.hosts.isNotEmpty()) SidebarSavedSessions(state, actions.hosts, f, saved) { c, favorite -> actions.openFavorite(c, favorite) }
        actions.hosts.forEachIndexed { i, h ->
            if (state.hostScope.isNotEmpty() && state.hostScope != h.id) return@forEachIndexed
            val c = actions.connOf(h)
            val sessions = c?.sessions?.filter { f.isEmpty() || h.label.contains(f, true) || h.region.contains(f, true) || it.short.contains(f, true) || it.name.contains(f, true) || it.cwd.contains(f, true) || state.navigation.title(taskNavigationKey(h, it))?.contains(f, true) == true || state.navigation.projectTitle(projectKey(h, it.cwd))?.contains(f, true) == true || c.projectGroups.of(it.name).any { group -> group.contains(f, true) } } ?: emptyList()
            val hostMatch = f.isEmpty() || h.label.contains(f, ignoreCase = true) || h.region.contains(f, ignoreCase = true)
            val favorites = if (state.navigation.mode == "全部") state.navigation.favorites(h).filter { favorite ->
                (hostMatch || favorite.title.contains(f, true) || favorite.directory.contains(f, true)) &&
                    c?.sessions?.none { taskNavigationKey(h, it) == favorite.key } != false
            } else emptyList()
            val managedMatch = state.remoteAcpTasks.tasks(h).any { record ->
                listOf(record.title, record.directory, state.navigation.title(record.key).orEmpty(), state.navigation.group(record.key), LocalRuntimeDiscovery.title(record.engine)).any { it.contains(f, true) }
            } || state.remoteOpenCodeTasks.tasks(h).any { it.title.contains(f, true) || it.directory.contains(f, true) } || state.codexWorkspace.tasks(h).any { record ->
                listOf(record.title, record.directory, state.navigation.title(record.key).orEmpty(), state.navigation.group(record.key)).any { it.contains(f, true) }
            }
            if (!hostMatch && sessions.isEmpty() && favorites.isEmpty() && !managedMatch) return@forEachIndexed
            val section = HostSection(i, h, c, sessions, favorites, f)
            // Code 的分组里有按位置记的状态（⋮ 开关），主机按 id 定位，滤掉前面的主机时不串
            if (host != null) key(h.id) { host(section) } else ClassicHostSection(state, actions, section)
        }
    }
}

/** 侧栏里的一台主机，已按搜索词筛过：[index] 是它在主机列表里的序号（连接颜色按它轮），[conn] 没连过时为空，
 *  [sessions] 是匹配的服务器会话，[favorites] 是「收藏 · 未启用」要列的项，[filter] 是去掉首尾空白的搜索词。 */
internal class HostSection(val index: Int, val host: Host, val conn: Conn?, val sessions: List<Session>, val favorites: List<FavoriteLaunch>, val filter: String)

/** 经典的主机分组：整组左侧带连接颜色条；分组头、连接失败的说明和删旧指纹、项目树、空态、未启用的收藏。 */
@Composable
private fun ClassicHostSection(state: AppState, actions: HostActions, section: HostSection) {
    val t = Tokens.current
    val h = section.host
    val c = section.conn
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(h.tint(section.index)))   // 连接颜色条，整组都带着
        Column(Modifier.weight(1f)) {
            HostHeader(
                h, c, current = c != null && state.conn === c, actions = actions,
                // 没连 / 连失败了：点一下就连；连着的分组头点了不动（会话行才是入口）
                onClick = { if (c == null || c.status == Conn.Status.Failed) actions.connect(h) },
            )
            if (c?.status == Conn.Status.Failed) {
                Text(c.error, Modifier.padding(start = 24.dp, end = 10.dp, bottom = 4.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
                // 删旧指纹是单独一步；删完立刻重连，新指纹会再弹一次「确认这是 X 吗」让用户核对
                if (c.keyChanged) TextButton({ actions.keys.forget(h); actions.connect(h) }, Modifier.padding(start = 12.dp)) { Text("我确认过了，删除旧指纹") }
            }
            // 断线重连中列表照旧摆着（服务器上的会话还在），不清；搜索时只画滤剩下的
            if (c != null) {
                ProjectTree(state, c, section.sessions, searching = section.filter.isNotEmpty(), query = section.filter)
                HostEmptyNote(state, h, c, section.filter)
            }
            HostFavorites(actions, c, section.favorites, state.navigation)
        }
    }
}

/** 连着但这台机器上什么都没有（会话和三类托管任务都空）；搜索时不算。空态说明画不画只看它，两种风格共用。 */
internal fun hostIsEmpty(state: AppState, h: Host, c: Conn, filter: String): Boolean =
    filter.isEmpty() && c.status == Conn.Status.Connected && c.sessions.isEmpty() && state.codexWorkspace.tasks(h).isEmpty() && state.remoteOpenCodeTasks.tasks(h).isEmpty() && state.remoteAcpTasks.tasks(h).isEmpty()

/** 经典的空态说明（条件见 [hostIsEmpty]）。 */
@Composable
internal fun HostEmptyNote(state: AppState, h: Host, c: Conn, filter: String) {
    if (hostIsEmpty(state, h, c, filter))
        Text("这台机器上还没有会话", Modifier.padding(start = 24.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
}

/** 「收藏 · 未启用」：收藏过、服务器上已经没有的会话；点了按收藏的目录 / Agent 新建（[HostActions.openFavorite]），× 取消收藏。 */
@Composable
internal fun HostFavorites(actions: HostActions, c: Conn?, favorites: List<FavoriteLaunch>, nav: WorkspaceNavigation) {
    if (favorites.isNotEmpty()) Text("收藏 · 未启用", Modifier.padding(start = 18.dp, top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
    favorites.forEach { favorite ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (c != null) actions.openFavorite(c, favorite) }, Modifier.weight(1f), enabled = c?.status == Conn.Status.Connected && !actions.openingFavorite) {
                Column {
                    Text(favorite.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(favorite.directory, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton({ nav.removeFavorite(favorite.key) }) { Text("×") }
        }
    }
}

/** 分组头：状态点 + 别名 + 状态文字 + ⋯ 菜单（右键也出，条目见 [hostMenu]）。当前主机底色 selected，悬停 hover。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostHeader(h: Host, c: Conn?, current: Boolean, actions: HostActions, onClick: () -> Unit) {
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
            DropdownMenu(menu, { menu = false }) { ClassicMenuEntries(hostMenu(h, c, actions)) { menu = false } }
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
    Box(Modifier.size(8.dp).background(color, CircleShape))
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
        AccountAvatar(name, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tier ?: "点一下登录", fontSize = 10.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(ver, fontSize = 10.sp, color = t.textMuted)
    }
    DropdownMenu(menu, { menu = false }) {
        DropdownMenuItem(text = { Column {
            Text(name, style = MaterialTheme.typography.titleSmall, color = t.textPrimary)
            Text(listOfNotNull(tier, "Yxi $ver").joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        } }, leadingIcon = { AccountAvatar(name, 28.dp) }, onClick = {}, enabled = false)
        HorizontalDivider(color = t.border)
        DropdownMenuItem(text = { Text("我的 · 账号与使用情况") }, leadingIcon = { Icon(Icons.Outlined.AccountCircle, null, Modifier.size(18.dp)) }, onClick = { menu = false; state.meSection = "个人资料"; state.page = Page.Me })
        DropdownMenuItem(text = { Text("版本与更新 · $ver") }, leadingIcon = { Icon(Icons.Outlined.SystemUpdateAlt, null, Modifier.size(18.dp)) }, onClick = { menu = false; state.settingsSection = "关于"; state.showSettings = true })
        DropdownMenuItem(text = { Text("设置") }, trailingIcon = { Text("Ctrl+,", style = MaterialTheme.typography.labelSmall, color = t.textMuted) }, leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)) }, onClick = { menu = false; state.showSettings = true })
        HorizontalDivider(color = t.border)
        DropdownMenuItem(text = { Text("服务器配置") }, leadingIcon = { Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp)) }, onClick = { menu = false; state.page = Page.Config })
        DropdownMenuItem(text = { Text("插件") }, leadingIcon = { Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)) }, onClick = { menu = false; state.page = Page.Plugins })
        HorizontalDivider(color = t.border)
        DropdownMenuItem(text = { Text(if (signedIn) "切换账号…" else "浏览器登录…") }, leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp)) }, enabled = !MeAuth.waitingBrowser, onClick = { menu = false; state.requestAccountLogin(true) })
        if (signedIn) DropdownMenuItem(text = { Text("退出登录", color = t.danger) }, leadingIcon = { Icon(Icons.Outlined.Logout, null, Modifier.size(18.dp), tint = t.danger) }, onClick = { menu = false; MeAuth.signOut() })
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = Tokens.current
    val fg = if (on) t.textPrimary else t.textSecondary
    Row(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) t.selected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

