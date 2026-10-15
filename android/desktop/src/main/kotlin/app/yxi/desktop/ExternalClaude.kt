package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.ChatItem
import app.yxi.agent.ClaudeSessions
import app.yxi.agent.PermissionMode
import app.yxi.agent.Session
import app.yxi.agent.Transcript
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M4s：SSH 主机上 **Yxi 之外起的 Claude Code 会话**（终端里的 `claude`、Claude App 的 Code 模式经 SSH 起的）在桌面这边的数据缝和动作。
 * 列表的读取、解析、去重都在 core 的 [ClaudeSessions]；这里放可替换的两个读取口（截图夹具不许碰网络，同 `BranchChanges.remote`），
 * 「在 Yxi 里继续」的进行中 / 出错状态（侧栏菜单和只读页都看得到），以及每个会话重试时复用的启动请求编号。
 *
 * 放在哪：Code 侧栏每台连着的主机组里、Yxi 会话（项目树）下面，一个「Claude Code 会话」分组（[CodeExternalClaudeGroup]）——
 * 服务器上的项目树按协作组分、不按文件夹分，这些会话不属于任何协作组，硬塞进去就得造一种新的分组；单独一组、行和别的会话行同一个样子，
 * 是最少的新概念（同本机侧栏把原生历史列成只读的行）。经典侧栏不变。
 */
internal object ExternalClaude {
    /** 只读页先载最近这么多条消息（[TranscriptBranchStart] 的计数：当前分支上的 user / assistant 行），「加载更早的对话」每次翻倍，最多 [MAX]。 */
    const val FIRST = 200
    const val MAX = 2000
    /** 读一次转录最多收这么多字节、服务器上最多跑这么多秒（本机再多等几秒）：超了就报错、留着已经载入的。 */
    const val TRANSCRIPT_BYTES = 24 * 1024 * 1024
    const val TRANSCRIPT_SECONDS = 30
    /** 侧栏一组先列这么多行，「显示更多」每次再加 [MORE]。 */
    const val SHOWN = 8
    const val MORE = 20

    /** 读这台主机的列表（[Conn.refreshClaudeSessions] 调）。可替换：截图夹具换成固定数据。 */
    internal var list: suspend (Conn) -> ClaudeSessions.Snapshot = { ClaudeSessions.read(it.ssh) }
    /** 读一个会话最近 [Int] 条消息的转录行（只读页调）。可替换，同上。 */
    internal var transcript: suspend (Conn, ClaudeSessions.Entry, Int) -> ExternalTranscript = ::remoteTranscript

    /** 正在「在 Yxi 里继续」的（[key] → true）和上一次失败的原因（[key] → 原因）。 */
    val continuing = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>()
    /** 同一个会话重试时复用同一个启动请求编号：上次其实建成了、只是没等到状态的话，再点一次会接上那个 tmux 会话，不会再起一个。 */
    private val requests = HashMap<String, String>()

    fun key(host: Host, id: String) = host.id + "\n" + id
    fun requestId(host: Host, id: String) = requests.getOrPut(key(host, id)) { DesktopLaunchPlan.newRequestId() }
    fun forgetRequest(host: Host, id: String) { requests.remove(key(host, id)) }

    /** 这台主机侧栏要列的行：读到的列表去掉 Yxi 此刻列着的 tmux 会话里跑的和刚在 Yxi 里接着跑的（[ClaudeSessions.visible]）。还没读成过是空的。 */
    fun rows(c: Conn): List<ClaudeSessions.Row> =
        c.claudeSessions?.let { ClaudeSessions.visible(it, c.sessions.map { s -> s.name }, c.claudeAdopted) }.orEmpty()

    /** 已经交给 Yxi 的 tmux 会话（刚接着跑的、或登记里在 Yxi 列着的 tmux 会话里跑着的）：只读页据此给「打开那个会话」。 */
    fun managedSession(c: Conn, id: String): Session? {
        val name = c.claudeAdopted[id] ?: c.claudeSessions?.registry?.firstOrNull { (it.sessionId == id || it.resumed == id) && it.tmux.isNotEmpty() && c.sessions.any { s -> s.name == it.tmux } }?.tmux
        return name?.let { n -> c.sessions.firstOrNull { it.name == n } }
    }

