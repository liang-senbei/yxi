package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.ChatItem
import app.yxi.agent.Live
import app.yxi.agent.Pending
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.agent.Transcript
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 右栏「对话」。历史读转录（权威），「此刻在等你选 / 在忙」读屏幕（唯一来源）—— 分工同手机端 ChatScreen。
 * 所有远端操作走 core 的 SessionProbe / TranscriptStream，这里不拼 tmux 命令。
 * 颜色一律 [Tokens]，文案照 design/desktop-reference.md §2.6。
 */
@Composable
fun ChatPane(conn: Conn, session: Session) {
    val ssh = conn.ssh
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    // ⚠️ 全部按 session.name 记，不按 Session 对象：看板每 5 秒换一份新对象（lastActivity 变了），按对象记会全部重置
    var items by remember(session.name) { mutableStateOf<List<ChatItem>>(emptyList()) }
    var status by remember(session.name) { mutableStateOf<String?>(null) }
    var pending by remember(session.name) { mutableStateOf<Pending?>(null) }
    var approval by remember(session.name) { mutableStateOf<Pair<String, Approval>?>(null) }   // 指纹 → 抓屏认出的工具名 / 命令
    var live by remember(session.name) { mutableStateOf(Live.IDLE) }
    var keyBusy by remember(session.name) { mutableStateOf(false) }          // 送了键、等屏幕换掉
    var awaitFp by remember(session.name) { mutableStateOf<String?>(null) }  // 等着被换掉的那块提示的指纹
    var draft by remember(session.name) { mutableStateOf(TextFieldValue()) }
    var sendErr by remember(session.name) { mutableStateOf<String?>(null) }
    var sending by remember(session.name) { mutableStateOf(false) }
    var stick by remember(session.name) { mutableStateOf(true) }              // 粘在底部：用户往上翻就停，点 ↓ 再粘上
    val openGroups = remember(session.name) { mutableStateListOf<String>() }
    val listState = remember(session.name) { LazyListState() }
    val seen = remember(session.name) { Seen() }
    val focus = remember { FocusRequester() }

    // 转录：找到这个会话的 jsonl，从最后 400 行的字节起点 tail -f，攒 300ms 一批增量解析。
    // 历史灌完（字节数够了）之前不上屏，免得看它从旧滚到新（手机端 #261 的教训）。
    // 断线：流断了不清屏，等连接回来从记下的字节位置接着尾随；转录文件换了（重开 / --resume）才整个重灌。
    LaunchedEffect(session.name, ssh) {
        status = "正在载入对话…"
        var file: String? = null
        var inc = Transcript.Incremental()
        var pos = 0L               // 下一次从这个字节接着尾随（收到就记，别等解析 —— 断在两者之间会重放一批）
        var expect = 0L
        var eaten = 0L
        var caughtUp = false
        var idle = 0
        val buf = ArrayList<String>()
        var bufBytes = 0L
        val lock = Any()
        launch {
            while (true) {
                delay(300)
                val (batch, bytes) = synchronized(lock) { (buf.toList() to bufBytes).also { buf.clear(); bufBytes = 0L } }
                if (batch.isNotEmpty()) {
                    idle = 0
                    val snap = withContext(Dispatchers.Default) { inc.add(batch.asSequence()); inc.snapshot() }
                    eaten += bytes
                    if (eaten >= expect) caughtUp = true
                    if (caughtUp) { items = snap; status = null }
                } else if (!caughtUp && expect == 0L && ++idle >= 10) {
                    // 不知道要灌多少时才按「3 秒没动静」放行
                    caughtUp = true; items = withContext(Dispatchers.Default) { inc.snapshot() }; status = null
                }
            }
        }
        while (true) {
            if (!ssh.isConnected) { if (file != null) status = "连接断了 —— 接上后自动续上"; delay(1_000); continue }
            // 会话名必须传：转录按 sessionId 找，会话里 cd 过一次按目录就找不到了
            val f = TranscriptStream.latestFor(ssh, session.cwd, session.name)
            if (f == null) { status = "这个会话里没找到 Claude Code 的转录（${session.cwd}）"; delay(5_000); continue }
            if (f != file) {
                // 拿不到就等着重试，绝不退回 0（那等于把几百 MB 的整份转录重放）
                val ts = TranscriptStream.tailStart(ssh, f, 400)
                if (ts == null) { status = "连接还没稳，正在重试…"; delay(2_000); continue }
                file = f; inc = Transcript.Incremental(); pos = ts.second
                expect = ts.first - ts.second; eaten = 0L; idle = 0; caughtUp = expect <= 0L
            }
            if (caughtUp) status = null   // 续上了：把「流断了」那条撤掉；首灌那条要等灌完才撤
            catching {
                TranscriptStream.streamFrom(ssh, f, pos).collect { line ->
                    if (line.isEmpty()) return@collect   // follow() 每 20 秒的心跳空行，不在文件里，不算字节
                    synchronized(lock) { buf += line; val n = line.toByteArray().size + 1L; bufBytes += n; pos += n }
                }
            }
            status = "转录流断了 —— 连接可能掉了，接上后自动续上"
            delay(2_000)
        }
    }

    // 「等你选 / 在忙」只有屏幕知道（tool_use 要等工具跑完才落转录）：推流优先，断了退回轮询，连接回来再试推流
    LaunchedEffect(session.name, ssh) {
        fun apply(p: Pending?, l: Live) {
            // 一轮结束 = 从忙变成等输入、且没有在等审批；只在这一下发，状态不变不重发
            if (seen.busy && !l.busy && p == null) Notify.notify("${session.short} 轮次完成", lastAssistant(items))
            seen.busy = l.busy
            pending = p; live = l
            if (awaitFp != null && p?.fingerprint != awaitFp) { awaitFp = null; keyBusy = false }   // 动作生效了就解锁
        }
        while (true) {
            if (ssh.isConnected) catching { SessionProbe.watchScreen(ssh, session.name).collect { (p, l) -> apply(p, l) } }
            // ⚠️ 断着的时候别抓屏：exec 断线返回空串不抛，空屏会被解析成「不忙、没在等」—— 审批卡消失、还假发一条「轮次完成」
            repeat(20) {
                if (ssh.isConnected) catching { SessionProbe.snapshot(ssh, session.name) }.onSuccess { (p, l) -> apply(p, l) }
                delay(if (live.busy || pending != null) 700 else 2_500)
            }
        }
    }

    // 新提示来了：再抓一次屏认工具名 + 命令原文（watchScreen 只给解析结果），顺手发一条通知（同一指纹只发一次）
    LaunchedEffect(pending?.fingerprint) {
        val p = pending ?: return@LaunchedEffect
        val a = approvalOf(catching { SessionProbe.peek(ssh, session.name, 120) }.getOrNull())
        approval = p.fingerprint to a
        if (seen.fp != p.fingerprint) {
            seen.fp = p.fingerprint
            val what = a.body.lineSequence().firstOrNull { it.isNotBlank() } ?: p.title.ifBlank { p.options.joinToString(" / ") { it.label } }
            Notify.notify("${session.short} 等待批准", (a.tool?.let { "$it：" } ?: "") + what.take(80))
        }
    }

    /** 送一个键（屏幕上写几号就送几号）；4 秒屏幕还没变就解锁，别永远灰着。 */
    fun sendKey(key: String, fp: String) = scope.launch {
        keyBusy = true; awaitFp = fp
        catching { SessionProbe.sendKey(ssh, session.name, key) }
        delay(4_000)
        if (awaitFp == fp) { awaitFp = null; keyBusy = false }
    }

    /** 多选 / 多题的提交：一路 → 走到复核页，再送「Submit answers」那个号（协议见 Prompt 的类注释）。 */
    fun submit(p: Pending) = scope.launch {
        keyBusy = true; awaitFp = null
        catching {
            var cur = p
            var n = 0
            while (!cur.review && n++ < 6) {
                SessionProbe.sendKey(ssh, session.name, "Right")
                cur = waitChange(cur.fingerprint) { pending } ?: cur
            }
            if (cur.review) {
                val submit = cur.options.firstOrNull { it.label.startsWith("Submit") }
                SessionProbe.sendKey(ssh, session.name, (submit?.number ?: 1).toString())
            }
        }
        keyBusy = false
    }

    val canAct = !keyBusy && conn.status == Conn.Status.Connected   // 断着时按钮灰掉：send-keys 会静默失败

    /** Enter（输入框空着）= 第一项 / 提交，Codex 的「Enter 批准」。 */
    fun approve() {
        val p = pending ?: return
        if (!canAct) return
        if (p.multiSelect || p.tabs.size > 1 || p.review) submit(p) else sendKey(p.options.first().number.toString(), p.fingerprint)
    }

    /** Esc = 拒绝项；没有拒绝项（AskUserQuestion / 多选）就送 Esc 本身（脚注写的 `Esc to cancel`）。 */
    fun reject() {
        val p = pending ?: return
        if (!canAct) return
        sendKey(p.options.firstOrNull { isReject(it.label) }?.number?.toString() ?: "Escape", p.fingerprint)
    }

    fun send() {
        val text = draft.text.trim()
        if (text.isEmpty() || sending) return
        draft = TextFieldValue(); sendErr = null; sending = true
        scope.launch {
            // ⚠️ exec 在连接断了时返回空串不抛，SessionProbe.send 会把「读不到屏」当成功 —— 先看连接活没活
            val ok = ssh.isConnected && catching { SessionProbe.send(ssh, session.name, text) }.getOrDefault(false)
            if (!ok) {
                sendErr = "没发出去，话给你留在输入框了"
                draft = TextFieldValue(text + draft.text.let { if (it.isBlank()) "" else "\n$it" })
            }
            sending = false
        }
    }

    val rows = remember(items) { groupToolRuns(items) }
    LaunchedEffect(rows, stick) { if (stick && rows.isNotEmpty()) listState.requestScrollToItem(Int.MAX_VALUE / 2, 100_000) }
    LaunchedEffect(session.name) { focus.requestFocus() }   // 焦点先落输入框，Enter / Esc 一开始就能批
    // 只认用户的滚动（程序滚到底那一下也会走这里，不过滤会把 stick 关掉）；往上翻 = 停跟随，翻回底 = 再粘上
    val scrollWatch = remember(session.name) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) stick = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !listState.canScrollForward) stick = true
                return Offset.Zero
            }
        }
    }

    Column(Modifier.fillMaxSize().background(t.surface2)) {
        // 会话头：名字 + 主机 + 连接徽标常驻（报告 §3.8）。重连本身归侧栏，这里只读状态 + 给个按钮
        SessionHeader(conn, session) { scope.launch(NonCancellable) { conn.connect() } }   // 切走面板也别把连接连一半
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(scrollWatch), state = listState,
                contentPadding = PaddingValues(16.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Group -> GroupCard(row.calls, open = row.key in openGroups) {
                            if (row.key in openGroups) openGroups.remove(row.key) else openGroups.add(row.key)
                        }
                        is ChatRow.One -> ItemView(row.item)
                    }
                }
            }
            if (items.isEmpty()) Text(status ?: "还没有对话", Modifier.align(Alignment.Center), style = BodyStyle, color = t.textMuted)
            if (!stick) TextButton(
                onClick = { stick = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).background(t.surface3, RoundedCornerShape(Radius)).border(1.dp, t.border, RoundedCornerShape(Radius)),
            ) { Text("↓ 回到底部", color = t.textPrimary) }
        }
        if (items.isNotEmpty()) status?.let { Note(it, t.textMuted) }
        if (live.busy) Note("✽ " + (live.status ?: "在想…"), t.accent)
        val p = pending
        val a = approval?.takeIf { it.first == p?.fingerprint }?.second ?: Approval(null, "")   // 抓屏还没回来就先只有标题
        if (p != null) ApprovalCard(p, a, busy = !canAct, onKey = { sendKey(it, p.fingerprint) }, onSubmit = { submit(p) })
        val hint = when {
            p == null -> "跟它说点什么… Enter 发送，Shift+Enter 换行"
            p.isPermission(a) -> "Enter 允许 · Esc 拒绝 · 想说别的直接打字"
            else -> "Enter 选第 1 项 · Esc 取消 · 想说别的直接打字"
        }
        sendErr?.let { Note(it, t.danger) }
        Row(Modifier.fillMaxWidth().padding(12.dp, 6.dp, 12.dp, 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft, { draft = it }, maxLines = 8, textStyle = BodyStyle, shape = RoundedCornerShape(RadiusComposer),
                placeholder = { Text(hint, color = t.textMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = t.surface2, unfocusedContainerColor = t.surface2,
                    focusedBorderColor = t.accent, unfocusedBorderColor = t.border, cursorColor = t.accent,
                    focusedTextColor = t.textPrimary, unfocusedTextColor = t.textPrimary,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                    when {
                        e.type != KeyEventType.KeyDown || draft.composition != null -> false   // 中文输入法正在组词时 Enter / Esc 归输入法
                        // 输入框空着时 Enter 归审批卡（Codex：Enter 批准）；有字就是发消息
                        enter && !e.isShiftPressed -> { if (draft.text.isBlank()) approve() else send(); true }
                        e.key == Key.Escape && pending != null -> { reject(); true }   // 没在等审批时 Esc 留给窗口壳
                        else -> false
                    }
                },
            )
            Button(onClick = ::send, enabled = draft.text.isNotBlank() && !sending, colors = primaryButton()) { Text(if (sending) "发送中…" else "发送") }
        }
    }
}

