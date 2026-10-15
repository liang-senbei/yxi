package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.ConfigRemote
import kotlinx.coroutines.CancellationException

/** 自定义页的三个标签：技能（这里画）、插件、MCP（经典的子面板放进来）。 */
private enum class CustomizeTab(val label: String) { Skills("技能"), Plugins("插件"), Mcp("MCP") }

/**
 * Code 风格的自定义页（M4o，规格表 §3.10，PRD §6「自定义页」）：侧栏「插件」进来，经典是 [PluginsPane]。
 * - 内容列宽 960，在主区里居中；主区右边先留 9.6（滚动条的槽），列就落在实测的 x 683.2–1643.2（中心比主区中线偏左 4.8，规格表记「原因未知」，按滚动条槽解释）。
 *   主区不够宽时两侧各留 40。
 * - 标题「自定义」≈28、主色、无衬线（参考端是衬线，PRD §10-7），字墨顶 y 98.4。
 * - 标签页一排高 32、顶 y 148：技能 / 插件 / MCP，选中 #F0F0EF 底、圆角 10、左右 12、14 主色，未选弱色，彼此紧挨。
 *   同一排右端：位置 chip（本机 / 当前服务器，三个标签共用，同经典的「本地插件 / 服务器插件」）；插件标签在服务器上多一个运行器 chip（Codex / Claude Code，
 *   同经典的「目录来源 / 原生安装记录」）；技能标签的「重新读取」；搜索图标 16.8（中心 x 1543.6，点开换成搜索框，筛当前列表，和经典同一个搜索词）；
 *   黑底「添加」70.4×30.4、圆角 8（x 1572、y 148.8）。
 * - 「添加」只在有真实入口时放（PRD §1 不放空入口）：插件 = 进插件市场（同经典的「插件市场」，上面一行「‹ 已安装插件」回来）；
 *   MCP = 经典的「添加通用 MCP 配置」；技能没有安装入口（技能放在服务器的 ~/.claude/skills 里或随插件来），不放。
 * - 标签页下 28 是内容（y 208）：技能列表是这里画的（[CodeSkillsTab]）；插件、MCP 里面还是经典的子面板（NativePluginPane / PluginInventoryPane /
 *   PluginCatalogPane / SharedMcpPane，各自的标题、刷新、卡片照旧，只是搜索框换成这里的）。没选服务器、没连上这类状态用 §3.10 的空态卡（[CodeEmptyCard]）。
 * 状态和经典共用（位置 pluginLocation、运行器 pluginCatalogRuntime、搜索词 pluginMarketQuery、MCP 标签 = sharedMcpPage），
 * Code 自己多两项（[AppState.customizeSkills]、[AppState.customizeMarket]），切风格时停在同一处。
 */