    /** 转录行里 user / assistant 的条数：等于请求的条数就说明更早的可能还有（[TranscriptBranchStart] 数到够数就停）。 */
    fun messages(lines: List<String>): Int = lines.count { line ->
        runCatching { JSONObject(line).optString("type") in setOf("user", "assistant") }.getOrDefault(false)
    }

    /** 行和会话头上的标题：脚本没找出标题（开头没有人说的话、也没有摘要）时用目录名，免得一列「没有标题」分不清。 */
    fun title(e: ClaudeSessions.Entry) = e.title.ifBlank { e.cwd.trimEnd('/').substringAfterLast('/').ifBlank { e.cwd } + " · 没有标题" }

    /** 截图夹具：每张图开头清空（读取口换成空列表、不碰网络），场景要的数据自己再放。 */
    fun clearForFixture() {
        list = { ClaudeSessions.Snapshot(emptyList(), emptyList()) }
        transcript = { _, _, _ -> ExternalTranscript(emptyList(), false) }
        continuing.clear(); errors.clear(); requests.clear()
    }

    /**
     * 服务器上的那份：按当前分支取最近 [count] 条（同 SSH 对话页首屏的 [TranscriptBranchStart] 那段脚本，按文件大小取快照，别处正在写也不会读到半行）；
     * 文件里认不出消息链（很老的格式）就退回末尾 [count] 行，那时「更早的」按行数判断（先数总行数）。路径是 [ClaudeSessions.parse] 核过的
     * `~/.claude/projects/<目录>/<编号>.jsonl`。
     *
     * ⚠️ 不走 [SshSession.exec]：那条占着连接的 exec 锁直到读完，大文件要扫好一阵，期间审批按键、发送、抓屏都排在后面；
     * 也不用 [TranscriptBranchStart.load]（同样走 exec、没有超时和上限）。这里开一条独立的 exec 流（锁只在开通道时占一下），
     * 服务器上套 `timeout`、本机 [withTimeout]、最多收 [TRANSCRIPT_BYTES] 字节（同 ClaudeSubscriptionProbe 的读法）；离开页面（协程取消）就关通道。
     */
    private suspend fun remoteTranscript(conn: Conn, entry: ClaudeSessions.Entry, count: Int): ExternalTranscript {
        check(conn.ssh.isConnected) { "服务器未连接" }
        val n = count.coerceIn(1, MAX)
        val raw = bounded(conn, "python3 -c ${Shell.q(TranscriptBranchStart.script)} ${Shell.q(entry.path)} $n snapshot")
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: error("没读到这个会话的历史（连接可能断了、读取超时，或文件刚被改写），请刷新重试")
        if (!result.isNull("start")) {
            val array = result.getJSONArray("lines")
            val lines = (0 until array.length()).map { array.getString(it) }
            return ExternalTranscript(lines, messages(lines) >= n)
        }
        // 认不出消息链：第一行是总行数，后面是末尾 n 行
        val tail = bounded(conn, "sh -c 'wc -l < \"${'$'}1\" && tail -n \"${'$'}2\" \"${'$'}1\"' sh ${Shell.q(entry.path)} $n").lines()
        val total = tail.firstOrNull()?.trim()?.toLongOrNull() ?: error("没读到这个会话的历史，请刷新重试")
        return ExternalTranscript(tail.drop(1).filter { it.isNotBlank() }, total > n)
    }

