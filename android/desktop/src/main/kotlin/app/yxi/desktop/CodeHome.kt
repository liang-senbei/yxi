package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.PermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Code 风格新会话页的草稿，放在 [AppState.newSessionDraft]：切风格、切页面都不丢（PRD §4.1）。
 * 目标主机就是当前主机（[AppState.conn]），这里不另存；文件夹记着是哪台主机的，换了主机就回到那台的默认值。
 */
internal class NewSessionDraft {
    var text by mutableStateOf(TextFieldValue())
    private var directory by mutableStateOf("")
    private var directoryHost by mutableStateOf("")
    /** RunnerCatalog 的 id；[CODEX_WORKBENCH] 表示「在对话工作台继续」（结构化 Codex，不建 tmux 会话）。 */
    var agent by mutableStateOf("claude")
    var worktree by mutableStateOf(false)
    var permission by mutableStateOf(PermissionMode.Manual)
    /** 每次「新建会话」（Ctrl+N、侧栏、托盘、收藏）加一：CodeHome 已经开着也把焦点放回输入卡。 */
    var focusTick by mutableStateOf(0)
    /** 从收藏进来时带着，只对那台主机算数：创建成功后按收藏改名、挪收藏（和经典对话框一致）。 */
    private var favorite by mutableStateOf<FavoriteLaunch?>(null)
    private var favoriteHost by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")

    fun directoryFor(c: Conn): String = if (directoryHost == c.host.id) directory else recentDirectories(c).firstOrNull().orEmpty()
    fun setDirectory(c: Conn, path: String) { directory = path; directoryHost = c.host.id }
    fun favoriteFor(c: Conn): FavoriteLaunch? = favorite?.takeIf { favoriteHost == c.host.id }

    /** 新建会话的入口都走这里；从收藏来的带上收藏的目录和运行器（未知运行器按 Claude Code，同经典）。 */
    fun begin(c: Conn, from: FavoriteLaunch?) {
        favorite = from; favoriteHost = if (from == null) "" else c.host.id
        if (from != null) { setDirectory(c, from.directory); agent = from.agent.takeIf { RunnerCatalog.find(it) != null } ?: "claude" }
        if (agent in HomeStructured) worktree = false
        error = ""; focusTick++
    }
    /** 建好以后清空，下次回到这里是新的一页；运行器留着（和参考端的模型选择一样沿用上次）。 */
    fun reset() {
        text = TextFieldValue(); directory = ""; directoryHost = ""; worktree = false; permission = PermissionMode.Manual
        favorite = null; favoriteHost = ""; error = ""; requestKey = null
    }

    // 同一份输入重试时沿用同一个启动请求标识（同经典对话框）：上次其实已经开起来的话，服务器按标识认出来，不会再开一个
    private var requestKey: List<Any?>? = null
    private var requestId = ""
    fun requestIdFor(key: List<Any?>): String {
        if (key != requestKey) { requestKey = key; requestId = DesktopLaunchPlan.newRequestId() }
        return requestId
    }

    companion object {
        const val CODEX_WORKBENCH = "codex-workbench"

        /** 最近用过的项目目录：按会话最后活动时间，去重，最多 5 个。 */
        fun recentDirectories(c: Conn): List<String> =
            c.sessions.sortedByDescending { it.lastActivity }.map { it.cwd.trimEnd('/') }.filter { it.startsWith('/') && it.length > 1 }.distinct().take(5)
    }
}
/** 结构化运行器：提示词先进工作台草稿、不建 tmux 会话，也不支持独立 worktree（同经典对话框）。 */
private val HomeStructured = setOf("opencode", "gemini", "grok", "hermes", NewSessionDraft.CODEX_WORKBENCH)

/**
 * Code 风格的新会话页（规格 §3.8、§3.2）：左对齐的问候行，输入卡停在底部，上面一行 chips（环境 ｜ 文件夹 ｜ 分支 + worktree）。
 * 目标主机就是当前主机（[AppState.conn]）；创建用 App 级 [scope]，切页面、切风格都不取消（PRD §4.1）。
 * 经典欢迎页的入口：「新建任务」就是这一页，「服务器 / 切换任务 / 继续工作」在侧栏；延后的线路状态照放。
 */
