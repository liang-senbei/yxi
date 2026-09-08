package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.yxi.agent.ChatItem
import app.yxi.agent.Live
import app.yxi.agent.Pending
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.agent.Transcript
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 右栏「对话」。历史读转录（权威），「此刻在等你选 / 在忙」读屏幕（唯一来源）—— 分工同手机端 ChatScreen。
 * 所有远端操作走 core 的 SessionProbe / TranscriptStream，这里不拼 tmux 命令。
 */
@Composable
fun ChatPane(conn: Conn, session: Session) {
    val ssh = conn.ssh
    val scope = rememberCoroutineScope()
    // ⚠️ 全部按 session.name 记，不按 Session 对象：看板每 5 秒换一份新对象（lastActivity 变了），按对象记会全部重置
    var items by remember(session.name) { mutableStateOf<List<ChatItem>>(emptyList()) }
    var status by remember(session.name) { mutableStateOf<String?>(null) }
    var pending by remember(session.name) { mutableStateOf<Pending?>(null) }
    var live by remember(session.name) { mutableStateOf(Live.IDLE) }
    var keyBusy by remember(session.name) { mutableStateOf(false) }          // 送了键、等屏幕换掉
    var awaitFp by remember(session.name) { mutableStateOf<String?>(null) }  // 等着被换掉的那块提示的指纹
    var draft by remember(session.name) { mutableStateOf(TextFieldValue()) }
    var sendErr by remember(session.name) { mutableStateOf<String?>(null) }
    var sending by remember(session.name) { mutableStateOf(false) }
    var stick by remember(session.name) { mutableStateOf(true) }              // 粘在底部：用户往上翻就停，点 ↓ 再粘上
    val openGroups = remember(session.name) { mutableStateListOf<String>() }
    val listState = remember(session.name) { LazyListState() }

    // 转录：找到这个会话的 jsonl，从最后 400 行的字节起点 tail -f，攒 300ms 一批增量解析。
    // 历史灌完（字节数够了）之前不上屏，免得看它从旧滚到新（手机端 #261 的教训）。
    LaunchedEffect(session.name, ssh) {
        status = "正在载入对话…"
        // 会话名必须传：转录按 sessionId 找，会话里 cd 过一次按目录就找不到了
        val file = TranscriptStream.latestFor(ssh, session.cwd, session.name)
        if (file == null) { status = "这个会话里没找到 Claude Code 的转录（${session.cwd}）"; return@LaunchedEffect }
        var ts: Pair<Long, Long>? = null
        while (ts == null) {   // 拿不到就等着重试，绝不退回 0（那等于把几百 MB 的整份转录重放）
            ts = TranscriptStream.tailStart(ssh, file, 400)
            if (ts == null) { status = "连接还没稳，正在重试…"; delay(2_000) }
        }
        val expect = ts.first - ts.second
        val inc = Transcript.Incremental()
        val buf = ArrayList<String>()
        var bufBytes = 0L
        val lock = Any()
        var caughtUp = expect <= 0L
        var eaten = 0L
        var idle = 0
        var ended = false
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
                if (ended) {
                    if (!caughtUp) items = withContext(Dispatchers.Default) { inc.snapshot() }
                    status = "转录流断了 —— 连接可能掉了"
                    break
                }
            }
        }
        catching {
            TranscriptStream.streamFrom(ssh, file, ts.second).collect { line ->
                if (line.isEmpty()) return@collect   // follow() 每 20 秒的心跳空行，不在文件里，不算字节
                synchronized(lock) { buf += line; bufBytes += line.toByteArray().size + 1 }
            }
        }
        ended = true
    }

    // 「等你选 / 在忙」只有屏幕知道（tool_use 要等工具跑完才落转录）：推流优先，断了退回轮询
    LaunchedEffect(session.name, ssh) {
        fun apply(p: Pending?, l: Live) {
            pending = p; live = l
            if (awaitFp != null && p?.fingerprint != awaitFp) { awaitFp = null; keyBusy = false }   // 动作生效了就解锁
        }
        catching { SessionProbe.watchScreen(ssh, session.name).collect { (p, l) -> apply(p, l) } }
        while (true) {
            runCatching { SessionProbe.snapshot(ssh, session.name) }.onSuccess { (p, l) -> apply(p, l) }
            delay(if (live.busy || pending != null) 700 else 2_500)
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

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(scrollWatch), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp),
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
            if (items.isEmpty()) Text(
                status ?: "还没有对话", Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
            )
            if (!stick) FilledTonalButton(onClick = { stick = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("↓ 回到底部") }
        }
        if (items.isNotEmpty()) status?.let { Note(it, MaterialTheme.colorScheme.outline) }
        if (live.busy) Note("✽ " + (live.status ?: "在想…"), MaterialTheme.colorScheme.tertiary)
        pending?.let { p -> PendingBar(p, keyBusy, onKey = { sendKey(it, p.fingerprint) }, onSubmit = { submit(p) }) }
        sendErr?.let { Note(it, MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(12.dp, 6.dp, 12.dp, 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft, { draft = it }, maxLines = 8,
                placeholder = { Text("跟它说点什么… Enter 发送，Shift+Enter 换行") },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // 中文输入法正在组词（composition != null）时 Enter 归输入法，不发
                    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed && draft.composition == null) { send(); true } else false
                },
            )
            Button(onClick = ::send, enabled = draft.text.isNotBlank() && !sending) { Text(if (sending) "发送中…" else "发送") }
        }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, Modifier.padding(16.dp, 2.dp), style = MaterialTheme.typography.labelMedium, color = color)

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