    /**
     * 跑一条命令、收它的输出：独立 exec 流，服务器上套 `timeout`（没有这个命令的主机照常跑，靠本机这边的超时），最多等 [TRANSCRIPT_SECONDS] + 5 秒、
     * 收 [TRANSCRIPT_BYTES] 字节，超了报错；结束（含取消）都关通道。exec 流不像 [SshSession.exec] 那样补 `~/.local/bin`，这里自己补上。
     */
    private suspend fun bounded(conn: Conn, command: String): String {
        val channel = conn.ssh.openExecStream("export PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\"; " +
            "if command -v timeout >/dev/null 2>&1; then timeout $TRANSCRIPT_SECONDS $command; else $command; fi")
        try {
            val bytes = withTimeout((TRANSCRIPT_SECONDS + 5) * 1000L) {
                try { runInterruptible(Dispatchers.IO) { channel.output.readNBytes(TRANSCRIPT_BYTES + 1) } }
                catch (e: java.io.InterruptedIOException) { currentCoroutineContext().ensureActive(); throw e }
            }
            check(bytes.size <= TRANSCRIPT_BYTES) { "这段历史超过 ${TRANSCRIPT_BYTES / 1024 / 1024} MB，没有载入；少载一些，或在服务器上查看" }
            return bytes.toString(Charsets.UTF_8)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { error("读这个会话的历史超时了（文件太大或连接太慢），请稍后刷新重试") }
        finally { channel.close() }
    }
}

/** 只读页一次读到的转录：[lines] 是转录行，[more] = 更早的可能还有（给「加载更早的对话」）。 */
internal data class ExternalTranscript(val lines: List<String>, val more: Boolean)

/**
 * 「在 Yxi 里继续」：在原目录起一个 Yxi 管的 tmux 会话跑 `claude --resume <编号>`（[DesktopLaunchPlan.resumeSessionId]），
 * 走新会话的同一条路（[createSession]）：权限模式取这台主机这个目录上次用的（同 Code 新会话页，没记过是「请求批准」），
 * 这台主机的共享 MCP 照样带上，线路是服务器上的配置、自然生效。成功后记进 [Conn.claudeAdopted]（它马上从外部列表里消失），
 * 新会话的显示名用原会话的标题。
 *
 * ⚠️ 动手前**再读一次列表**：这期间它可能在终端 / Claude App 里又跑起来了，或者已经被接进 Yxi —— 两个进程同时写一份会话会把历史写乱，
 * 所以只认刚读到的状态，拿不准的一律不接（[ClaudeSessions.Row.blocked]）；启动脚本里 tmux new-session 之前还会在服务器上再核一次（guard）。
 * 不碰原来那个进程：不发按键、不 attach 它的 tmux、不连它的 socket。
 */
internal suspend fun continueExternalClaude(state: AppState, c: Conn, row: ClaudeSessions.Row): Session {
    check(c.status == Conn.Status.Connected && c.ssh.isConnected) { "请先连接服务器" }
    val fresh = ExternalClaude.list(c)
    c.claudeSessions = fresh
    val now = ClaudeSessions.visible(fresh, c.sessions.map { it.name }, c.claudeAdopted).firstOrNull { it.entry.id == row.entry.id }
        ?: error("这个会话已经在 Yxi 里了，或者服务器上找不到它了；请刷新列表")
    now.blocked?.let { error(it) }
    val registry = state.sharedMcp
    check(registry.problem.isBlank()) { registry.problem }
    val shared = registry.forHost(projectKey(c.host, "/")).filter { "claude" in it.desiredRunners }
    val permission = continuePermission(c, now.entry)
    val plan = DesktopLaunchPlan(now.entry.cwd, "claude", ExternalClaude.requestId(c.host, now.entry.id), permissionMode = permission, resumeSessionId = now.entry.id)
    val session = createSession(c, plan, shared).getOrThrow()
    ExternalClaude.forgetRequest(c.host, now.entry.id)
    c.claudeAdopted[now.entry.id] = session.name
    val key = taskNavigationKey(c.host, session)
    if (state.navigation.title(key) == null && now.entry.title.isNotBlank()) state.navigation.rename(key, now.entry.title)
    return session
}

/** 接着跑用的权限模式：这台主机、这个目录上次在 Yxi 里建会话用的（[FolderPermissions]，计划 / 完全访问不记），没记过是「请求批准」。 */
internal fun continuePermission(c: Conn, entry: ClaudeSessions.Entry): PermissionMode = FolderPermissions.recall(c.host.id, entry.cwd) ?: PermissionMode.Manual

/**
 * Code 侧栏一台主机里的「Claude Code 会话」组（放在项目树下面、「收藏 · 未启用」上面）。连上之后读一次（[Conn.ensureClaudeSessions]），
 * 标题行右边的刷新图标手动再读；读到的是空的、又没出错就整组不画（不占地方，主机菜单里也能刷新）。只在「全部」里列（同本机的原生历史）。
 * 每行同别的会话行（[CodeSessionRow]）：标题、右端弱色来源（终端 / Claude App / SDK）、圆点 —— 在别处跑着的是实心（等你 / 干活中），历史是空心环。
 * 点一行打开只读页；⋮ 里是打开、在 Yxi 里继续（在跑的灰掉并说明原因）、复制会话编号 / 项目路径。搜索词按标题、目录、来源、分支过滤。
 */
@Composable
internal fun CodeExternalClaudeGroup(state: AppState, actions: HostActions, c: Conn, filter: String) {
    val nav = state.navigation
    LaunchedEffect(c, c.status) { c.ensureClaudeSessions() }
    val all = ExternalClaude.rows(c)
    val rows = if (nav.mode != "全部") emptyList() else all.filter { r ->
        filter.isEmpty() || listOf(r.entry.title, r.entry.cwd, r.origin, r.entry.branch).any { it.contains(filter, true) }
    }
    val error = c.claudeSessionsError.takeIf { filter.isEmpty() && nav.mode == "全部" }.orEmpty()
    if (rows.isEmpty() && error.isBlank()) return
    val groupKey = "external-claude:" + projectKey(c.host, "/")
    val closed = filter.isEmpty() && nav.collapsed(groupKey, false)
    var shown by remember(c) { mutableStateOf(ExternalClaude.SHOWN) }
    CodeExternalGroupTitle(closed, c.claudeSessionsLoading, c.status == Conn.Status.Connected, { nav.setCollapsed(groupKey, !closed) }) { c.refreshClaudeSessions() }
    if (closed) return
    if (error.isNotBlank()) CodeTreeNote(error, true)
    rows.take(shown).forEach { r ->
        // 行里有按位置记的状态（⋮ 开关、hover、渐隐），按会话编号定位
        key(r.entry.id) {
            val selected = state.page == Page.ExternalClaude && state.externalClaude == (c.host.id to r.entry.id)
            CodeSessionRow(ExternalClaude.title(r.entry), r.origin, r.state, selected = selected,
                menu = { close -> CodeExternalRowMenu(state, actions, c, r, close) }) { state.openExternalClaude(c, r.entry.id) }
        }
    }
    if (rows.size > shown) CodeSessionRow("显示更多（还有 ${rows.size - shown} 个）", null, dot = null, muted = true) { shown += ExternalClaude.MORE }
}

/** 组标题：样式同项目分组标题（上方 6、高 26、13 弱色起于 x 14.4，折叠时常显「>」、展开时 hover 才出「∨」）；右边只有一个刷新图标（中心 x 264，同「其余分组只有一个图标」那一格）。 */
@Composable
private fun CodeExternalGroupTitle(closed: Boolean, loading: Boolean, connected: Boolean, onToggle: () -> Unit, onRefresh: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(26.dp).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle)
            .padding(start = 14.4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Claude Code 会话", Modifier.weight(1f, fill = false), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (closed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, if (closed) "展开分组" else "折叠分组",
                Modifier.padding(start = 2.dp).size(14.dp), tint = if (closed || hovered) t.textMuted else Color.Transparent)
        }
        CodeIconButton(Icons.Outlined.Refresh, if (loading) "正在读取…" else "刷新 Claude Code 会话", enabled = connected && !loading, hoverFill = p.groupPlusHover, onClick = onRefresh)
    }
}