@Composable
internal fun CodeHome(state: AppState, actions: HostActions, scope: CoroutineScope) {
    val d = state.newSessionDraft
    val c = state.conn
    val canSend = c != null && c.status == Conn.Status.Connected && !d.busy &&
        (if (d.agent == NewSessionDraft.CODEX_WORKBENCH) !d.worktree else d.directoryFor(c).trim().startsWith('/'))
    val focus = remember { FocusRequester() }
    LaunchedEffect(d.focusTick) { runCatching { focus.requestFocus() } }
    CodeHomeFrame(state, notes = { CodeHomeNotes(d, c) }, chips = { CodeHomeChips(state, actions, d, c) }, footer = { CodeHomeFooter(d) }) {
        CodeComposerCard(
            d.text, { d.text = it }, "描述要做的事，按 Enter 开始（可留空）", focus, canSend,
            onSend = { if (c != null && canSend) sendNewSession(state, actions, scope, c) },
            modifier = Modifier.fillMaxWidth(), readOnly = d.busy,
        )
    }
}

/** 新会话页的骨架，SSH（[CodeHome]）和本机（[CodeLocalHome]）共用：问候行在上；说明 → chips → 输入卡 → 页脚贴底。 */
@Composable
internal fun CodeHomeFrame(
    state: AppState,
    notes: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    card: @Composable () -> Unit,
) {
    val t = Tokens.current
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 768.dp).fillMaxSize()) {
            Spacer(Modifier.height(52.4.dp))   // 页面起于 y 36；规格图标顶 91.2（§3.8），实测 92.0：52.4 和 Row 里居中的 2.8 各进 0.5px，在 §7 的 ±1px 内
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("icon.png"), null, Modifier.size(22.4.dp))
                Spacer(Modifier.width(6.4.dp))   // 文字起于 812.8
                Text("今天想做点什么？", color = t.textPrimary, fontSize = 20.sp, lineHeight = 28.sp, maxLines = 1)
            }
            if (state.deferredRoute != null || state.deferredRouteNotice.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                DeferredRouteStatus(state)
            }
            Spacer(Modifier.weight(1f))
            notes()
            chips()
            Spacer(Modifier.height(10.4.dp))   // chips 991.2–1015.2，卡 1025.6–1069.6，页脚 1072–1100，窗底 1104
            card()
            Spacer(Modifier.height(2.4.dp))
            footer()
            Spacer(Modifier.height(4.dp))
        }
    }
}
/** 输入卡上方的说明：连接状态、收藏来源、worktree 和运行器的说明（沿用经典对话框的文案）、进度和失败原因。 */
@Composable
private fun CodeHomeNotes(d: NewSessionDraft, c: Conn?) {
    val notes = mutableListOf<Pair<String, Boolean>>()
    if (c == null) notes += "先在左侧选一台主机并连上，再新建会话" to false
    else {
        if (c.status != Conn.Status.Connected)
            notes += (c.host.label + " · " + c.status.label.ifBlank { "未连接" } + (if (c.error.isBlank()) "" else "：" + c.error)) to (c.status == Conn.Status.Failed)
        d.favoriteFor(c)?.let { notes += "按收藏「${it.title}」的文件夹和运行器新建" to false }
        if (d.worktree) notes += "上方文件夹作为源仓库，在仓库旁创建独立目录并从当前 HEAD 开始；不带入未提交改动，不自动合并或清理。" to false
        when {
            d.agent == NewSessionDraft.CODEX_WORKBENCH -> notes += "对话工作台支持排队、引导和侧栏预览；暂需使用已存在的目录，提示词先进入草稿，由你确认发送。" to false
            d.agent in HomeStructured -> notes += "使用已存在的服务器目录，连接原生运行器后创建对话；提示词先保存为草稿，确认发送后才开始工作。" to false
            d.text.text.isNotBlank() -> notes += "创建后会把这段内容直接交给运行器，可能立即开始工作。" to false
        }
    }
    if (d.busy) notes += "正在检查运行器并创建会话…" to false
    if (d.error.isNotBlank()) notes += d.error to true
    CodeNoteLines(notes)
}

