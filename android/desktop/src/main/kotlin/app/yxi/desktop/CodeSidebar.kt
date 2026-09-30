package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.AccountApi

/**
 * Code 风格侧栏（PRD §5.1 / §5.4，规格 §3.5）：导航行（新会话 / 插件 / 更多）→ 当前范围的分组标题（新建 / 搜索 / 筛选）
 * → 会话列表 → 账号行。顶上 48 留给外壳的图标行（CodeShell 画在最上层）。
 * 入口和经典侧栏一一对应，只是换了位置：范围和状态筛选进「筛选」浮层；切换任务、配置、探索、服务器管理进「更多」。
 * 会话列表和经典共用 [SidebarHostList]；状态（草稿、连接、选中）都在 AppState / HostActions，这里只画和转发点击。
 */
@Composable
internal fun CodeSidebar(state: AppState, actions: HostActions, modifier: Modifier = Modifier) {
    val t = Tokens.current
    val line = hairline()
    // 账号行的资料：进来就拉一次（幂等，和经典侧栏同一个调用）
    LaunchedEffect(Unit) { MeAuth.load() }
    var filter by remember { mutableStateOf(TextFieldValue()) }
    var searchVisible by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchVisible) { if (searchVisible) searchFocus.requestFocus() }
    Column(
        modifier.background(t.sidebar).drawWithContent {
            drawContent()
            val w = line.toPx()
            drawRect(t.border, Offset(size.width - w, 0f), Size(w, size.height))   // 右缘细线（宽 288 含这条线）
        },
    ) {
        Spacer(Modifier.height(48.dp))
        UpdateBanner()   // 有新版时才画
        CodeNavRow("新会话", shortcut = "Ctrl+N", plusCircle = true) { if (state.isLocal) openLocalHome(state) else actions.newConversation() }
        CodeNavRow("插件", Icons.Outlined.Extension, selected = state.page == Page.Plugins) { state.page = Page.Plugins }
        CodeMoreRow(state, actions)
        if (actions.note.isNotBlank()) Text(actions.note, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (Store.warning.isNotBlank()) Text(Store.warning, Modifier.padding(14.dp, 2.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        if (Store.hostRecoveryNeeded()) TextButton({ actions.recoveringHosts = true }, enabled = state.conns.isEmpty()) { Text(if (state.conns.isEmpty()) "从受保护副本恢复服务器" else "恢复前请先断开连接") }
        Spacer(Modifier.height(20.dp))
        // 本机时搜索框多一个 Enter：去运行器里搜全部原生历史（同经典列表页的「搜索」按钮）；收起搜索框时撤掉这次历史搜索
        val workspace = state.localWorkspace
        CodeGroupTitle(state, actions, searchVisible) {
            searchVisible = !searchVisible
            if (!searchVisible) { filter = TextFieldValue(); if (state.isLocal && workspace.query.isNotBlank()) workspace.loadThreads("", workspace.archived) }
        }
        if (searchVisible) {
            if (state.isLocal) CodeSearchBox(filter, { filter = it }, searchFocus, "搜索（Enter 搜全部历史）") { workspace.loadThreads(filter.text.trim(), workspace.archived) }
            else CodeSearchBox(filter, { filter = it }, searchFocus)
        }
        if (!state.isLocal && actions.hosts.isEmpty()) Text("尚未添加服务器", Modifier.padding(14.dp, 8.dp), color = t.textMuted, fontSize = 13.sp)
        if (state.navigation.error.isNotBlank()) Text(state.navigation.error, Modifier.padding(10.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        SidebarHostList(state, actions, filter.text, Modifier.weight(1f), saved = CodeSavedLook, host = { CodeHostGroup(state, actions, it) }) { q -> CodeLocalSessions(state, q) }
        CodeAccountRow(state)
    }
}

/**
 * 导航行：高 26、左右外边距 8 / 10、圆角 6；文字 14 起于 x 38.4，次色（[muted] 时弱色）；hover #F0EFEC，选中 #EDECE8。
 * [plusCircle]：「+」放在 18 的圆底里（hover 变深）；[shortcut] 只在 hover 时出现在右端。[held]：浮层开着时保持 hover 底。
 */
@Composable
private fun CodeNavRow(
    label: String,
    icon: ImageVector? = null,
    selected: Boolean = false,
    muted: Boolean = false,
    held: Boolean = false,
    plusCircle: Boolean = false,
    shortcut: String? = null,
    onClick: () -> Unit,
) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fg = if (selected) t.textPrimary else if (muted) t.textMuted else t.textSecondary
    Row(
        Modifier.padding(start = 8.dp, end = 10.dp).fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) t.selected else if (hovered || held) t.hover else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 5.dp, end = 8.8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标格宽 18（x 13–31），文字从 x 38.4 起
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (plusCircle) Box(Modifier.size(18.dp).clip(CircleShape).background(if (hovered) p.plusCircleHover else p.plusCircle), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, Modifier.size(14.dp), tint = t.textSecondary)
            } else if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        }
        Spacer(Modifier.width(7.4.dp))
        Text(label, Modifier.weight(1f), color = fg, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Clip)
        if (shortcut != null && hovered) Text(shortcut, color = t.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}
/**
 * 「更多」：经典侧栏里切换任务、配置、探索（定时任务 / 连接 / 本地 Agent）和服务器管理的入口都收在这里。
 * 浮层在行下方，左缘对齐行的填充（规格没记，定值）。
 */
@Composable
private fun CodeMoreRow(state: AppState, actions: HostActions) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val pages = setOf(Page.Config, Page.Connections, Page.LocalAgents, Page.ScheduledTasks)
    Box {
        CodeNavRow("更多", Icons.Filled.MoreHoriz, selected = state.page in pages, muted = true, held = open) { open = true }
        // 锚点内缩到行的填充范围（不接事件，点击照样落到行上）
        Box(Modifier.matchParentSize().padding(start = 8.dp, end = 10.dp)) {
            if (open) CodePopup(close, width = 240.dp) {
                CodeMenuItem("切换任务", { close(); state.showTaskSwitcher = true }, trailing = "Ctrl+K")
                CodeMenuItem("配置", { close(); state.page = Page.Config }, checked = state.page == Page.Config)
                CodeMenuDivider()
                CodeMenuItem("定时任务", { close(); state.page = Page.ScheduledTasks }, checked = state.page == Page.ScheduledTasks)
                CodeMenuItem("连接", { close(); state.page = Page.Connections }, checked = state.page == Page.Connections)
                CodeMenuItem("本地 Agent", { close(); state.selectLocal() })
                CodeMenuDivider()
                CodeMenuItem("添加服务器…", { close(); actions.addHost() })
                CodeMenuItem("导入服务器…", { close(); actions.importHosts() })
                CodeMenuItem("导出服务器（不含认证）…", { close(); actions.exportHosts() }, enabled = actions.hosts.isNotEmpty())
            }
        }
    }
}
/**
 * 分组标题：当前范围（本地 / 所有主机 / 某台主机），13 弱色起于 x 14.4，整行不接 hover。
 * 右侧三个图标常显（中心 x 216 / 240 / 264）：新建、搜索（开着时保持 hover 底）、筛选。
 */
@Composable
private fun CodeGroupTitle(state: AppState, actions: HostActions, searchVisible: Boolean, onToggleSearch: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    var filterMenu by remember { mutableStateOf(false) }
    NativeOverlay(filterMenu)
    val title = if (state.isLocal) "本地" else actions.hosts.firstOrNull { it.id == state.hostScope }?.label ?: "所有主机"
    Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 14.4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Clip)
        CodeIconButton(Icons.Filled.Add, "新会话", hoverFill = p.groupPlusHover, shortcut = "Ctrl+N") { if (state.isLocal) openLocalHome(state) else actions.newConversation() }
        CodeIconButton(Icons.Outlined.Search, "搜索会话", held = searchVisible, hoverFill = p.groupPlusHover, onClick = onToggleSearch)
        Box {
            CodeIconButton(Icons.Outlined.FilterList, "筛选", held = filterMenu, hoverFill = p.groupPlusHover) { filterMenu = true }
            if (filterMenu) CodeFilterMenu(state, actions) { filterMenu = false }
        }
    }
}