/** 一行的 ⋮：头是标题和「来源（长）· 目录」；不能接着跑的「在 Yxi 里继续」灰掉，下面一行写原因（[ClaudeSessions.Row.blocked]）。 */
@Composable
private fun ColumnScope.CodeExternalRowMenu(state: AppState, actions: HostActions, c: Conn, r: ClaudeSessions.Row, close: () -> Unit) {
    val busy = ExternalClaude.continuing[ExternalClaude.key(c.host, r.entry.id)] == true
    CodeMenuHeader(ExternalClaude.title(r.entry), r.originLong + " · " + r.entry.cwd)
    CodeMenuDivider()
    CodeMenuItem("打开（只读）", { close(); state.openExternalClaude(c, r.entry.id) })
    CodeMenuItem(if (busy) "正在接着跑…" else "在 Yxi 里继续", { close(); state.openExternalClaude(c, r.entry.id); actions.continueClaude(c, r) },
        enabled = r.blocked == null && !busy && c.status == Conn.Status.Connected)
    r.blocked?.let { CodeMenuNote(it) }
    CodeMenuDivider()
    CodeMenuItem("复制会话编号", { close(); copyToClipboard(r.entry.id) })
    CodeMenuItem("复制项目路径", { close(); copyToClipboard(r.entry.cwd) })
}

private fun copyToClipboard(text: String) {
    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
}

