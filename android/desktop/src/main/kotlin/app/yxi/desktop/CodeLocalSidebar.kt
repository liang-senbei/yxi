package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.SessionState
import java.io.File

/**
 * Code 侧栏在本机时的会话列表（替代经典的 [LocalWorkspaceSidebar]，经典侧栏不变）。最上面是置顶 / 收藏区（[CodeLocalSaved]，有了才出现）；
 * 再按文件夹分组：本地项目在前，每组先列 Yxi 建的本机对话（四个运行器，⋮ 菜单见 [localTaskMenu]），再列原生历史（只读，去掉 Yxi 已列出的同一会话）；
 * 末尾「加载更多历史」。全部 / 待处理 / 归档（[WorkspaceNavigation.mode]，和服务器共用）只管 Yxi 建的对话：不是「全部」时不列原生历史。
 * 文件夹不存在时标题后标「不可用」（同经典「目录已不可用」）。[query] 是去掉首尾空白的搜索词，在本地过滤；
 * 搜索框按 Enter 会去运行器里搜全部原生历史（[LocalWorkspace.query]），和它相同时原生历史不再按标题 / 目录二次过滤（以运行器的结果为准）。
 */
@Composable
internal fun CodeLocalSessions(state: AppState, query: String) {
    val w = state.localWorkspace
    val nav = state.navigation
    LaunchedEffect(w) { if (!w.scanned) w.refresh() }
    val all = localOwnedTasks(state)
    val owned = localSidebarTasks(nav, all, query) { localTaskState(state, it) }
    val showNative = nav.mode == "全部"
    val home = w.selectedRuntime?.home?.let { runCatching { File(it).canonicalPath }.getOrNull() }
    val threads = if (!showNative) emptyList() else nativeWithoutOwned(w.threads, all, home).filter {
        query.isEmpty() || query == w.query || it.title.contains(query, true) || it.directory.contains(query, true)
    }
    val ownedBy = owned.groupBy { File(it.directory).path }
    val threadsBy = threads.groupBy { File(it.directory).path }
    val folders = (w.projects.map { File(it).path }.filter { query.isEmpty() } + ownedBy.keys + threadsBy.keys).distinct()
    val onPage = state.page == Page.LocalWorkspace
    Column(Modifier.fillMaxWidth()) {
        CodeLocalSaved(state, all, query)
        CodeLocalListNotes(w, nav.mode, empty = owned.isEmpty() && threads.isEmpty(), searching = query.isNotEmpty())
        folders.forEach { folder ->
            CodeLocalFolderTitle(if (folder.isBlank()) "未分组" else localFolderName(folder), missing = folder.isNotBlank() && !File(folder).isDirectory)
            // 置顶会让行换位置，行里有按位置记的状态（hover、渐隐、浮层），按导航键定位
            ownedBy[folder].orEmpty().forEach { r -> key(r.key) { CodeLocalTaskRow(state, r, selected = onPage && state.localSelectedTaskKey == r.key) } }
            threadsBy[folder].orEmpty().forEach { thread ->
                CodeSessionRow(thread.title, "只读", SessionState.Idle, selected = onPage && state.localSelectedTaskKey == null && w.selectedThread?.id == thread.id) {
                    openNativeHistory(state, thread)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (showNative && w.next != null) CodeSessionRow(if (w.loading) "正在读取…" else "加载更多历史", null, dot = null, muted = true) { w.loadThreads(more = true) }
    }
}

/** Yxi 建的本机对话：四个运行器的记录（[localOwnedRecords]），未经整理过滤。 */
private fun localOwnedTasks(state: AppState): List<LocalCodexTaskRecord> = localOwnedRecords(
    state.localCodexTasks.registry.records + state.localOpenCodeTasks.registry.records + state.localAcpTasks.registry.records + state.localClaudeTasks.registry.records,
    System.getProperty("user.name"), System.getProperty("os.name"))

/** 本机用户和系统的记录，新的在前。不要求 Codex 的记录是当前运行器的数据目录（经典工作台列表要求）：对话页按记录的数据目录找运行器，找不到会说明原因。 */
internal fun localOwnedRecords(records: List<LocalCodexTaskRecord>, user: String, os: String): List<LocalCodexTaskRecord> =
    records.filter { it.user == user && it.platform == os }.sortedByDescending { it.createdAt }

/** 侧栏要列的本机对话：全部 / 待处理 / 归档（[WorkspaceNavigation.visible]）+ 搜索词（显示名称 / 原标题 / 目录 / 运行器名）；置顶的在前（按置顶次序，其余保持原顺序）。 */
internal fun localSidebarTasks(nav: WorkspaceNavigation, records: List<LocalCodexTaskRecord>, query: String, status: (LocalCodexTaskRecord) -> SessionState): List<LocalCodexTaskRecord> =
    records.filter { r ->
        nav.visible(r.key, status(r)) && (query.isEmpty() ||
            listOf(nav.title(r.key).orEmpty(), r.title, r.directory, LocalRuntimeDiscovery.title(r.engine)).any { it.contains(query, true) })
    }.sortedBy { nav.pinOrder(it.key) }

/** 原生历史去掉 Yxi 已经列出的同一会话（同一数据目录 [runtimeHome]、同一 threadId 的 Codex 记录，归档的也算），免得列两遍。 */
internal fun nativeWithoutOwned(threads: List<NativeHistoryThread>, owned: List<LocalCodexTaskRecord>, runtimeHome: String?): List<NativeHistoryThread> {
    val ids = owned.filter { it.engine == "codex" && it.runtimeHome == runtimeHome }.mapTo(HashSet()) { it.threadId }
    return if (ids.isEmpty()) threads else threads.filterNot { it.id in ids }
}

/** 本机对话的状态（供行首圆点）：key 在四个运行器里唯一，按各自控制器的字段判断（同 [AppState] 的忙碌计数），
 *  再算上排队指令（同 [openCodeTaskState]）。没有控制器（还没打开过）就是空闲。 */
private fun localTaskState(state: AppState, r: LocalCodexTaskRecord): SessionState {
    val pending = state.instructions.entries.filter { it.taskKey == r.key }
    val codex = state.localCodexTasks.controllers[r.key]
    val openCode = state.localOpenCodeTasks.controllers[r.key]
    val acp = state.localAcpTasks.controllers[r.key]
    val claude = state.localClaudeTasks.controllers[r.key]
    val waiting = codex?.pendingRequests?.isNotEmpty() == true || openCode?.permissions?.isNotEmpty() == true ||
        openCode?.questions?.isNotEmpty() == true || acp?.pendingApprovals?.isNotEmpty() == true ||
        claude?.pendingApprovals?.isNotEmpty() == true || pending.any { it.status == InstructionStatus.Unknown }
    val working = codex?.sending == true || codex?.activeTurnId != null || openCode?.busy == true || openCode?.nativeBusy == true ||
        acp?.busy == true || acp?.changingMode == true || claude?.busy == true || claude?.cancelling == true || claude?.changingModel == true ||
        pending.any { it.status == InstructionStatus.Delivering || it.runtimeTurnState == RuntimeTurnState.InProgress }
    return if (waiting) SessionState.NeedsYou else if (working) SessionState.Working else SessionState.Idle
}

/**
 * 一条 Yxi 建的本机对话：标题用整理记录里的显示名称，右端小字是运行器名；⋮ 菜单见 [localTaskMenu]，「修改显示名称」开 [CodeLocalRenameDialog]。
 * 点了选中它、切到本机工作台页（同经典工作台列表）。
 */
@Composable
private fun CodeLocalTaskRow(state: AppState, r: LocalCodexTaskRecord, selected: Boolean) {
    val nav = state.navigation
    val status = localTaskState(state, r)
    var rename by remember(r.key) { mutableStateOf(false) }
    CodeSessionRow(nav.title(r.key) ?: r.title, LocalRuntimeDiscovery.title(r.engine), status, selected = selected,
        menu = { close -> CodeMenuEntries(localTaskMenu(nav, r, status) { rename = true }, close) }) {
        state.localSelectedTaskKey = r.key; state.page = Page.LocalWorkspace
    }
    if (rename) CodeLocalRenameDialog(nav, r) { rename = false }
}

/** 改名弹窗：写法同 Code 会话头的改名弹窗和服务器托管任务的「修改显示名称」，只改 Yxi 里的显示；留空恢复原标题。 */
@Composable
private fun CodeLocalRenameDialog(nav: WorkspaceNavigation, r: LocalCodexTaskRecord, close: () -> Unit) {
    var title by remember(r.key) { mutableStateOf(nav.title(r.key) ?: r.title) }
    WorkbenchDialog(
        onDismissRequest = close,
        title = { Text("修改显示名称") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("名称") })
                Text("只改 Yxi 里的显示名称，不改运行器里的会话；留空恢复原标题。", style = MaterialTheme.typography.bodySmall)
                if (nav.error.isNotBlank()) Text(nav.error, color = Tokens.current.danger)
            }
        },
        confirmButton = { TextButton({ nav.rename(r.key, title); if (nav.error.isBlank()) close() }) { Text("保存") } },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
}

/**
 * 本机的置顶 / 收藏区：有置顶或收藏的本机对话时才出现（服务器的置顶区常显；本机没有就不占位置，有意偏差），放在本机列表最上面。
 * 外观和「置顶 / 收藏」的选择同服务器（[CodeSavedLook]，存 sidebarSavedKind）；过滤和排序同下面的列表（[localSidebarTasks]）。
 * 行右端小字是文件夹名（只有这一台电脑，不写主机名），没有 ⋮（同服务器的置顶区）。
 */
@Composable
private fun CodeLocalSaved(state: AppState, all: List<LocalCodexTaskRecord>, query: String) {
    val nav = state.navigation
    if (all.none { nav.pinned(it.key) || nav.favorite(it.key) }) return
    var choice by remember { mutableStateOf(Store.pref("sidebarSavedKind", "置顶").takeIf { it in listOf("置顶", "收藏") } ?: "置顶") }
    val choose: (String) -> Unit = { value -> choice = value; Store.setPref("sidebarSavedKind", value) }
    val look = CodeSavedLook
    look.header(choice, choose)
    val rows = localSidebarTasks(nav, all.filter { if (choice == "置顶") nav.pinned(it.key) else nav.favorite(it.key) }, query) { localTaskState(state, it) }
    val onPage = state.page == Page.LocalWorkspace
    rows.forEach { r ->
        val folder = if (r.directory.isBlank()) "未分组" else localFolderName(File(r.directory).path)
        look.row(SavedRow(r.key, nav.title(r.key) ?: r.title, folder, localTaskState(state, r), enabled = true, selected = onPage && state.localSelectedTaskKey == r.key) {
            state.localSelectedTaskKey = r.key; state.page = Page.LocalWorkspace
        })
    }
    if (rows.isEmpty()) look.note(when {
        query.isNotBlank() -> "没有匹配的${choice}会话"
        nav.mode != "全部" -> "没有${nav.mode}的${choice}会话"
        else -> "暂无${choice}会话，可在任务菜单中添加"
    }, false)
    look.end()
}

/** 文件夹分组标题：13 弱色起于 x 14.4，高 26（同 [CodeGroupTitle]）；文件夹不在了后面标「不可用」。 */
@Composable
private fun CodeLocalFolderTitle(name: String, missing: Boolean) {
    val t = Tokens.current
    Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 14.4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f, fill = false), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (missing) Text(" · 不可用", color = t.danger, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

/**
 * 会话行（规格 §3.5）：x 8、宽 268.8、高 26.4、圆角 6，行距 28；状态圆点直径 5.6、中心 x 22（[CodeStatusDot]）；
 * 标题 14 次色起于 x 38.4（选中变主色），过长不加省略号、右端渐隐：静止 8 宽止于 x 262，出 ⋮ 时 20 宽止于 254（伸进渐隐区才加）。
 * 选中 #EDECE8，hover #F0EFEC。停 500ms 在行右侧（离行右缘 2.4、垂直居中）出完整标题的 tooltip（[CodeRowTooltip]）。
 * [dot] 为 null 的是操作行（「加载更多历史」）：不画圆点、不出 tooltip。[trailing] 是右端的弱色小字（运行器名 / 只读 / 主机名），最宽 120、超出省略。
 * [menu] 是 ⋮ 浮层的内容：hover 或浮层开着时右端出 ⋮（中心 x 264，替掉 [trailing]）；为 null 不画（原生历史、置顶区和操作行没有逐行菜单）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CodeSessionRow(title: String, trailing: String?, dot: SessionState?, selected: Boolean = false, muted: Boolean = false,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null, onClick: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    if (menu != null) NativeOverlay(open)
    val showMenu = menu != null && (hovered || open)
    val fade = if (showMenu) 20.dp else 8.dp
    val fadePx = with(LocalDensity.current) { fade.toPx() }
    // 标题伸进渐隐区才加渐隐：离屏合成会改变文字的抗锯齿，短标题保持原样
    var reachesFade by remember { mutableStateOf(false) }
    val row = @Composable {
        Row(
            Modifier.fillMaxWidth().height(26.4.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selected) t.selected else if (hovered || open) t.hover else Color.Transparent)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .padding(start = 11.2.dp, end = if (showMenu) 2.8.dp else 14.8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) CodeStatusDot(dot, selected) else Spacer(Modifier.width(5.6.dp))
            Spacer(Modifier.width(13.6.dp))
            Box(Modifier.weight(1f).then(if (reachesFade) Modifier.fadeEnd(fade) else Modifier)) {
                Text(title, color = if (selected) t.textPrimary else if (muted) t.textMuted else t.textSecondary,
                    fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    onTextLayout = { reachesFade = it.getLineRight(0) > it.layoutInput.constraints.maxWidth - fadePx })
            }
            if (showMenu) Box {
                val button = remember { MutableInteractionSource() }
                val buttonHovered by button.collectIsHoveredAsState()
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (buttonHovered || open) p.groupPlusHover else Color.Transparent)
                    .hoverable(button).clickable(interactionSource = button, indication = null, role = Role.Button) { open = true },
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.MoreVert, "会话操作", Modifier.size(14.dp), tint = t.textSecondary) }
                if (open) CodePopup({ open = false }, alignEnd = true, maxWidth = CodeSidebarMenuMax) { menu(this) { open = false } }   // 条目和经典共用，「运行中或待处理任务不能归档」放不进 200
            } else if (trailing != null) Text(trailing, Modifier.padding(start = 6.dp).widthIn(max = 120.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Box(Modifier.padding(start = 8.dp, end = 11.2.dp, bottom = 1.6.dp).fillMaxWidth()) {
        if (dot == null) row() else TooltipArea(tooltip = { CodeRowTooltip(title) }, modifier = Modifier.fillMaxWidth(), delayMillis = 500,
            tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(2.4.dp, 0.dp))) { row() }
    }
}

/** 状态圆点，直径 5.6：等你 = 实心琥珀（带 #F7BB3A 边）、干活中 = 实心青、刚跑完 = 实心铜、闲置 = 1dp 空心灰环（选中行上略深）。
 *  含义同 STYLE §1.2，颜色见 [CodePalette] 的 dot*。 */
@Composable
internal fun CodeStatusDot(state: SessionState, selected: Boolean) {
    val p = CodePalette.current
    val m = Modifier.size(5.6.dp).clip(CircleShape)
    when (state) {
        SessionState.NeedsYou -> Box(m.background(p.dotWaiting).border(0.8.dp, p.dotWaitingEdge, CircleShape))
        SessionState.Working -> Box(m.background(p.dotWorking))
        SessionState.Done -> Box(m.background(p.dotDone))
        SessionState.Idle -> Box(m.border(1.dp, if (selected) p.dotIdleSelected else p.dotIdle, CircleShape))
    }
}

/** 会话行的 tooltip：反色底白字 12，最宽 224（三行时 224×60），内容是完整标题（规格 §3.1）。 */
@Composable
private fun CodeRowTooltip(title: String) {
    Box(Modifier.widthIn(max = 224.dp).clip(RoundedCornerShape(6.dp)).background(CodePalette.current.tooltip).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** 右端 [width] 宽的渐隐（离屏合成后用 DstIn 蒙版把末端的内容淡出）。 */
private fun Modifier.fadeEnd(width: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx()
        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width), blendMode = BlendMode.DstIn)
    }

/** 列表上方的说明（弱色 12）：检测 / 读取中；正在看归档或搜索结果；空列表。[mode] 不是「全部」时只列 Yxi 建的对话，说明换成这一句。 */
@Composable
private fun CodeLocalListNotes(w: LocalWorkspace, mode: String, empty: Boolean, searching: Boolean) {
    val t = Tokens.current
    val notes = buildList {
        if (mode != "全部") {
            add(if (!empty) "只列${mode}的对话，原生历史不在其中" else if (searching) "没有找到符合条件的对话" else "没有${mode}的对话（原生历史不在其中）")
            return@buildList
        }
        if (w.detecting) add("正在检测本机运行器…") else if (w.loading && w.threads.isEmpty()) add("正在读取原生历史…")
        val scope = listOfNotNull("归档".takeIf { w.archived }, w.query.takeIf { it.isNotBlank() }?.let { "搜索「$it」" }).joinToString(" · ")
        if (scope.isNotEmpty()) add("原生历史：$scope")
        if (empty && w.scanned && !w.detecting && !w.loading) add(when {
            searching -> "没有找到符合条件的对话"
            w.selectedRuntime == null -> "还没有本机对话；安装并验证 Codex 后可读取原生历史"
            else -> "还没有本机对话"
        })
    }
    notes.forEach { Text(it, Modifier.padding(start = 14.4.dp, end = 12.dp, bottom = 6.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp) }
}