/**
 * 筛选浮层：本机时先列原生历史的范围（最近 / 归档，同经典列表页的两个标签；搜过全部历史时可以清除）；
 * 有服务器时列状态（全部 / 待处理 / 归档）；最后是范围（本地 / 所有主机 / 各主机）。右缘对齐筛选按钮。
 */
@Composable
private fun CodeFilterMenu(state: AppState, actions: HostActions, close: () -> Unit) {
    CodePopup(close, alignEnd = true, width = 240.dp) {
        if (state.isLocal) {
            val w = state.localWorkspace
            val ready = w.selectedRuntime != null && !w.loading
            CodeMenuCaption("原生历史")
            CodeMenuItem("最近", { close(); w.loadThreads(w.query, false) }, enabled = ready, checked = !w.archived)
            CodeMenuItem("归档", { close(); w.loadThreads(w.query, true) }, enabled = ready, checked = w.archived)
            if (w.query.isNotBlank()) CodeMenuItem("清除历史搜索", { close(); w.loadThreads("", w.archived) }, enabled = ready, trailing = w.query.take(12))
            CodeMenuDivider()
        }
        if (!state.isLocal && actions.hosts.isNotEmpty()) {
            listOf("全部", "待处理", "归档").forEach { mode ->
                CodeMenuItem(mode, { close(); state.navigation.setMode(mode) }, checked = state.navigation.mode == mode)
            }
            CodeMenuDivider()
        }
        CodeMenuItem("本地 · 此电脑", { close(); state.selectLocal() }, checked = state.isLocal)
        CodeMenuItem("所有主机", { close(); state.scopeHost(""); state.page = Page.Workspace }, checked = state.hostScope.isEmpty())
        if (actions.hosts.isNotEmpty()) CodeMenuDivider()
        actions.hosts.forEach { h ->
            val status = actions.connOf(h)?.status?.label?.ifBlank { "未连接" } ?: "未连接"
            CodeMenuItem(h.label + (if (h.region.isBlank()) "" else " · ${h.region}"), {
                close(); state.scopeHost(h.id)
                if (actions.connOf(h) == null || actions.connOf(h)?.status == Conn.Status.Failed) actions.connect(h)
                actions.connOf(h)?.let { c -> if (state.conn !== c) state.select(c, null) }
            }, trailing = status, checked = state.hostScope == h.id)
        }
    }
}
/**
 * 会话搜索框：底 #F3F3F3、圆角 6、高 28，放大镜 + 占位符；过滤和经典同一套（按会话名 / 主机名 / 目录）。
 * [onSearch]：按 Enter 时调用（本机用来搜全部原生历史）；中文输入法正在组词时 Enter 归输入法。
 */