// ── 审批 / 选择条 ──

/** ⚠️ 卡片上显示几号，点下去就送几号 —— 别改成按下标送键，顺序对不上会点 A 选中 B 且不报错。 */
@Composable
private fun PendingBar(p: Pending, busy: Boolean, onKey: (String) -> Unit, onSubmit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp)) {
        Column(Modifier.padding(14.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (p.multiSelect) "等你选（可多选：点是勾/取消，选完再提交）" else "等你选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (p.title.isNotBlank()) Text(p.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (p.tabs.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { onKey("Left") }) { Text("← 上一题") }
                p.tabs.forEach { Text((if (it.submit) "✔ " else if (it.answered) "☑ " else "☐ ") + it.label, style = MaterialTheme.typography.labelSmall) }
                TextButton(enabled = !busy, onClick = { onKey("Right") }) { Text("下一题 →") }
            }
            p.options.forEach { o ->
                Surface(
                    color = if (o.checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onKey(o.number.toString()) },
                ) {
                    Row(Modifier.padding(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (p.multiSelect) (if (o.checked) "☑" else "☐") else "${o.number}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                        Column {
                            Text(o.label, style = MaterialTheme.typography.bodyMedium)
                            if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            if (p.multiSelect || p.tabs.size > 1 || p.review) Button(onClick = onSubmit, enabled = !busy) { Text(if (p.review) "确认提交" else "提交答案") }
        }
    }
}

// ── 条目渲染 ──

@Composable
private fun ItemView(item: ChatItem) = when (item) {
    is ChatItem.UserText -> UserBubble(item.text, queued = false)
    is ChatItem.Queued -> UserBubble(item.text, queued = true)
    is ChatItem.AssistantText -> AssistantBody(item.markdown)
    is ChatItem.Thinking -> Fold("思考过程", item.text)
    is ChatItem.ToolCall -> ToolCard(item)
    is ChatItem.Injected -> Fold(listOfNotNull(item.kind, item.from).joinToString(" · "), Transcript.clean(item.text))
    is ChatItem.ApiError -> Note("⚠ " + item.text, MaterialTheme.colorScheme.error)
    is ChatItem.Unknown -> Unit
}

@Composable
private fun UserBubble(text: String, queued: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = if (queued) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp), modifier = Modifier.widthIn(max = 640.dp),
        ) {
            Column(Modifier.padding(14.dp, 9.dp)) {
                if (queued) Text("排队中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun AssistantBody(md: String) {
    val blocks = remember(md) { mdBlocks(md) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { (code, text) ->
                if (code) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                } else Text(inlineMd(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 默认折起来的一段（思考过程 / 注入的系统消息）：一行标题，点开看全文。 */
@Composable
private fun Fold(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        Text((if (open) "▾ " else "▸ ") + title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(
            body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolCard(c: ChatItem.ToolCall) {
    var open by remember(c.key) { mutableStateOf(c.isError) }   // 失败的默认展开，那才是要看的
    val accent = when { c.isError -> MaterialTheme.colorScheme.error; c.result == null -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.outline }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, style = MaterialTheme.typography.labelLarge, color = accent)
                Text(summary(c), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(when { c.isError -> "失败"; c.result == null -> "进行中…"; else -> "完成" }, style = MaterialTheme.typography.labelSmall, color = accent)
            }
            if (open) {
                Mono(c.input.toString(2).take(3000))
                c.result?.let { Mono(it.take(4000)) }
            }
        }
    }
}

@Composable
private fun Mono(text: String) = SelectionContainer {
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

/** 一串同名工具卡合成一张：`Bash × 7 · 2 失败`，点开铺成原来的小卡片。 */
@Composable
private fun GroupCard(calls: List<ChatItem.ToolCall>, open: Boolean, onToggle: () -> Unit) {
    val bad = calls.count { it.isError }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(calls.first().name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                Text("× ${calls.size}", style = MaterialTheme.typography.labelSmall)
                if (bad > 0) Text("$bad 失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(if (open) "" else summary(calls.first()), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (open) Column(Modifier.padding(8.dp, 0.dp, 8.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { calls.forEach { ToolCard(it) } }
        }
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

/** 最简 markdown：按 ``` 围栏切成 (是代码块, 文本)；代码块外的连续正文合成一块。 */
fun mdBlocks(md: String): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    val cur = StringBuilder()
    var code = false
    fun flush() { if (cur.isNotBlank()) out += code to cur.toString().trim('\n'); cur.clear() }
    md.replace("\r", "").split('\n').forEach { line ->
        if (line.trimStart().startsWith("```")) { flush(); code = !code } else cur.append(line).append('\n')
    }
    flush()
    return out
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
private val HEADING = Regex("""^#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[-*]\s+""")

/** 行内：`**粗体**`、`` `code` ``；行首 `# 标题` 整行加粗，`- ` 换成圆点。 */
fun inlineMd(text: String): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { n, raw ->
        if (n > 0) append('\n')
        val heading = HEADING.containsMatchIn(raw)
        val line = BULLET.replace(HEADING.replace(raw, ""), "$1• ")
        val start = length   // 标记符（** 和反引号）不进正文，所以行的起点要在追加前记下
        var at = 0
        INLINE.findAll(line).forEach { m ->
            append(line.substring(at, m.range.first))
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[2]) }
            at = m.range.last + 1
        }
        append(line.substring(at))
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}
