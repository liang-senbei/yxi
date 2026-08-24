package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.ChatItem
import app.yxi.agent.Pending
import app.yxi.agent.SessionProbe
import app.yxi.agent.Transcript
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.SshSession
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Pill = RoundedCornerShape(100.dp)

/**
 * 对话渲染模式 —— **这是主界面**（PRD 附录 D）。
 *
 * 数据源是 `~/.claude/projects` 下的转录 JSONL，**不刮终端屏幕**。
 * 发消息走 `tmux send-keys` 打进那个活着的会话，
 * 所以 Claude Code 的配置、权限、MCP、skills 原样生效 —— 我们不重新实现 agent 协议。
 */
@Composable
fun ChatScreen(
    /** ⚠️ 连接由 [Workspace] 持有并传进来 —— 切模式时这个 composable 会销毁，连接不能跟着断 */
    ssh: SshSession?,
    /** 附件上传要用。没有就把回形针按钮藏起来 */
    sftp: app.yxi.ssh.Sftp?,
    sessionName: String,
    cwd: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var staged by remember(sessionName) { mutableStateOf<List<app.yxi.agent.Attachments.Staged>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    /** 正在放大看的那张附件图。null = 没在看 */
    var preview by remember { mutableStateOf<app.yxi.agent.Attachments.Staged?>(null) }

    // 选文件（图片和任意文件走同一个选择器，类型看 MIME）
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val u = uri ?: return@rememberLauncherForActivityResult
        val s = sftp ?: return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            runCatching {
                val cr = ctx.contentResolver
                val bytes = withContext(Dispatchers.IO) {
                    cr.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                }
                val mime = cr.getType(u).orEmpty()
                val isImage = mime.startsWith("image/")
                val name = queryName(ctx, u) ?: (if (isImage) "image" else "file")
                val idx = staged.count { it.isImage == isImage } + 1
                val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                staged = staged + app.yxi.agent.Attachments.upload(
                    s, sessionName, name, bytes, idx, isImage, stamp
                ).copy(localUri = u.toString())
                // ⚠️ 顺手清一次 3 天前的 —— 不用 cron，不用守护进程
                ssh?.let { app.yxi.agent.Attachments.sweep(it) }
            }
            uploading = false
        }
    }

    // 语音：走系统的识别界面（`RecognizerIntent`）。国产 ROM 有自家实现，接口一样。
    // ⚠️ **结果只填进输入框，绝不直接发** —— 识别错一个字，在服务器上就是另一条命令。
    val listen = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val said = r.data?.getStringArrayListExtra(
            android.speech.RecognizerIntent.EXTRA_RESULTS
        )?.firstOrNull().orEmpty()
        if (said.isNotBlank()) draft = (draft.trimEnd() + " " + said).trim()
    }
    var items by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var status by remember { mutableStateOf<String?>("连接中…") }

    var pending by remember { mutableStateOf<Pending?>(null) }
    // ⚠️ 此刻在忙什么、有哪些输入还排着队 —— **只有屏幕知道**，转录里没有。
    // 见 Live 的类注释和 TROUBLESHOOTING #72
    var live by remember { mutableStateOf(app.yxi.agent.Live.IDLE) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 历史灌完了没。灌的过程中一律瞬移到底，不做动画（见下面的 LaunchedEffect）
    var settled by remember(sessionName) { mutableStateOf(false) }

    LaunchedEffect(sessionName, ssh) {
        val s = ssh ?: return@LaunchedEffect
        val file = TranscriptStream.latestFor(s, cwd)
        if (file == null) {
            status = "这个会话里没找到 Claude Code 的转录\n（$cwd）"
            return@LaunchedEffect
        }
        status = null

        // ⚠️ **先画最新的一屏，再补历史。** `tail -n 800` 从最老那条开始吐、
        // 最新的最后才到，所以完整那次要等 4.17 MB 传完你才看得见最新内容。
        // 这里先要 60 行（0.58 MB，一个来回），立刻有东西看；
        // 下面那条完整流回来之后整体替换。
        runCatching { TranscriptStream.head(s, file) }
            .onSuccess { head ->
                if (head.isNotEmpty()) {
                    items = withContext(Dispatchers.Default) { Transcript.parse(head.asSequence()) }
                }
            }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

        // 攒一批再解析：tail 一上来就吐几百行，逐行重解会把 UI 卡住。
        //
        // ⚠️ **节流必须有「尾随刷新」。** 第一版写成「距上次解析超过 250ms 才解析」，
        // 结果是：tail 把历史一次性吐完（都在同一个 250ms 窗口里），只有第一行触发了解析，
        // 剩下的全被吞掉，然后 tail 阻塞等新内容 —— **界面永远停在第一行的解析结果**。
        // 现象是「忙的会话正常、闲的会话永远空白」，最容易被当成偶发问题。
        // 现在改成：收行的只管往 buf 里塞并标脏，另一个协程定时把脏的刷出来。
        // ⚠️ **只喂新行。** 老做法是每 300ms 把整个缓冲从头重解 ——
        // 实测真实会话 `tail -n 800` 是 4.17 MB，等于**每秒重嚼三次 4 MB**，
        // 会话越长越慢。见 TROUBLESHOOTING #86。
        val inc = Transcript.Incremental()
        val pending = ArrayList<String>()
        // ⚠️ 收行和刷新是两个协程。`toList()` 和 `clear()` 之间来一行就会**丢**，
        // 所以这两处都要在同一把锁里。老代码不清空所以没这个问题，现在清了就得管。
        val lock = Any()
        launch {
            while (true) {
                delay(300)
                val batch = synchronized(lock) {
                    if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
                }
                if (batch.isEmpty()) {
                    // ⚠️ 一个空转的周期 = 历史灌完了。**这个标志是「不要跳」的关键**：
                    // 在它之前每次刷新都瞬移到底（不做动画），之后才允许动画。
                    // `tail -n 800` 是分批吐的，每批都动画一次滚到底 ——
                    // 动画没走完下一批又来，看起来就是一闪一闪地跳。
                    if (items.isNotEmpty()) settled = true
                    continue
                }
                items = withContext(Dispatchers.Default) {
                    inc.add(batch.asSequence())
                    inc.snapshot()
                }
            }
        }
        TranscriptStream.stream(s, file).collect { line -> synchronized(lock) { pending += line } }
    }
    // ⚠️ 「此刻在等你选」这件事**只有屏幕知道** —— tool_use 要等工具跑完才落进转录。
    // 所以历史读转录、待答抓屏幕，两条路各司其职（见 Prompt 的类注释）。
    LaunchedEffect(ssh, sessionName) {
        val s = ssh ?: return@LaunchedEffect
        while (true) {
            if (!busy) {
                runCatching { SessionProbe.snapshot(s, sessionName) }.onSuccess { (p, l) ->
                    pending = p
                    live = l
                }
            }
            // 忙的时候抓快一点 —— 状态行是给人看「它还活着」的，
            // 2.5 秒一跳就不像在动了；闲的时候没必要这么勤
            delay(if (live.busy) 900 else 2_500)
        }
    }
    // ⚠️ **`settled` 也要当键。** 只用 items.size 的话，最后一次定位发生在
    // 「历史还在灌、布局还在变」的时候，滚到一半列表又长高了 ——
    // 结果永远差最后一屏（最后一条被切掉，↓ 按钮赖着不走）。
    // 加上 settled：灌完那一刻**再定位一次**，这次布局是稳的。
    LaunchedEffect(items.size, settled) {
        if (items.isEmpty()) return@LaunchedEffect
        val last = items.size - 1
        runCatching {
            if (!settled) {
                // 还在灌历史：**瞬移**。用户看到的是「一进来就在最新的地方」
                listState.scrollToEnd(last)
            } else if (listState.atBottom) {
                // ⚠️ 只有本来就在底部才跟着走。用户往上翻着看旧消息时，
                // 新消息把他拽回底部比不滚更烦（跟 #61 是同一类错误）
                listState.scrollToEnd(last)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 标题和路径由 Workspace 的头部管，这里只在出问题时说一句
        status?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(18.dp, 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(items.size, key = { items[it].key }) { i ->
                    Item(
                        items[i],
                        onCopy = { copy(ctx, it) },
                        onPopQueue = {
                            // ⚠️ 原文取**转录里的**，不是屏幕上刮的（[SessionProbe.popQueue] 的注释）
                            val all = items.filterIsInstance<ChatItem.Queued>().map(ChatItem.Queued::text)
                            scope.launch {
                                val s = ssh ?: return@launch
                                app.yxi.ssh.catching { app.yxi.agent.SessionProbe.popQueue(s, sessionName) }
                                    .onSuccess {
                                        // 收回来的接在草稿后面，不覆盖用户可能已经打了一半的东西
                                        draft = (draft.trimEnd() + "\n" + all.joinToString("\n")).trim()
                                    }
                                    .onFailure {
                                        android.widget.Toast.makeText(ctx, "收不回来：" + it.message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                    )
                }
            }

            // ⚠️ **只在没在底部时才出现。** 一直挂着的话它就是块永久的遮挡 ——
            // 而绝大多数时候你本来就在底部（新消息会自动跟着走），那时它毫无用处。
            // derivedStateOf：不加的话每滚一帧都要重组整个 ChatScreen。
            val away by remember {
                derivedStateOf { items.isNotEmpty() && !listState.atBottom }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = away,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp).clickable {
                        // ⚠️ 瞬移不做动画：几百条的列表上 animateScrollToItem 要滚好几秒，
                        // 而这个按钮的意思就是「立刻到底」
                        // ⚠️ 第二个参数是**在那一条内部再往下滚多少像素**。
                        // 不给的话是把最后一条的**顶部**对齐视口顶部 ——
                        // 那条要是比一屏长，尾巴还在屏幕外，用户会觉得按了没用。
                        // 给一个大数，Compose 会夹到列表真正的末尾。
                        scope.launch { listState.scrollToEnd(items.size - 1) }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "↓",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        if (live.busy) LiveStatus(live.status)

        pending?.let { p ->
            Box(Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp)) {
                PendingCard(
                    p, busy,
                    onPick = { o ->
                        scope.launch {
                            busy = true
                            // 送屏幕上写的那个数字本身，**不是列表下标**
                            ssh?.let { SessionProbe.sendKey(it, sessionName, o.number.toString()) }
                            delay(500)
                            pending = ssh?.let { runCatching { SessionProbe.pending(it, sessionName) }.getOrNull() }
                            busy = false
                        }
                    },
                    onSubmit = {
                        scope.launch {
                            busy = true
                            ssh?.let {
                                SessionProbe.sendKey(it, sessionName, "Right")   // 跳到 Submit 页
                                delay(300)
                                SessionProbe.sendKey(it, sessionName, "1")       // 交卷
                            }
                            delay(500)
                            pending = ssh?.let { runCatching { SessionProbe.pending(it, sessionName) }.getOrNull() }
                            busy = false
                        }
                    },
                )
            }
        }

        // 斜杠命令提示。手机上把 `/compact` 一个字母一个字母敲出来太痛苦了 —— 点一下就好。
        //
        // ⚠️ **不拦任何输入。** 这只是个填字条，选中就是把名字塞进草稿，
        // 送出去的还是 `tmux send-keys`，由 Claude Code 自己的命令面板处理。
        // 所以你自己写的斜杠命令照打照样能用，只是没提示。
        val hints = app.yxi.agent.Slash.suggest(draft)
        if (hints.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(14.dp, 0.dp, 14.dp, 8.dp),
            ) {
                // ⚠️ 高度必须封顶：只打一个 `/` 时候选是全部二十来条，
                // 不封顶会把整个对话区顶出屏幕。
                LazyColumn(Modifier.heightIn(max = 232.dp)) {
                    items(hints, key = { it.name }) { c ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { draft = "/" + c.name }
                                .padding(18.dp, 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "/" + c.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                c.hint,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        if (staged.isNotEmpty() || uploading) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                staged.forEach { a ->
                    // ⚠️ **点名字预览、点 ✕ 删除，两个热区必须分开。**
                    // 原来整块都是「删掉」—— 想确认自己传的是不是那张图，一点就没了，
                    // 还得重新去相册翻一遍。
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.label,
                                Modifier
                                    // 只有图片点得开；别的文件点名字不该有反应
                                    .clickable(enabled = a.isImage && a.localUri != null) { preview = a }
                                    .padding(12.dp, 6.dp, 6.dp, 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (a.isImage && a.localUri != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "✕",
                                Modifier.clickable { staged = staged - a }.padding(6.dp, 6.dp, 12.dp, 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (uploading) Text(
                    "传着…", Modifier.padding(8.dp, 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // 输入框：打进那个活着的会话，不调任何 API
        //
        // ⚠️ **整条是一个胶囊，不是四个圆按钮排排站。** 原来是 📎 🎤 输入框 ↑ 四块分开，
        // 每块之间 10dp 空隙，视觉上是「一排控件」而不是「一个输入区」；
        // 而且两个 emoji 图标跟界面里其余的线性图标不是一路。
        // 现在按 Gemini 那种做法收成一条：+ · 文字 · 🎤 · 发送，边界一条，里面才分格。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = Pill,
            modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 18.dp).heightIn(min = 56.dp),
        ) {
            Row(
                Modifier.padding(6.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sftp != null) {
                    FlatIcon(Glyph.Plus, "加附件") { pick.launch("*/*") }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) { BasicTextFieldRow(draft) { draft = it } }
                FlatIcon(Glyph.Mic, "语音输入") {
                    runCatching {
                        listen.launch(
                            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(
                                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说吧")
                        )
                    }
                }
                val canSend = draft.isNotBlank() || staged.isNotEmpty()
                Surface(
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp).clickable(enabled = canSend) {
                        // 附件的路径映射贴在正文前面 —— Claude 自己去读那些文件
                        val t = (app.yxi.agent.Attachments.header(staged) + draft.trim()).trim()
                        draft = ""; staged = emptyList()
                        scope.launch { ssh?.let { SessionProbe.send(it, sessionName, t) } }
                    },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GlyphIcon(
                            Glyph.Send,
                            if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.outline,
                            20.dp,
                        )
                    }
                }
            }
        }
    }

    // 附件图片放大看。⚠️ 读的是**手机本地**那份（[Attachments.Staged.localUri]）——
    // 文件是刚从这台手机传上去的，再从服务器拉回来是白跑一趟。
    preview?.let { a ->
        val uri = a.localUri
        val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, uri) {
            value = uri?.let {
                withContext(Dispatchers.IO) {
                    app.yxi.ssh.catching {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(it))?.use { s -> s.readBytes() }
                    }.getOrNull()
                }
            }
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { preview = null }) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.clickable { preview = null }.padding(6.dp)) {
                    Text(
                        a.label + " · " + a.remotePath,
                        Modifier.padding(14.dp, 10.dp, 14.dp, 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    bytes?.let { ImageBody(it) } ?: Text(
                        "读不出来了 —— 这张图的授权可能已经失效",
                        Modifier.padding(14.dp, 10.dp, 14.dp, 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** 胶囊里那种「无底色、点得动」的图标按钮。 */
@Composable
private fun FlatIcon(path: String, label: String, onTap: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(path, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
    }
}

@Composable
private fun BasicTextFieldRow(value: String, onValue: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value, onValue,
        modifier = Modifier.padding(20.dp, 15.dp).fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text("说一句…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
            }
            inner()
        },
    )
}

/**
 * 复制到剪贴板 + 吱一声。
 *
 * ⚠️ 用 `android.content.ClipboardManager` 而不是 Compose 的 `LocalClipboardManager` ——
 * 项目里另外两处（[HostsScreen] 的公钥、[DevMode] 的诊断）已经是这个写法，统一。
 */
private fun copy(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("yxi", text))
    // Android 13+ 系统自己会弹「已复制」的浮层，再 Toast 一次就是两层，所以只在旧系统上吱
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Item(item: ChatItem, onCopy: (String) -> Unit, onPopQueue: () -> Unit) = when (item) {
    is ChatItem.UserText -> UserBubble(item.text, onCopy)
    is ChatItem.Queued -> QueuedBubble(item.text, onCopy, onPopQueue)
    is ChatItem.Injected -> InjectedCard(item)
    // ⚠️ AI 的输出**不做长按菜单，做原生文本选择** —— 想要的多半是里面的一个 URL
    // 或者一段命令，整段复制反而要回头再删。SelectionContainer 给的是系统那套
    // 选择手柄 + 复制条，长按即起，双击选词。
    // （代价：长按被选择消费掉了，所以这一支不能再挂 combinedClickable。）
    is ChatItem.AssistantText -> androidx.compose.foundation.text.selection.SelectionContainer {
        Markdown(
            item.markdown,
            // ⚠️ 一定要传 —— 库默认把 `##` 渲染成 45sp（正文的 3 倍）。见 [yxiMarkdown]
            typography = yxiMarkdown(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    is ChatItem.Thinking -> ThinkingRow(item.text)
    is ChatItem.ToolCall -> ToolCard(item)
    is ChatItem.Unknown -> Unit   // 兜底：不认识的块静默跳过，不要在界面上留垃圾
}

/**
 * 滚到**真正的末尾**。
 *
 * ⚠️ `scrollToItem(last)` 只是把最后一条的**顶部**对齐视口顶部 ——
 * 那条要是比一屏长（长回复很常见），尾巴还在屏幕外。
 * 传个巨大的 `scrollOffset` 也不可靠。老老实实滚到滚不动为止。
 * 次数封顶，免得内容还在增长时转不出来。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToEnd(lastIndex: Int) {
    scrollToItem(lastIndex)
    repeat(30) {
        // ⚠️ **每次判之前先等一帧。** `canScrollForward` 是从 layoutInfo 算的，
        // 刚 scrollToItem 完布局还没重新量，这里读到的是**上一帧**的答案 ——
        // 读成 false 就会在第一次循环里直接 return，于是永远差最后一屏。
        // 现象是「一进对话，最后一条被切掉一半，↓ 按钮赖着不走」。
        androidx.compose.runtime.withFrameNanos { }
        if (!canScrollForward) return
        scroll { scrollBy(4000f) }
    }
}

/**
 * 真的到底了吗 —— 就问「还能不能往下滚」。
 *
 * ⚠️ 别自己拿 `visibleItemsInfo` 算像素。试过两版都不对：
 * 「最后一条可见」漏掉了「那条比一屏长、尾巴还在屏幕外」；
 * 算 `offset + size <= viewportEndOffset` 又要跟 `contentPadding` 较劲，
 * 差几个像素就永远判不到底（按钮赖着不走）。
 * `canScrollForward` 就是这个问题本身的答案，还是 State 支持的，能直接进 derivedStateOf。
 */
private val androidx.compose.foundation.lazy.LazyListState.atBottom: Boolean
    get() = !canScrollForward

/** 你说过的话。长按 → 复制整段。 */
@Composable
private fun UserBubble(text: String, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth(0.85f)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp),
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = {},
                    onLongClick = { menu = true },
                ),
            ) {
                Text(
                    text,
                    Modifier.padding(18.dp, 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text("复制整段") },
                    onClick = { onCopy(text); menu = false },
                )
            }
        }
    }
}

/**
 * 排队中的输入 —— 已经送到那台机器上了，但 Claude 还在忙，还没轮到它。
 *
 * ⚠️ 长得像用户气泡但**必须一眼看出不一样**（半透明 + 虚线边 + 「排队中」）。
 * 做成一模一样的话，用户以为已经在处理了；一点不显示的话，
 * 用户以为压根没发出去，然后重复发一遍 —— 后者我们已经遇到了。
 */
@Composable
private fun QueuedBubble(text: String, onCopy: (String) -> Unit, onPopQueue: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth(0.85f)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
            shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            ),
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = {},
                onLongClick = { menu = true },
            ),
        ) {
            Column(Modifier.padding(18.dp, 12.dp)) {
                Text(
                    "排队中 · 它忙完就轮到这条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text("复制整段") },
                onClick = { onCopy(text); menu = false },
            )
            // ⚠️ 文案必须说「都收回来」。TUI 的 `Up` 是全有全无的，
            // 排了三条按一次就三条一起回来 —— 写成「撤回这一条」是骗人的。
            DropdownMenuItem(
                text = { Text("收回改一改") },
                onClick = { onPopQueue(); menu = false },
            )
        }
        }
    }
}

/**
 * 「它正在忙」那一条。文案直接用 Claude Code 自己的状态词（Scampering… / Crafting… /
 * Searching…），**不翻译也不归一** —— 那些词是它自己在屏幕上说的话，
 * 换成「处理中…」反而丢了信息（词本身+耗时+token 数都在里面）。
 */
@Composable
private fun LiveStatus(status: String?) {
    val dots = rememberInfiniteTransition(label = "live")
    val a by dots.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth().padding(20.dp, 2.dp, 20.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(6.dp)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = a), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            status ?: "在忙…",
            // ⚠️ **它显得大不是因为字号大**（量过：12sp，比正文 16sp 还小），
            // 是视觉重量：labelMedium 自带 Medium 字重 + 强调色 + 独占一行。
            // 所以这里压的是字重和颜色，不是一味调小 —— 它还得看得见。
            // 单行截断：状态里带 token 数，长的会折成两行，那时候才是真的一大块。
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
        )
    }
}

/** 思考默认折叠 —— 实测一个会话 128 条，全展开会把内容淹掉（PRD 附录 D.2）。 */
@Composable
private fun ThinkingRow(text: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Pill,
            modifier = Modifier.clickable { open = !open },
        ) {
            Row(
                Modifier.padding(16.dp, 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (open) "思考 ▾" else "思考 ▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        AnimatedVisibility(open) {
            Text(
                text,
                Modifier.padding(4.dp, 10.dp, 0.dp, 0.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun RoundBtn(icon: String, onTap: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
        modifier = Modifier.size(46.dp).clickable(onClick = onTap),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(icon, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** 从 content:// 里问出原始文件名，问不到就返回 null。 */
private fun queryName(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
}.getOrNull()

/**
 * 注入内容（队友消息 / 任务通知 / 系统提醒 / 命令输出）。
 *
 * ⚠️ **默认折叠，而且不能长得像用户气泡。** 它们在转录里也是 `user` 类型，
 * 不单独渲染的话就是一坨原始 XML 顶着「你说的话」的样子出现在屏幕上。
 * 折叠是因为它们通常又长又不是你要读的内容 —— 但也不能藏掉，
 * 队友消息里常常有正事。
 */
@Composable
private fun InjectedCard(item: ChatItem.Injected) {
    var open by remember(item.key) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(16.dp, if (open) 13.dp else 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.kind,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                item.from?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!open) {
                    Text(
                        stripTags(item.text),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stripTags(item.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 把 XML 标签抹掉 —— 屏幕上没人想看 `<teammate-message from="...">`。 */
private fun stripTags(t: String): String =
    t.replace(Regex("</?[a-z][a-z0-9-]*(\\s[^>]*)?>"), " ")
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n").trim()