/** 通知去重用的两个「上次看到的」：审批指纹、忙不忙。不进 Compose 状态，改了不用重组。 */
private class Seen(var fp: String? = null, var busy: Boolean = false)

/** 「轮次完成」通知正文：最后一条助手文本前 60 字。 */
private fun lastAssistant(items: List<ChatItem>): String =
    items.lastOrNull { it is ChatItem.AssistantText }?.let { (it as ChatItem.AssistantText).markdown }
        ?.replace('\n', ' ')?.trim()?.take(60).orEmpty()

@Composable
private fun SessionHeader(conn: Conn, session: Session, onReconnect: () -> Unit) {
    val t = Tokens.current
    // 连过一次之后再见到 Connecting 就是「正在重新连接…」（侧栏以后加的 Reconnecting 也落进 else）
    var everConnected by remember(conn) { mutableStateOf(false) }
    LaunchedEffect(conn.status) { if (conn.status == Conn.Status.Connected) everConnected = true }
    val down = conn.status == Conn.Status.Failed || conn.status == Conn.Status.Idle
    val (color, label) = when {
        conn.status == Conn.Status.Connected -> t.success to "已连接"
        down -> t.danger to "已断开连接"
        everConnected -> t.warning to "正在重新连接…"
        else -> t.warning to "正在连接"
    }
    Row(Modifier.fillMaxWidth().background(t.surface1).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(session.short, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(conn.host.alias.ifBlank { conn.host.hostname }, fontSize = 12.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Dot(color)
        Text(label, fontSize = 12.sp, color = t.textSecondary)
        if (down) TextButton(onClick = onReconnect) { Text("重新连接", fontSize = 12.sp, color = t.accent) }
    }
    HorizontalDivider(color = t.border)
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, CircleShape))

@Composable
private fun Note(text: String, color: Color) = Text(text, Modifier.padding(16.dp, 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = color)

/** 等推流把屏幕换掉（指纹变了 / 面板没了），最多 [timeoutMs]；不自己抓屏，抓屏归 watchScreen 那条长连。 */
private suspend fun waitChange(before: String, timeoutMs: Long = 4_000, get: () -> Pending?): Pending? {
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < timeoutMs) {
        val cur = get()
        if (cur == null || cur.fingerprint != before) return cur
        delay(60)
    }
    return get()
}

// ── 条目渲染 ──

@Composable
private fun ItemView(item: ChatItem) = when (item) {
    is ChatItem.UserText -> UserBubble(item.text, queued = false)
    is ChatItem.Queued -> UserBubble(item.text, queued = true)
    is ChatItem.AssistantText -> MessageRow(item.markdown, user = false) { AssistantBody(item.markdown) }
    is ChatItem.Thinking -> Fold("思考过程", item.text)
    is ChatItem.ToolCall -> ToolCard(item)
    is ChatItem.Injected -> Fold(listOfNotNull(item.kind, item.from).joinToString(" · "), Transcript.clean(item.text))
    is ChatItem.ApiError -> Note("⚠ " + item.text, Tokens.current.danger)
    is ChatItem.Unknown -> Unit
}

/** 一条消息 + 悬停才浮现的「复制」（Claude 的 MessageActions）。按钮常驻只改透明度，免得布局跳。 */
@Composable
private fun MessageRow(copyText: String, user: Boolean, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().hoverable(src), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) Box(Modifier.weight(1f)) { content() }
        CopyButton(copyText, Modifier.alpha(if (hovered) 1f else 0f))
        if (user) content()
    }
}