@Composable
internal fun CodeCustomizePane(state: AppState) {
    val t = Tokens.current
    val conn = state.conn
    val local = state.pluginLocation == "本地"
    val tab = when { state.customizeSkills -> CustomizeTab.Skills; state.sharedMcpPage -> CustomizeTab.Mcp; else -> CustomizeTab.Plugins }
    var searching by remember { mutableStateOf(state.pluginMarketQuery.isNotBlank()) }
    var searchFocus by remember { mutableStateOf(false) }
    var mcpAdd by remember { mutableStateOf(false) }
    val closeSearch = { searching = false; state.pluginMarketQuery = "" }
    Box(Modifier.fillMaxSize().padding(end = 9.6.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(horizontal = 40.dp).widthIn(max = 960.dp).fillMaxWidth().fillMaxHeight()) {
            Spacer(Modifier.height(CustomizeTitleTop))
            Text("自定义", color = t.textPrimary, fontSize = 28.sp, lineHeight = 36.sp, maxLines = 1)
            Spacer(Modifier.height(CustomizeTabsGap))
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                CustomizeTabs(tab) { next ->
                    state.customizeSkills = next == CustomizeTab.Skills
                    if (next != CustomizeTab.Skills) state.sharedMcpPage = next == CustomizeTab.Mcp
                }
                Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
                CustomizeLocationChip(state, conn, local)
                if (tab == CustomizeTab.Plugins && !local) {
                    Spacer(Modifier.width(5.6.dp))
                    CustomizeRuntimeChip(state)
                }
                Spacer(Modifier.width(12.dp))
                if (tab == CustomizeTab.Skills && !local && conn != null) {
                    val host = conn.host.id
                    CodeIconButton(Icons.Outlined.Refresh, "重新读取技能", box = 28.dp, iconSize = 16.8.dp,
                        enabled = conn.status == Conn.Status.Connected && !RemoteSlashCommands.reading(host)) {
                        if (conn.ssh.isConnected) RemoteSlashCommands.refresh(host) { ConfigRemote.read(conn.ssh) }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                val focus = remember { FocusRequester() }
                if (searching) {
                    CustomizeSearch(state.pluginMarketQuery, { state.pluginMarketQuery = it }, closeSearch, focus,
                        when (tab) { CustomizeTab.Skills -> "搜索技能"; CustomizeTab.Plugins -> "搜索插件"; CustomizeTab.Mcp -> "搜索 MCP" })
                    LaunchedEffect(searchFocus) { if (searchFocus) { runCatching { focus.requestFocus() }; searchFocus = false } }
                } else CodeIconButton(Icons.Outlined.Search, "搜索", box = 28.dp, iconSize = 16.8.dp) { searching = true; searchFocus = true }
                val add: (() -> Unit)? = when (tab) {
                    CustomizeTab.Skills -> null
                    CustomizeTab.Plugins -> if (state.customizeMarket) null else ({ state.customizeMarket = true })
                    CustomizeTab.Mcp -> if (!local && conn == null) null else ({ mcpAdd = true })
                }
                if (add != null) {
                    Spacer(Modifier.width(14.4.dp))
                    CodeAddButton(enabled = tab != CustomizeTab.Mcp || state.sharedMcp.problem.isBlank(), onClick = add)
                }
                Spacer(Modifier.width(0.8.dp))   // 实测「添加」右缘 1642.4，比内容列右缘 1643.2 往里 0.8
            }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 24.dp)) {
                when (tab) {
                    CustomizeTab.Skills -> CodeSkillsTab(state, local, conn)
                    CustomizeTab.Plugins -> CodePluginsTab(state, local, conn)
                    CustomizeTab.Mcp -> if (!local && conn == null) CodeEmptyCard("先选一台服务器", "服务器的 MCP 配置按机器登记。在侧栏选一台服务器后再回来，或者右上角切回本机。")
                        else SharedMcpPane(state, conn, addRequested = mcpAdd, onAddHandled = { mcpAdd = false }, inlineAdd = false)
                }
            }
        }
    }
}

/**
 * 标题区：页面顶（y 36）到标题框顶、标题框底到标签页顶。标题框 125% 下实测 36.8 高（行高 36 取整到 46 像素），字墨顶在框顶 + 5.6：
 * 56.8 让字墨顶落在 y 98.4，再隔 18.4 标签页顶落在 y 148（「添加」30.4 高居中 → y 148.8）。都取 0.8 的整数倍（125% 下整像素，半像素会被取整）。
 */
private val CustomizeTitleTop = 56.8.dp
private val CustomizeTabsGap = 18.4.dp

/** 标签页：高 32、左右 12、字 14；选中 #F0F0EF 底、圆角 10、主色，未选弱色（悬停次色），彼此紧挨。 */
@Composable
private fun CustomizeTabs(selected: CustomizeTab, select: (CustomizeTab) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    Row(Modifier.selectableGroup()) {
        CustomizeTab.entries.forEach { tab ->
            val on = tab == selected
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(if (on) p.sunken else Color.Transparent)
                    .hoverable(source).selectable(on, source, indication = null, role = Role.Tab) { select(tab) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tab.label, color = if (on) t.textPrimary else if (hovered) t.textSecondary else t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
            }
        }
    }
}