/** 说明行：弱色，失败原因用 danger 色；没有就不占位。SSH 和本机首页共用。 */
@Composable
internal fun CodeNoteLines(notes: List<Pair<String, Boolean>>) {
    if (notes.isEmpty()) return
    val t = Tokens.current
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        notes.forEach { (text, bad) -> Text(text, color = if (bad) t.danger else t.textMuted, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

/** chips 行：环境 ｜ 文件夹 ｜ 分支 + worktree，间隔 5.6；没选主机时只有环境。 */
@Composable
private fun CodeHomeChips(state: AppState, actions: HostActions, d: NewSessionDraft, c: Conn?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.6.dp), verticalAlignment = Alignment.CenterVertically) {
        CodeEnvChip(state, actions, c)
        if (c != null) {
            CodeFolderChip(d, c)
            CodeBranchChip(d)
        }
    }
}

/** 环境 chip：本机或已添加的主机（PRD §5.1）。选主机就连上并切过去，和经典侧栏的主机菜单是同一套动作；添加、导入在侧栏的主机菜单里。[local]：本机首页（规格 §3.8：本机 = 笔记本图标）。 */
@Composable
internal fun CodeEnvChip(state: AppState, actions: HostActions, c: Conn?, local: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    Box {
        if (local) CodeChip("本机", Icons.Outlined.Laptop, { open = true }, held = open)
        else CodeChip(c?.host?.label ?: "选择主机", Icons.Outlined.Dns, { open = true }, held = open)
        if (open) CodePopup(close, side = PopupSide.Above, gap = 5.6.dp, width = 200.dp) {
            CodeMenuItem("本机", { close(); state.selectLocal() }, checked = local)
            actions.hosts.forEach { h ->
                val hc = actions.connOf(h)
                val status = if (hc == null) "未连接" else if (hc.status == Conn.Status.Connected) null else hc.status.label.ifBlank { "未连接" }
                CodeMenuItem(h.label, {
                    close()
                    if (hc == null || hc.status == Conn.Status.Failed) actions.connect(h)
                    actions.connOf(h)?.let { n -> if (state.conn !== n) state.select(n, null) }
                }, trailing = status, checked = c?.host?.id == h.id)
            }
        }
    }
}
/** 文件夹 chip：最近用过的 5 个项目目录，外加「打开文件夹…」手填服务器上的路径（主机上没有原生的选文件夹对话框）。 */
@Composable
private fun CodeFolderChip(d: NewSessionDraft, c: Conn) {
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val current = d.directoryFor(c).trim()
    val recent = NewSessionDraft.recentDirectories(c)
    Box {
        CodeHint(current.ifBlank { "还没选文件夹" }) {
            CodeChip(
                if (current.isBlank()) "选择文件夹" else folderName(current), Icons.Outlined.Folder,
                if (d.busy) null else ({ open = true }), Modifier.widthIn(max = 220.dp), held = open,
            )
        }
        if (open) CodePopup(close, side = PopupSide.Above, gap = 5.6.dp, width = 191.2.dp) {   // §3.1 ≈191
            if (recent.isNotEmpty()) {
                CodeMenuCaption("最近")
                val names = recent.groupingBy(::folderName).eachCount()
                recent.forEach { path ->
                    val name = folderName(path)
                    CodeMenuItem(name, { close(); d.setDirectory(c, path) },
                        trailing = parentName(path).takeIf { (names[name] ?: 0) > 1 }, checked = path == current.trimEnd('/'))
                }
                CodeMenuDivider()
            }
            CodeMenuItem("打开文件夹…", { close(); typing = true })
        }
    }
    if (typing) CodeFolderDialog(current, d.agent in HomeStructured, { typing = false }) { d.setDirectory(c, it); typing = false }
}

/** 浮层里的分组标题：弱色、不可点，高度同单行项。 */
@Composable
internal fun CodeMenuCaption(title: String) {
    Box(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.8.dp), contentAlignment = Alignment.CenterStart) {
        Text(title, color = Tokens.current.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
    }
}

/** 悬停提示放在控件上方：chips 和页脚都贴着窗口底边，放下面会被挡住。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CodeHint(label: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = { CodeTooltip(label) }, delayMillis = 500,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.TopCenter, Alignment.TopCenter, DpOffset(0.dp, (-5).dp)),
        content = content,
    )
}

/** 路径的最后一段；"/" 本身就是 "/"。 */
private fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path.ifBlank { "/" } }
/** 上一级目录名：最近列表里有同名文件夹时用来区分。 */
private fun parentName(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "/" }
/** 「打开文件夹…」：手填服务器上的绝对路径，校验同 DesktopLaunchPlan；说明沿用经典对话框。 */
@Composable
private fun CodeFolderDialog(initial: String, structured: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf("") }
    val pick: () -> Unit = {
        val p = path.trim()
        if (p.startsWith('/') && p.none { it.isISOControl() }) { onPick(p) } else { problem = "请输入服务器上的绝对路径，不含控制字符" }
    }
    WorkbenchDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(path, { path = it; problem = "" }, singleLine = true, label = { Text("服务器工作目录") }, placeholder = { Text("/opt/workspace/…") })
                Text(if (structured) "使用已存在的服务器目录，连接原生运行器后创建对话。" else "目录不存在会创建；已有任务继续运行。", style = MaterialTheme.typography.bodySmall)
                if (problem.isNotBlank()) Text(problem, color = Tokens.current.danger, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(pick) { Text("打开") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/**
 * 分支 chip：组合时不跑 git，只显示「当前分支」，要换分支在终端里切；worktree 勾上后在仓库旁开独立工作树（同经典）。
 * 结构化运行器不支持独立 worktree，勾选框置灰；勾上以后竖线消失（规格 §3.8）。
 */
@Composable
private fun CodeBranchChip(d: NewSessionDraft) {
    val p = CodePalette.current
    CodeHint("用服务器上当前检出的分支；要换分支请在终端里切") {
        CodeChip("当前分支", Icons.AutoMirrored.Outlined.CallSplit, null, iconTint = p.branchGlyph, endPadding = 4.8.dp) {
            Spacer(Modifier.width(7.dp))
            Box(Modifier.width(hairline()).height(9.6.dp).alpha(if (d.worktree) 0f else 1f).background(p.chipBorder))
            CodeWorktreeToggle(d.worktree, enabled = !d.busy && d.agent !in HomeStructured) { d.worktree = it }
        }
    }
}

/** worktree 勾选：框离分隔线 5，「worktree」在框后 3.2、次色；点击区是整个 chip 高。 */
@Composable
private fun CodeWorktreeToggle(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = Tokens.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.height(24.dp).alpha(if (enabled) 1f else 0.45f).hoverable(source)
            .toggleable(checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodeCheckbox(checked, held = enabled && hovered)
        Spacer(Modifier.width(3.2.dp))
        Text("worktree", color = if (enabled && hovered) t.textPrimary else t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}
/** 页脚：左边权限（只有 Claude Code 有，同经典）；右边运行器（替代参考端的模型）和上下文环（新会话还没有用量，整环灰）。 */
@Composable
private fun CodeHomeFooter(d: NewSessionDraft) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (d.agent == "claude") CodePermissionChip(d)
        Spacer(Modifier.weight(1f))
        CodeRunnerChip(d)
        Spacer(Modifier.width(9.6.dp))
        CodeContextRing(null)
        Spacer(Modifier.width(4.dp))
    }
}

/** 权限：每次新建都从「请求批准」开始（reset 时重置）；浮层 280 宽、向上展开，左缘对齐输入卡（规格 §3.1）。 */
@Composable
private fun CodePermissionChip(d: NewSessionDraft) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val p = CodePalette.current
    Box {
        CodeFooterChip(d.permission.title, { open = true }, color = if (d.permission == PermissionMode.Manual) p.manual else Tokens.current.textPrimary,
            held = open, enabled = !d.busy)
        if (open) CodePopup(close, side = PopupSide.Above, gap = 2.4.dp, width = 280.dp) {
            PermissionMode.entries.forEach { mode ->
                CodeMenuOption(mode.title, mode.description, { close(); d.permission = mode }, checked = mode == d.permission)
            }
        }
    }
}

/** 运行器选项：目录里的运行器，Codex 后面多一项「对话工作台」（经典对话框里的「在对话工作台继续」）。 */
private val RunnerChoices: List<Pair<String, String>> = RunnerCatalog.entries.flatMap { e ->
    if (e.id == "codex") listOf(e.id to e.title, NewSessionDraft.CODEX_WORKBENCH to "Codex · 对话工作台") else listOf(e.id to e.title)
}

/** 运行器 chip：浮层右缘对齐 chip、向上展开；选结构化运行器时顺带取消 worktree（它们不支持）。 */
@Composable
private fun CodeRunnerChip(d: NewSessionDraft) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    Box {
        CodeHint("运行器沿用服务器的登录与权限设置") {
            CodeFooterChip(RunnerChoices.firstOrNull { it.first == d.agent }?.second ?: d.agent, { open = true }, held = open, enabled = !d.busy)
        }
        if (open) CodePopup(close, side = PopupSide.Above, alignEnd = true, gap = 2.4.dp, width = 240.dp) {
            RunnerChoices.forEach { (id, title) ->
                CodeMenuItem(title, { close(); d.agent = id; if (id in HomeStructured) d.worktree = false }, checked = id == d.agent)
            }
        }
    }
}
/** 点发送时才探测运行器是否装好（组合时不 exec）；判法、超时同经典对话框：超时和其他异常都算「无法确认」。 */
private suspend fun probeRunner(c: Conn, agent: String): String = try {
    withTimeout(10000) { c.ssh.exec(RunnerCatalog.probeCommand(agent)).trim() }.takeIf { it in setOf("available", "missing") } ?: "unknown"
} catch (e: TimeoutCancellationException) { "unknown" }
catch (e: CancellationException) { throw e }
catch (e: Exception) { "unknown" }

