package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
                )
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
        // 攒一批再解析：tail 一上来就吐几百行，逐行重解会把 UI 卡住。
        //
        // ⚠️ **节流必须有「尾随刷新」。** 第一版写成「距上次解析超过 250ms 才解析」，
        // 结果是：tail 把历史一次性吐完（都在同一个 250ms 窗口里），只有第一行触发了解析，
        // 剩下的全被吞掉，然后 tail 阻塞等新内容 —— **界面永远停在第一行的解析结果**。
        // 现象是「忙的会话正常、闲的会话永远空白」，最容易被当成偶发问题。
        // 现在改成：收行的只管往 buf 里塞并标脏，另一个协程定时把脏的刷出来。
        val buf = ArrayList<String>()
        var dirty = false
        launch {
            while (true) {
                delay(300)
                if (!dirty) {
                    // ⚠️ 一个空转的周期 = 历史灌完了。**这个标志是「不要跳」的关键**：
                    // 在它之前每次刷新都瞬移到底（不做动画），之后才允许动画。
                    // `tail -n 800` 是分批吐的，每批都动画一次滚到底 ——
                    // 动画没走完下一批又来，看起来就是一闪一闪地跳。
                    if (items.isNotEmpty()) settled = true
                    continue
                }
                dirty = false
                val snapshot = buf.toList()   // 在收行的同一个线程上拷一份，再拿去后台解析
                items = withContext(Dispatchers.Default) { Transcript.parse(snapshot.asSequence()) }
            }
        }
        TranscriptStream.stream(s, file).collect { line -> buf += line; dirty = true }
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
    LaunchedEffect(items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        val last = items.size - 1
        runCatching {
            if (!settled) {
                // 还在灌历史：**瞬移**。用户看到的是「一进来就在最新的地方」
                listState.scrollToItem(last)
            } else if (listState.atBottom(items.size)) {
                // ⚠️ 只有本来就在底部才跟着走。用户往上翻着看旧消息时，
                // 新消息把他拽回底部比不滚更烦（跟 #61 是同一类错误）
                listState.animateScrollToItem(last)
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

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items.size, key = { items[it].key }) { i -> Item(items[i]) }
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

        if (staged.isNotEmpty() || uploading) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                staged.forEach { a ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill) {
                        Row(Modifier.clickable { staged = staged - a }.padding(12.dp, 6.dp)) {
                            Text(
                                a.label + "  ✕",
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
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sftp != null) RoundBtn("📎") { pick.launch("*/*") }
            RoundBtn("🎤") {
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                BasicTextFieldRow(draft) { draft = it }
            }
            Surface(
                color = if (draft.isBlank() && staged.isEmpty()) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.primary,
                shape = Pill,
                modifier = Modifier.size(52.dp).clickable(enabled = draft.isNotBlank() || staged.isNotEmpty()) {
                    // 附件的路径映射贴在正文前面 —— Claude 自己去读那些文件
                    val t = (app.yxi.agent.Attachments.header(staged) + draft.trim()).trim()
                    draft = ""; staged = emptyList()
                    scope.launch { ssh?.let { SessionProbe.send(it, sessionName, t) } }
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "↑",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (draft.isBlank() && staged.isEmpty()) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
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

@Composable
private fun Item(item: ChatItem) = when (item) {
    is ChatItem.UserText -> UserBubble(item.text)
    is ChatItem.Queued -> QueuedBubble(item.text)
    is ChatItem.AssistantText -> Markdown(
        item.markdown,
        modifier = Modifier.fillMaxWidth(),
    )
    is ChatItem.Thinking -> ThinkingRow(item.text)
    is ChatItem.ToolCall -> ToolCard(item)
    is ChatItem.Unknown -> Unit   // 兜底：不认识的块静默跳过，不要在界面上留垃圾
}

/** 视口最后一个可见项是不是就在列表末尾附近（差一条也算，避免边界抖）。 */
private fun androidx.compose.foundation.lazy.LazyListState.atBottom(total: Int): Boolean {
    val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= total - 2
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(
                text,
                Modifier.padding(18.dp, 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
private fun QueuedBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
            shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            ),
            modifier = Modifier.fillMaxWidth(0.85f),
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
        Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = a), CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            status ?: "在忙…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
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