/** 位置 chip：本机（笔记本图标）或当前服务器（服务器图标）；浮层两项，服务器没选 / 没连上时右侧注明。只换这一页看的位置，不切侧栏的主机。 */
@Composable
private fun CustomizeLocationChip(state: AppState, conn: Conn?, local: Boolean) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val server = conn?.host?.label ?: "服务器"
    Box {
        CodeChip(if (local) "本机" else server, if (local) Icons.Outlined.Laptop else Icons.Outlined.Dns, { open = true }, Modifier.widthIn(max = 220.dp), held = open)
        if (open) CodePopup(close, alignEnd = true, gap = 5.6.dp, width = 200.dp) {
            CodeMenuItem("本机", { close(); state.pluginLocation = "本地" }, checked = local)
            CodeMenuItem(server, { close(); state.pluginLocation = "服务器" }, checked = !local,
                trailing = when { conn == null -> "未选择"; conn.status != Conn.Status.Connected -> "未连接"; else -> null })
        }
    }
}

/** 运行器 chip（插件标签、服务器上）：Codex 的原生插件，还是 Claude Code 的插件（同经典的两个选项）；浮层头的说明随已安装 / 市场换。 */
@Composable
private fun CustomizeRuntimeChip(state: AppState) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val claude = state.pluginCatalogRuntime == "claude"
    Box(Modifier.semantics { contentDescription = "插件运行器" }) {   // chip 没有图标，读屏和截图夹具靠这句认它（侧栏里也有运行器名）
        CodeChip(if (claude) "Claude Code" else "Codex", null, { open = true }, held = open)
        if (open) CodePopup(close, alignEnd = true, gap = 5.6.dp, width = 200.dp) {
            CodeMenuCaption(if (state.customizeMarket) "目录来源" else "原生安装记录")
            CodeMenuItem("Codex", { close(); state.pluginCatalogRuntime = "codex" }, checked = !claude)
            CodeMenuItem("Claude Code", { close(); state.pluginCatalogRuntime = "claude" }, checked = claude)
        }
    }
}

/** 搜索框（点搜索图标后换上）：220×28、浅填充、圆角 6，放大镜 + 13 号字 + 关闭；Esc 或关闭 = 清空搜索词并收起。 */
@Composable
private fun CustomizeSearch(query: String, onChange: (String) -> Unit, onClose: () -> Unit, focus: FocusRequester, placeholder: String) {
    val t = Tokens.current
    val p = CodePalette.current
    Row(Modifier.width(220.dp).height(28.dp).clip(RoundedCornerShape(6.dp)).background(p.menuHover), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.padding(start = 8.dp).size(14.dp), tint = t.textMuted)
        BasicTextField(
            query, onChange, singleLine = true,
            textStyle = TextStyle(color = t.textPrimary, fontSize = 13.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(t.textPrimary),
            modifier = Modifier.weight(1f).focusRequester(focus).padding(horizontal = 8.dp)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false },
            decorationBox = { inner -> Box { if (query.isEmpty()) Text(placeholder, color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1); inner() } },
        )
        CodeIconButton(Icons.Outlined.Close, "关闭搜索", Modifier.padding(end = 4.dp), box = 20.dp, iconSize = 12.dp, tint = t.textMuted, onClick = onClose)
    }
}

