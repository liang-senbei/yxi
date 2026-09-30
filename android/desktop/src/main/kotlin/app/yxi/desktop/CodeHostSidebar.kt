package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.SessionState

/**
 * Code 侧栏里的一台主机（替代经典的 ClassicHostSection，筛选已在 [SidebarHostList] 里做完）：主机标题行（[CodeHostTitle]）
 * → 连接失败的说明和删旧指纹 → 项目树（经典的 [ProjectTree] 配 [CodeTreeLook]）→ 空态说明（条件同经典，见 [hostIsEmpty]）→ 未启用的收藏（[CodeHostFavorites]）。
 * 参考端侧栏没有主机这一层；Yxi 的主机有连接状态和连接 / 编辑 / 颜色等入口，所以每台加一行标题（有意偏差）。
 * 整组左缘 x 0–3 画连接颜色条（「连接颜色…」选的色），画在底下、不占位，行照旧从 x 8 起。
 */
@Composable
internal fun CodeHostGroup(state: AppState, actions: HostActions, section: HostSection) {
    val t = Tokens.current
    val h = section.host
    val c = section.conn
    val tint = h.tint(section.index)
    Column(Modifier.fillMaxWidth().drawBehind { drawRect(tint, size = Size(3.dp.toPx(), size.height)) }) {
        CodeHostTitle(h, c, actions)
        if (c?.status == Conn.Status.Failed) {
            Text(c.error, Modifier.padding(start = 14.4.dp, end = 12.dp, bottom = 4.dp), color = t.danger, fontSize = 12.sp, lineHeight = 17.sp)
            // 删旧指纹是单独一步；删完立刻重连，新指纹会再弹一次「确认这是 X 吗」让用户核对
            if (c.keyChanged) Box(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                CodeFooterChip("我确认过了，删除旧指纹", { actions.keys.forget(h); actions.connect(h) }, color = t.danger)
            }
        }
        // 断线重连中列表照旧摆着（服务器上的会话还在），不清；搜索时只画滤剩下的
        if (c != null) {
            ProjectTree(state, c, section.sessions, searching = section.filter.isNotEmpty(), query = section.filter, look = CodeTreeLook)
            if (hostIsEmpty(state, h, c, section.filter)) CodeTreeNote("这台机器上还没有会话", false)
        }
        CodeHostFavorites(actions, c, section.favorites, state.navigation)
        // 组尾留 6：下一台主机的标题和上一行拉开，同「会话 → 下一个分组标题」≈34（规格 §3.8）；颜色条跟着画满，相邻主机仍然连着
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 「收藏 · 未启用」（同经典 [HostFavorites]：收藏过、服务器上已经没有的会话）。小标题同项目分组标题（13 弱色、上方留 6）；
 * 每项一行 [CodeSessionRow]（空心圆点，右端运行器名），连着才能点，点了按收藏的目录 / Agent 新建，没连或正在打开时弱色。
 * 经典行上的目录和 × 挪进 ⋮：浮层顶上是标题和目录，下面「新建会话」「取消收藏」。
 */
@Composable
private fun CodeHostFavorites(actions: HostActions, c: Conn?, favorites: List<FavoriteLaunch>, nav: WorkspaceNavigation) {
    if (favorites.isEmpty()) return
    val t = Tokens.current
    val ready = c?.status == Conn.Status.Connected && !actions.openingFavorite
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(26.dp).padding(start = 14.4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("收藏 · 未启用", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
    favorites.forEach { favorite ->
        val open: () -> Unit = { if (ready) actions.openFavorite(c, favorite) }
        key(favorite.key) {
            CodeSessionRow(favorite.title, LocalRuntimeDiscovery.title(favorite.agent).takeIf { it.isNotBlank() }, SessionState.Idle, muted = !ready,
                menu = { close ->
                    CodeMenuHeader(favorite.title, favorite.directory)
                    CodeMenuDivider()
                    CodeMenuItem("新建会话", { close(); open() }, enabled = ready)
                    CodeMenuItem("取消收藏", { close(); nav.removeFavorite(favorite.key) })
                }, onClick = open)
        }
    }
}

/**
 * 主机标题行：高 26，起于 x 14.4（同 [CodeGroupTitle]）；名称 13 次色、中等字重（和项目分组标题区分开），
 * 后接弱色「 · 地区 · 状态」（没连过是「未连接」，连接失败用 danger 色）。没有 hover 底、不能折叠（同经典），当前主机不高亮。
 * 点一下：没连 / 连失败了就连，连着的不动（会话行才是入口）。右侧两个图标常显：「+」新会话（中心 x 240，连上才可用，
 * 走 [HostActions.creatingOn]，Code 下落到 CodeHome）、「⋮」主机操作（中心 x 264，右键同它），条目见 [hostMenu]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeHostTitle(h: Host, c: Conn?, actions: HostActions) {
    val t = Tokens.current
    val p = CodePalette.current
    var menu by remember { mutableStateOf(false) }
    NativeOverlay(menu)
    val st = c?.status ?: Conn.Status.Idle
    Row(
        Modifier.fillMaxWidth().height(26.dp)
            // 左右键分开用 onClick 配 matcher：右键只开菜单，不连带「连接」
            .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { menu = true }
            .onClick { if (c == null || st == Conn.Status.Failed) actions.connect(h) }
            .padding(start = 14.4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(h.label, Modifier.weight(1f, fill = false), color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.region.isNotBlank()) Text(" · ${h.region}", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
            Text(" · ${st.label.ifBlank { "未连接" }}", color = if (st == Conn.Status.Failed) t.danger else t.textMuted,
                fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
        }
        CodeIconButton(Icons.Filled.Add, "新会话", enabled = st == Conn.Status.Connected, hoverFill = p.groupPlusHover) { actions.creatingOn = c }
        Box {
            CodeIconButton(Icons.Filled.MoreVert, "主机操作", held = menu, hoverFill = p.groupPlusHover) { menu = true }
            if (menu) CodePopup({ menu = false }, alignEnd = true) { CodeMenuEntries(hostMenu(h, c, actions)) { menu = false } }
        }
    }
}

/** Code 样式的 ⋯ 菜单条目（和 [ClassicMenuEntries] 按同一份列表画）：条目 [CodeMenuItem]（先关菜单再执行）、
 *  小标题 [CodeMenuCaption]、标题 [CodeMenuHeader]、分隔线 [CodeMenuDivider]。 */
@Composable
internal fun CodeMenuEntries(entries: List<MenuEntry>, close: () -> Unit) {
    entries.forEach { e ->
        when (e) {
            is MenuEntry.Item -> CodeMenuItem(e.label, { close(); e.action() }, enabled = e.enabled, path = e.path)
            is MenuEntry.Header -> CodeMenuCaption(e.label)
            is MenuEntry.Title -> CodeMenuHeader(e.label)
            MenuEntry.Divider -> CodeMenuDivider()
        }
    }
}