@Composable
private fun CodeSearchBox(
    filter: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    placeholder: String = "搜索会话",
    onSearch: (() -> Unit)? = null,
) {
    val t = Tokens.current
    Row(
        Modifier.padding(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 4.dp).fillMaxWidth().height(28.dp)
            .clip(RoundedCornerShape(6.dp)).background(t.surface1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.padding(start = 8.dp).size(14.dp), tint = t.textMuted)
        BasicTextField(
            filter, onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = t.textPrimary, fontSize = 13.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(t.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 8.dp)
                .onPreviewKeyEvent { e ->
                    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (onSearch != null && enter && e.type == KeyEventType.KeyDown && filter.composition == null) { onSearch(); true } else false
                },
            decorationBox = { inner -> Box { if (filter.text.isEmpty()) Text(placeholder, color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1); inner() } },
        )
    }
}

/**
 * 账号行：上方细线；高 44，头像 16 起于 x 14.4，名称 14 次色起于 x 38.4，右端 ∨ 在 x 263.2；菜单开着时 #EDECE8。
 * 菜单 272 宽，从行上方展开（x 8，底边略盖过细线）；菜单项和经典账号菜单一一对应。
 */
@Composable
private fun CodeAccountRow(state: AppState) {
    val t = Tokens.current
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
    val close = { menu = false }
    val me = MeAuth.me
    val signedIn = MeAuth.signedIn
    val name = if (signedIn) me?.nickname?.ifBlank { null } ?: "Yxi 用户" else "未登录"
    val tier = me?.let { when (it.tier) { AccountApi.Tier.Ultra -> "Ultra"; AccountApi.Tier.Pro -> "Pro"; else -> "免费" } }
    val ver = Updater.version.takeIf { it != "dev" }?.let { "v$it" } ?: "开发版"
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().height(hairline()).background(t.border))
    Box(Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
        Row(
            Modifier.matchParentSize().clip(RoundedCornerShape(6.dp))
                .background(if (menu) t.selected else if (hovered) t.hover else Color.Transparent)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { menu = true }
                .padding(start = 6.4.dp, end = 2.8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(name, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(name, Modifier.weight(1f), color = t.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = t.textMuted)
        }
        // 锚点是内缩后的行框（y 起于行顶 +6）：gap 3.6 让菜单底边落在细线下 3.2，和参考一致
        if (menu) CodePopup(close, side = PopupSide.Above, gap = 3.6.dp, width = 272.dp) {
            CodeMenuHeader(name, listOfNotNull(tier, "Yxi $ver").joinToString(" · "))
            CodeMenuDivider()
            CodeMenuItem("我的 · 账号与使用情况", { close(); state.meSection = "个人资料"; state.page = Page.Me })
            CodeMenuItem("版本与更新 · $ver", { close(); state.settingsSection = "关于"; state.showSettings = true })
            CodeMenuItem("设置", { close(); state.showSettings = true }, trailing = "Ctrl+,")
            CodeMenuDivider()
            CodeMenuItem("服务器配置", { close(); state.page = Page.Config })
            CodeMenuItem("插件", { close(); state.page = Page.Plugins })
            CodeMenuDivider()
            CodeMenuItem(if (signedIn) "切换账号…" else "浏览器登录…", { close(); state.requestAccountLogin(true) }, enabled = !MeAuth.waitingBrowser)
            if (signedIn) CodeMenuItem("退出登录", { close(); MeAuth.signOut() }, color = t.danger)
        }
    }
}