@Composable
private fun CopyButton(text: String, modifier: Modifier = Modifier) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { delay(1_500); done = false } }
    IconButton(
        onClick = { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null); done = true },
        modifier = modifier.size(28.dp),
    ) { Icon(if (done) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "复制", Modifier.size(15.dp), tint = if (done) Tokens.current.success else Tokens.current.textMuted) }
}

@Composable
private fun UserBubble(text: String, queued: Boolean) {
    val t = Tokens.current
    MessageRow(text, user = true) {
        Column(Modifier.widthIn(max = 640.dp).background(if (queued) t.surface1 else t.userBubble, RoundedCornerShape(Radius)).padding(14.dp, 9.dp)) {
            if (queued) Text("排队中", fontSize = 11.sp, color = t.textMuted)
            SelectionContainer { Text(text, style = BodyStyle, color = t.textPrimary) }
        }
    }
}

/** 默认折起来的一段（思考过程 / 注入的系统消息）：一行标题，点开看全文。 */
@Composable
private fun Fold(title: String, body: String) {
    val t = Tokens.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        Text((if (open) "▾ " else "▸ ") + title, fontSize = 11.sp, color = t.textMuted)
        Text(body, fontSize = 12.sp, lineHeight = 16.sp, color = t.textSecondary, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolCard(c: ChatItem.ToolCall) {
    val t = Tokens.current
    var open by remember(c.key) { mutableStateOf(c.isError) }   // 失败的默认展开，那才是要看的
    val dot = when { c.isError -> t.danger; c.result == null -> t.accent; else -> t.textMuted }
    Column(Modifier.fillMaxWidth().card().clickable { open = !open }.padding(12.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(dot)
            Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary)
            Text(summary(c), style = CodeStyle, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(when { c.isError -> "失败"; c.result == null -> "进行中…"; else -> "完成" }, fontSize = 11.sp, color = dot)
        }
        if (open) {
            Mono(c.input.toString(2).take(3000))
            c.result?.let { Mono(it.take(4000)) }
        }
    }
}

/** 卡片底：surface1 + 描边 + 圆角（工具卡 / 合并卡共用）。 */
@Composable
private fun Modifier.card(): Modifier {
    val t = Tokens.current
    val shape = RoundedCornerShape(Radius)
    return clip(shape).background(t.surface1, shape).border(1.dp, t.border, shape)   // clip 让点击的水波纹也跟着圆角
}

@Composable
private fun Mono(text: String) = SelectionContainer {
    Text(text, Modifier.fillMaxWidth(), style = CodeStyle, color = Tokens.current.textPrimary)
}

/** 一串同名工具卡合成一张：`Bash × 7 · 2 失败`，点开铺成原来的小卡片。 */
@Composable
private fun GroupCard(calls: List<ChatItem.ToolCall>, open: Boolean, onToggle: () -> Unit) {
    val t = Tokens.current
    val bad = calls.count { it.isError }
    Column(Modifier.fillMaxWidth().card()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (bad > 0) t.danger else t.textMuted)
            Text(calls.first().name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary)
            Text("× ${calls.size}", fontSize = 11.sp, color = t.textSecondary)
            if (bad > 0) Text("$bad 失败", fontSize = 11.sp, color = t.danger)
            Text(if (open) "" else summary(calls.first()), style = CodeStyle, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (open) "收起" else "展开", fontSize = 11.sp, color = t.textMuted)
        }
        if (open) Column(Modifier.padding(8.dp, 0.dp, 8.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { calls.forEach { ToolCard(it) } }
    }
}

// ── 纯逻辑（抄手机端 ToolCards，去掉 UI 依赖）──

sealed interface ChatRow {
    val key: String
    data class One(val item: ChatItem) : ChatRow { override val key get() = item.key }
    /** key 取 last()：历史往前灌时组的头会变、尾不变，锚点才保得住（手机端修出来的，别改回 first） */
    data class Group(val calls: List<ChatItem.ToolCall>) : ChatRow { override val key get() = calls.last().key }
}

private val NEVER_GROUP = setOf("AskUserQuestion", "ExitPlanMode", "TodoWrite")

/** 连续 ≥ [min] 条同名、已完成的工具卡合成一组（出错的也合，卡上标失败数）；还在跑的那条不合，要看得见。 */
fun groupToolRuns(items: List<ChatItem>, min: Int = 3): List<ChatRow> {
    val out = ArrayList<ChatRow>(items.size)
    var i = 0
    while (i < items.size) {
        val it0 = items[i]
        if (it0 is ChatItem.ToolCall && it0.result != null && it0.name !in NEVER_GROUP) {
            var j = i
            while (j < items.size) {
                val c = items[j] as? ChatItem.ToolCall ?: break
                if (c.name != it0.name || c.result == null) break
                j++
            }
            if (j - i >= min) { out += ChatRow.Group(items.subList(i, j).map { it as ChatItem.ToolCall }); i = j; continue }
        }
        out += ChatRow.One(it0); i++
    }
    return out
}

/** 折叠时那一行摘要：命令取第一行、文件取文件名。 */
private fun summary(c: ChatItem.ToolCall): String {
    val i = c.input
    val raw = when {
        i.has("command") -> i.optString("command")
        i.has("file_path") -> i.optString("file_path").substringAfterLast('/')
        i.has("pattern") -> i.optString("pattern")
        i.has("description") -> i.optString("description")
        i.has("prompt") -> i.optString("prompt")
        i.has("path") -> i.optString("path").substringAfterLast('/')
        i.has("url") -> i.optString("url")
        else -> i.keys().asSequence().firstOrNull()?.let { i.optString(it) }.orEmpty()
    }
    return raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
}