private val LAST = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 只读页（[Page.ExternalClaude]）：会话头（主机图标、标题、「只读 · 来源」；右边刷新和 ⋮）→ 对话（SSH 对话页 Code 外观同一套画法，
 * [CodeReadOnlyChatRow]）→ 停靠区（没有输入框：目录 / 分支 / 消息数 / 最后活动，状态说明，「在 Yxi 里继续」）。
 * 转录按当前分支先载最近 [ExternalClaude.FIRST] 条，顶上「加载更早的对话」每次翻倍、最多 [ExternalClaude.MAX] 条（同本机历史分页的用法）。
 * 在别处跑着的：说明「正在…里运行，这里只读」，不自动跟随，点刷新再读（列表和转录一起）；不发按键、不 attach、不连它的 socket。
 * 已经接进 Yxi 的：说明一句，给「打开那个会话」。
 */
@Composable
internal fun ExternalClaudePane(state: AppState, actions: HostActions) {
    val t = Tokens.current
    val target = state.externalClaude
    val c = target?.let { (host, _) -> state.conns.firstOrNull { it.host.id == host } }
    if (target == null || c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这个会话所在的服务器已经断开，请在左侧重新选择", color = t.textMuted, fontSize = 14.sp) }
        return
    }
    val id = target.second
    val key = ExternalClaude.key(c.host, id)
    val row = ExternalClaude.rows(c).firstOrNull { it.entry.id == id }
    val entry = row?.entry ?: c.claudeSessions?.history?.firstOrNull { it.id == id }
    val managed = if (row == null) ExternalClaude.managedSession(c, id) else null
    val connected = c.status == Conn.Status.Connected
    val origin = row?.originLong ?: ClaudeSessions.originOf(entry?.entrypoint.orEmpty(), long = true)
    val viewKey = "external-claude:$key"
    val view = state.transcriptView(viewKey)
    var count by remember(key) { mutableStateOf(ExternalClaude.FIRST) }
    var reload by remember(key) { mutableStateOf(0) }
    var items by remember(key) { mutableStateOf<List<ChatItem>?>(null) }
    var more by remember(key) { mutableStateOf(false) }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf("") }
    var toEnd by remember(key) { mutableStateOf(true) }
    var anchor by remember(key) { mutableStateOf<String?>(null) }
    var menu by remember(key) { mutableStateOf(false) }
    NativeOverlay(menu)
    val listState = remember(key) { LazyListState() }
    val openGroups = remember(key) { mutableStateListOf<String>() }
    // 连接状态也是键：断线时读不成（留着已经读到的、写原因），连回来自动再读一次
    LaunchedEffect(key, entry?.path, count, reload, connected) {
        val e = entry ?: return@LaunchedEffect
        loading = true; error = ""
        try {
            val got = ExternalClaude.transcript(c, e, count)
            items = withContext(Dispatchers.Default) { Transcript.parse(got.lines.asSequence()) }
            more = got.more
        } catch (ex: CancellationException) { throw ex }
        catch (ex: Exception) { error = ex.message.orEmpty().ifBlank { "读不到这个会话的历史" } }
        finally { loading = false }
    }
    val rows = remember(items) { groupToolRuns(items.orEmpty()) }
    // 首次读完、点了刷新：滚到最新；加载更早的：停在原来最上面那一行（下标 0 是分页那一项）
    LaunchedEffect(rows) {
        if (rows.isEmpty()) return@LaunchedEffect
        val at = anchor?.let { a -> rows.indexOfFirst { it.key == a } } ?: -1
        when {
            at >= 0 -> { listState.scrollToItem(at + 1); anchor = null }
            toEnd -> { listState.scrollToItem(rows.size + 1); toEnd = false }
        }
    }
    fun refresh() { toEnd = true; reload++; if (connected) c.refreshClaudeSessions() }
    Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
        Column(Modifier.fillMaxSize()) {
            CodeLocalSessionHeader(entry?.let(ExternalClaude::title) ?: "Claude Code 会话", entry?.cwd.orEmpty(), "只读 · $origin", host = c.host.label) {
                CodeIconButton(Icons.Outlined.Refresh, if (loading) "正在读取…" else "刷新", enabled = connected && !loading) { refresh() }
                Box {
                    CodeIconButton(Icons.Filled.MoreVert, "更多", held = menu) { menu = true }
                    if (menu) CodePopup({ menu = false }, alignEnd = true, gap = 6.4.dp) {
                        CodeMenuItem("复制会话编号", { menu = false; copyToClipboard(id) })
                        CodeMenuItem("复制项目路径", { menu = false; copyToClipboard(entry?.cwd.orEmpty()) }, enabled = entry != null)
                        CodeMenuDivider()
                        CodeMenuCaption("对话视图")
                        TranscriptView.entries.forEach { v -> CodeMenuItem(v.label, { menu = false; state.transcriptViews[viewKey] = v }, checked = v == view) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(40.dp, 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    item(key = "paging") {
                        CodeColumn(true) {
                            if (more && count < ExternalClaude.MAX) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CodeTextAction(if (loading) "正在加载…" else "加载更早的对话", {
                                    anchor = rows.firstOrNull()?.key; toEnd = false; count = minOf(count * 2, ExternalClaude.MAX)
                                }, enabled = !loading)
                            } else if (more) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CodeActionNote("只载入了最近 ${ExternalClaude.MAX} 条消息，更早的请在服务器上用 claude --resume 查看")
                            }
                        }
                    }
                    items(rows, key = { it.key }) { r ->
                        CodeColumn(true) {
                            CodeReadOnlyChatRow(r, view, r.key in openGroups) { if (r.key in openGroups) openGroups.remove(r.key) else openGroups.add(r.key) }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(1.dp)) }
                }
                if (rows.isEmpty()) Text(when {
                    entry == null -> "服务器上找不到这个会话了"
                    loading || items == null && error.isBlank() -> "正在读取对话…"
                    error.isNotBlank() -> "没读到对话"
                    else -> "这个会话还没有对话"
                }, Modifier.align(Alignment.Center), color = t.textMuted, fontSize = 14.sp)
                if (loading && items != null) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
            }
            CodeDock(true) {
                val busy = ExternalClaude.continuing[key] == true
                val meta = listOfNotNull(
                    entry?.branch?.takeIf { it.isNotBlank() }?.let { "分支 $it" },
                    entry?.let { (if (it.exact) "" else "约 ") + "${it.messages} 条消息" },
                    entry?.lastActivity?.takeIf { it > 0 }?.let { "最后活动 " + LAST.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) },
                ).joinToString(" · ")
                if (meta.isNotBlank()) Note(meta, t.textMuted)
                when {
                    managed != null -> Note("这个会话已经在 Yxi 里接着跑了，到那边继续聊。", t.textSecondary)
                    entry == null -> Note("服务器上找不到这个会话了（可能被删了或换了位置），刷新看看。", t.warning)
                    row?.live == true -> Note(row.liveNote + "。不会跟随更新，点右上角刷新看最新内容。", t.warning)
                    row?.blocked != null -> Note(row.blocked!!, t.warning)
                    else -> Note("在${if (origin.first().code < 128) " $origin " else origin}里起的会话，这里只读。「在 Yxi 里继续」会在原目录新开一个 Yxi 管的会话接着聊（claude --resume），原来的历史都在。", t.textMuted)
                }
                // 9.6 = Note 的 16 − chip 的 6.4：chip 的字和上面的提示字对齐
                Row(Modifier.padding(horizontal = 9.6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (managed != null) CodeFooterChip("打开 Yxi 里的会话", { state.select(c, managed) })
                    else if (row != null) {
                        CodeFooterChip(if (busy) "正在启动…" else "在 Yxi 里继续", { actions.continueClaude(c, row) }, enabled = row.blocked == null && !busy && connected)
                        when {
                            !connected -> CodeFooterNote("服务器未连接", t.textMuted)
                            row.live -> CodeFooterNote("运行结束后才能在 Yxi 里继续", t.textMuted)
                            row.blocked != null -> CodeFooterNote("暂时不能在 Yxi 里继续", t.textMuted)
                            else -> CodeFooterNote("权限：" + continuePermission(c, row.entry).title, t.textMuted)
                        }
                    }
                }
                ExternalClaude.errors[key]?.let { Note(it, t.danger) }
                if (error.isNotBlank()) Note(error, t.danger)
                Note("只列这台服务器上的 Claude Code 会话；claude.ai 网页 / 云端的会话不在服务器上，这里看不到。", t.textMuted)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