/** 「添加」主按钮（规格表 §3.10 / §1.4）：70.4×30.4、圆角 8、#0B0B0B 底、白字 14 半粗；深色反过来；不可用 #9D9D9D 底；悬停略淡。 */
@Composable
private fun CodeAddButton(enabled: Boolean, onClick: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.size(70.4.dp, 30.4.dp).alpha(if (hovered && enabled) 0.88f else 1f).clip(RoundedCornerShape(8.dp))
            .background(if (enabled) p.primary else p.primaryDisabled)
            .hoverable(source).clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("添加", color = if (t.dark) Color(0xFF0B0B0B) else Color.White, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * 空态卡（规格表 §3.10）：凹陷面 #F0F0EF、宽同内容列、至少 96 高、圆角 12、内边距 16；标题 16 半粗主色，说明 14 次色（文案 Yxi 自拟）。
 * 参考端右侧的插画不放（§5）。说明放不下一行时卡片跟着长高。
 */
@Composable
internal fun CodeEmptyCard(title: String, detail: String) {
    val t = Tokens.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(12.dp)).background(CodePalette.current.sunken).padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = t.textPrimary, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = t.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** 内容区顶上的一行小字（技能数、读取进度）：13 弱色，左边和标签字对齐（内缩 12）。 */
@Composable
private fun CustomizeNote(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp), color = Tokens.current.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * 插件标签：默认看已安装，「添加」进市场（上面一行「‹ 已安装插件」回来）。本机 = 这台电脑的 Codex 原生插件；服务器 = 那台机器上
 * 运行器 chip 选的 Codex 原生插件或 Claude Code 插件（安装记录 / 市场目录）。里面都是经典的子面板，搜索词用这一页的。
 */
@Composable
private fun CodePluginsTab(state: AppState, local: Boolean, conn: Conn?) {
    val market = state.customizeMarket
    Column(Modifier.fillMaxSize()) {
        if (market) {
            CustomizeBackLink("已安装插件") { state.customizeMarket = false }
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                local -> NativePluginPane(state, null, installedOnly = !market)
                conn == null -> CodeEmptyCard("先选一台服务器", "服务器插件装在那台机器的 Codex 或 Claude Code 里。在侧栏选一台服务器后再回来，或者右上角切回本机。")
                conn.status != Conn.Status.Connected -> CodeEmptyCard("${conn.host.label} 未连接", "连上这台服务器后，在这里查看、安装和管理它的插件。")
                state.pluginCatalogRuntime == "codex" -> NativePluginPane(state, conn, installedOnly = !market)
                market -> PluginCatalogPane(state, conn, embedded = true, searchText = state.pluginMarketQuery) { state.customizeMarket = false }
                else -> PluginInventoryPane(state, conn, query = state.pluginMarketQuery)
            }
        }
    }
}

/** 返回上一级的文字按钮：‹ + 13 次色，悬停主色；高 24。 */
@Composable
private fun CustomizeBackLink(label: String, onClick: () -> Unit) {
    val t = Tokens.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val color = if (hovered) t.textPrimary else t.textSecondary
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

/**
 * 技能标签（只读）：当前服务器上 Claude Code 的技能 —— ~/.claude/skills 里用户自己的，和已装插件带来的（[HostSkills.fromConfig]）。
 * 数据和斜杠菜单同一份缓存（[RemoteSlashCommands]）：进来时这台主机还没读过才读（[RemoteSlashCommands.ensure]，读在它自己的 scope 里，
 * 切标签、离开页面都不会把读到一半的取消掉），右上角「重新读取」再读一次。没连上时有上次读到的就照列、注明。
 * 本机：代码里还没有读本机 ~/.claude 的现成实现，只放一张说明卡（不为它另写一个读取器）。
 * 每行：技能名（14 主色）+ 右端来源（「用户」/「插件 X」，13 弱色）；行高 44、悬停浅填充圆角 8，行间 #F3F3F3 细线；点开是只读的 SKILL.md（[CodeSkillDialog]）。
 */
@Composable
private fun CodeSkillsTab(state: AppState, local: Boolean, conn: Conn?) {
    if (local) {
        CodeEmptyCard("还不能列出本机的技能", "Yxi 目前只读服务器上 Claude Code 的配置。右上角切到服务器，就能看到那台机器 ~/.claude 里的技能。")
        return
    }
    if (conn == null) {
        CodeEmptyCard("先选一台服务器", "技能列表读的是服务器上 Claude Code 的配置。在侧栏选一台服务器后再回来。")
        return
    }
    val host = conn.host.id
    val label = conn.host.label
    val connected = conn.status == Conn.Status.Connected
    LaunchedEffect(host, connected) { if (connected && conn.ssh.isConnected) RemoteSlashCommands.ensure(host) { ConfigRemote.read(conn.ssh) } }
    val all = RemoteSlashCommands.skills(host)
    val reading = RemoteSlashCommands.reading(host)
    val failed = RemoteSlashCommands.failed(host)
    var open by remember(host) { mutableStateOf<HostSkill?>(null) }
    open?.let { CodeSkillDialog(conn, it) { open = null } }
    when {
        all == null && failed && !reading -> CodeEmptyCard("没读到 $label 上的技能", "连接中断了，或者读取脚本出错（要用到那台机器上的 python3）。点右上角的「重新读取」再试一次。")
        all == null && (connected || reading) -> CustomizeNote("正在读取 $label 上的技能…")
        all == null -> CodeEmptyCard("$label 未连接", "连上这台服务器后，这里列出它的 Claude Code 技能：你放在 ~/.claude/skills 里的，和插件带来的。")
        all.isEmpty() -> CodeEmptyCard("$label 上还没有技能", "Claude Code 的技能放在 ~/.claude/skills/<名字>/SKILL.md，也可以随插件装进来。")
        else -> {
            val query = state.pluginMarketQuery.trim()
            val shown = HostSkills.filter(all, query)
            Column(Modifier.fillMaxSize()) {
                CustomizeNote(buildString {
                    append("$label · Claude Code 技能 · ${all.size} 个")
                    if (query.isNotEmpty()) append(" · 匹配 ${shown.size} 个")
                    when {
                        reading -> append(" · 正在重新读取…")
                        failed -> append(" · 刚才重新读取没成功，显示的是上次读到的")
                        !connected -> append(" · 未连接，显示的是上次读到的")
                    }
                })
                Spacer(Modifier.height(12.dp))
                if (shown.isEmpty()) CodeEmptyCard("没有匹配「$query」的技能", "换个关键词，或者关掉搜索看全部。")
                else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(shown, key = { _, s -> s.pluginId + "\u0000" + s.name }) { i, skill -> CodeSkillRow(skill, first = i == 0) { open = skill } }
                }
            }
        }
    }
}

/** 一行技能：高 44、左右 12；名字 14 主色，右端来源 13 弱色；悬停浅填充、圆角 8；上一行和它之间一条浅填充色的细线（第一行没有）。 */
@Composable
private fun CodeSkillRow(skill: HostSkill, first: Boolean, onOpen: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(hairline()).background(p.menuHover))
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(if (hovered) p.menuHover else Color.Transparent)
                .hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(skill.name, Modifier.weight(1f), color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HostSkills.source(skill), Modifier.padding(start = 16.dp).widthIn(max = 320.dp), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 只读的 SKILL.md：走 [ConfigRemote.readFile]（同配置页「查看」，路径经 Shell.q 转义），没连上不读、直接说明。头部的 YAML 拆出 description 放在上面，
 * 正文按 Markdown 画（同插件说明）；超过 128 KiB 只画前面一段。弹窗用经典的 [WorkbenchDialog]（Code 的其他弹窗也是），关掉就取消读取。
 */
@Composable
private fun CodeSkillDialog(conn: Conn, skill: HostSkill, close: () -> Unit) {
    val t = Tokens.current
    var text by remember(skill) { mutableStateOf<String?>(null) }
    var error by remember(skill) { mutableStateOf("") }
    var busy by remember(skill) { mutableStateOf(true) }
    LaunchedEffect(conn, skill) {
        busy = true; error = ""; text = null
        try {
            check(conn.status == Conn.Status.Connected && conn.ssh.isConnected) { "${conn.host.label} 未连接，连上之后才能读 SKILL.md。" }
            text = ConfigRemote.readFile(conn.ssh, skill.path) ?: error("读不到这个 SKILL.md：文件不存在、是空的，或者没有权限。")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty().ifBlank { "读取失败" } }
        finally { busy = false }
    }
    val doc = remember(text) { text?.let { HostSkills.document(it.take(131_072)) } }
    WorkbenchDialog(
        onDismissRequest = close,
        title = { Text(skill.name) },
        text = {
            Column(Modifier.widthIn(max = 640.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${conn.host.label} · ${HostSkills.source(skill)} · 只读", color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                SelectionContainer { Text(skill.path, style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.8.sp), color = t.textMuted) }
                if (busy) Text("正在读取…", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                if (error.isNotBlank()) Text(error, color = t.danger, fontSize = 13.sp, lineHeight = 18.sp)
                doc?.let {
                    if (it.description.isNotBlank()) Text(it.description, color = t.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    if (it.body.isNotBlank()) AssistantBody(it.body)
                    if ((text?.length ?: 0) > 131_072) Text("文件较长，只显示前 128 KiB。", color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = { TextButton(close) { Text("关闭") } },
    )
}
