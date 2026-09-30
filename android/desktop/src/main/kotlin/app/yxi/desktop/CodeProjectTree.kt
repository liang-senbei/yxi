package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Code 侧栏的项目树外观（传给 [ProjectTree] 的 [TreeLook]）：选中、点击、菜单条目和改名 / 配置 / 组内新建对话框仍在
 * ProjectTree 里，两种风格共用，这里只管画（PRD §4.2）。会话行用 [CodeSessionRow]：右端小字是运行器名，状态只看圆点
 * （不写「未连接 / 正在运行」这类副标题，也不写时间和目录，有意偏差）；⋮ 浮层按经典 ⋯ 菜单的同一份条目画。
 */
internal val CodeTreeLook = TreeLook(
    note = { text, warning -> CodeTreeNote(text, warning) },
    group = { name, closed, onToggle, onCreate, menu -> CodeTreeGroupTitle(name, closed, onToggle, onCreate, menu) },
    // 行里有按位置记的状态（⋮ 开关、hover、渐隐），按任务键定位
    row = { r -> key(r.key) { CodeSessionRow(r.title, r.runner, r.state, selected = r.selected, menu = { close -> CodeMenuEntries(r.menu(), close) }, onClick = r.onClick) } },
)

/**
 * 项目分组标题（规格 §3.5）：高 26，名称 13 弱色起于 x 14.4，没有 hover 底；上方留 6，会话到下一个分组标题 ≈34。
 * 名称后是弱色箭头：折叠时常显「>」，展开时 hover 这一行才出「∨」（规格是 hover 标题或组内任一行，这里只看标题行）。
 * 点一下折叠 / 展开。右侧两个图标常显：「+」组内新建（中心 x 240，弹经典新建对话框，能带上分组）、「⋮」分组操作
 * （中心 x 264，右键同它）。规格里当前文件夹那组还有搜索 / 筛选，SSH 主机没有「当前文件夹」，不做（有意偏差）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeTreeGroupTitle(name: String, closed: Boolean, onToggle: () -> Unit, onCreate: () -> Unit, entries: () -> List<MenuEntry>) {
    val t = Tokens.current
    val p = CodePalette.current
    var menu by remember(name) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    NativeOverlay(menu)
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(26.dp).hoverable(source)
            .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { menu = true }
            .onClick(onClick = onToggle)
            .padding(start = 14.4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(name.ifEmpty { "未分组" }, Modifier.weight(1f, fill = false), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 箭头一直占位、不出现时透明，hover 进出不挤动名称
            Icon(if (closed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, if (closed) "展开分组" else "折叠分组",
                Modifier.padding(start = 2.dp).size(14.dp), tint = if (closed || hovered) t.textMuted else Color.Transparent)
        }
        CodeIconButton(Icons.Filled.Add, "在组内新建 Agent", hoverFill = p.groupPlusHover, onClick = onCreate)
        Box {
            CodeIconButton(Icons.Filled.MoreVert, "分组操作", held = menu, hoverFill = p.groupPlusHover) { menu = true }
            // 目录是组员的 cwd，长短不定：按内容宽到 CodeSidebarMenuMax，再长的路径从前面省略
            if (menu) CodePopup({ menu = false }, alignEnd = true, maxWidth = CodeSidebarMenuMax) { CodeMenuEntries(entries()) { menu = false } }
        }
    }
}

/** 项目树里的说明 / 出错行（正在读取分组、分组读取失败、打开任务失败、主机空态）：12 弱色（出错用警示色），同主机的失败说明。 */
@Composable
internal fun CodeTreeNote(text: String, warning: Boolean) {
    val t = Tokens.current
    Text(text, Modifier.padding(start = 14.4.dp, end = 12.dp, bottom = 4.dp), color = if (warning) t.warning else t.textMuted,
        fontSize = 12.sp, lineHeight = 17.sp)
}

/**
 * Code 侧栏的置顶 / 收藏区外观（传给 [SidebarSavedSessions] 的 [SavedLook]）：筛选、排序、打开和「置顶 / 收藏」的选择都在
 * SidebarSavedSessions 里，这里只管画（PRD §4.2）。行用 [CodeSessionRow]：这个列表跨主机，右端小字是主机名（经典副标题里的
 * 目录、「Codex」、「启动入口 / 未连接」不显示，有意偏差）；不带 ⋮（经典这里也没有逐行菜单，置顶 / 收藏在各行自己的菜单里改）。
 * 没连上的行弱色、点了不响应。区尾不画分隔线，留 6 再接主机标题（有意偏差）。
 */
internal val CodeSavedLook = SavedLook(
    header = { choice, choose -> CodeSavedTitle(choice, choose) },
    // 行里有按位置记的状态（hover、渐隐），按导航键定位
    row = { r -> key(r.key) { CodeSessionRow(r.title, r.host, r.state, selected = r.selected, muted = !r.enabled, onClick = { if (r.enabled) r.onClick() }) } },
    note = { text, warning -> CodeTreeNote(text, warning) },
    end = { Spacer(Modifier.height(6.dp)) },
)

/**
 * 置顶 / 收藏区的小标题，样式同项目分组标题（上方 6、高 26、13 弱色、x 14.4），名称后常显「∨」；hover 或浮层开着时字变次色。
 * 点开浮层在「置顶」「收藏」之间选，当前的打勾（经典是「置顶 ⌄」文字按钮 + 下拉菜单）。
 */
@Composable
private fun CodeSavedTitle(choice: String, choose: (String) -> Unit) {
    val t = Tokens.current
    var menu by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    NativeOverlay(menu)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(26.dp).padding(start = 14.4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            val tint = if (hovered || menu) t.textSecondary else t.textMuted
            Row(Modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button) { menu = true },
                verticalAlignment = Alignment.CenterVertically) {
                Text(choice, color = tint, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                Icon(Icons.Filled.KeyboardArrowDown, "切换置顶 / 收藏", Modifier.padding(start = 2.dp).size(14.dp), tint = tint)
            }
            if (menu) CodePopup({ menu = false }) {
                listOf("置顶", "收藏").forEach { value -> CodeMenuItem(value, { menu = false; choose(value) }, checked = value == choice) }
            }
        }
    }
}