/**
 * 发送 = 经典对话框的「开起来」，创建路径相同：对话工作台直接进；其余先探测，OpenCode / ACP 进各自的对话页（提示词存成草稿），
 * Claude Code / Codex 建 tmux 会话后交给 [HostActions.sessionCreated]（默认名、收藏跟过去）。成功才清草稿，失败留着原样和原因。
 */
private fun sendNewSession(state: AppState, actions: HostActions, scope: CoroutineScope, c: Conn) {
    val d = state.newSessionDraft
    val directory = d.directoryFor(c).trim()
    val prompt = d.text.text
    val agent = d.agent
    if (agent == NewSessionDraft.CODEX_WORKBENCH) { d.reset(); state.prepareCodexTask(c, directory, prompt); return }
    val runner = RunnerCatalog.find(agent) ?: return
    val worktree = d.worktree
    val permission = d.permission.takeIf { agent == "claude" }
    val favorite = d.favoriteFor(c)
    d.busy = true; d.error = ""
    scope.launch {
        try {
            when (probeRunner(c, agent)) {
                "available" -> Unit
                "missing" -> error("服务器未找到 ${runner.title}，请先安装并登录")
                else -> error("无法确认 ${c.host.label} 上 ${runner.title} 的安装状态，请检查连接后重试")
            }
            when (agent) {
                "opencode" -> { d.reset(); state.prepareOpenCodeTask(c, directory, prompt) }
                "gemini", "grok", "hermes" -> { d.reset(); state.prepareAcpTask(c, agent, directory, prompt) }
                else -> {
                    val registry = state.sharedMcp
                    check(registry.problem.isBlank()) { registry.problem }
                    val shared = registry.forHost(projectKey(c.host, "/")).filter { agent in it.desiredRunners }
                    val requestId = d.requestIdFor(listOf(c.host.id, directory, agent, prompt, worktree, permission, shared))
                    createSession(c, DesktopLaunchPlan(directory, agent, requestId, prompt, "", worktree, permission), shared).fold(
                        { s -> d.reset(); actions.sessionCreated(c, s, favorite) },
                        { err -> d.error = err.message.orEmpty().ifBlank { "创建没成功，请重试" } },
                    )
                }
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { d.error = e.message.orEmpty().ifBlank { "创建没成功，请重试" } }
        finally { d.busy = false }
    }
}
