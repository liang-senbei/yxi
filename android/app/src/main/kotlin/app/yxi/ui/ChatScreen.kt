package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.ChatItem
import app.yxi.agent.SessionProbe
import app.yxi.agent.Transcript
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.SshSession
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

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
    store: HostStore,
    keys: KeyManager,
    host: Host,
    sessionName: String,
    cwd: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val connect = rememberSshConnector(store, keys, host)
    var items by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var status by remember { mutableStateOf<String?>("连接中…") }
    var ssh by remember { mutableStateOf<SshSession?>(null) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(sessionName) {
        val c = connect() ?: run { status = "这台主机还没有可用的认证方式"; return@LaunchedEffect }
        runCatching { c.session.connect(); ssh = c.session }.onFailure {
            status = c.explain(it)
            return@LaunchedEffect
        }
        val file = TranscriptStream.latestFor(c.session, cwd)
        if (file == null) {
            status = "这个会话里没找到 Claude Code 的转录\n（$cwd）"
            return@LaunchedEffect
        }
        status = null
        // 攒一批再解析：tail 一上来就吐几百行，逐行重解会把 UI 卡住
        val buf = ArrayList<String>()
        var lastEmit = 0L
        TranscriptStream.stream(c.session, file).collect { line ->
            buf += line
            val now = System.currentTimeMillis()
            if (now - lastEmit > 250) {
                lastEmit = now
                items = Transcript.parse(buf.asSequence())
            }
        }
    }
    DisposableEffect(sessionName) { onDispose { ssh?.disconnect() } }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) runCatching { listState.animateScrollToItem(items.size - 1) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 12.dp, 18.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sessionName.removePrefix("cc-"), style = MaterialTheme.typography.titleLarge)
                Text(
                    status ?: "${items.size} 条",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items.size, key = { items[it].key }) { i -> Item(items[i]) }
        }

        // 输入框：打进那个活着的会话，不调任何 API
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                BasicTextFieldRow(draft) { draft = it }
            }
            Surface(
                color = if (draft.isBlank()) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.primary,
                shape = Pill,
                modifier = Modifier.size(52.dp).clickable(enabled = draft.isNotBlank()) {
                    val t = draft.trim(); draft = ""
                    scope.launch { ssh?.let { SessionProbe.send(it, sessionName, t) } }
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "↑",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (draft.isBlank()) MaterialTheme.colorScheme.outline
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
    is ChatItem.AssistantText -> Markdown(
        item.markdown,
        modifier = Modifier.fillMaxWidth(),
    )
    is ChatItem.Thinking -> ThinkingRow(item.text)
    is ChatItem.ToolCall -> ToolCard(item)
    is ChatItem.Unknown -> Unit   // 兜底：不认识的块静默跳过，不要在界面上留垃圾
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

/** 工具卡片。G6 会按工具名做定制（Bash 占 3177 次，最该先做）。 */
@Composable
private fun ToolCard(c: ChatItem.ToolCall) {
    var open by remember { mutableStateOf(false) }
    val accent = when (c.name) {
        "Bash" -> MaterialTheme.colorScheme.primary
        "Edit", "Write" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(18.dp, 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(c.name, style = MaterialTheme.typography.labelLarge, color = accent)
                Spacer(Modifier.weight(1f))
                c.result?.let {
                    Text(
                        if (c.isError) "出错" else "完成",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (c.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            summarize(c)?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = if (open) 40 else 3,
                )
            }
            AnimatedVisibility(open && c.result != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                ) {
                    Text(
                        c.result.orEmpty().take(4000),
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * 从工具参数里抽一行「这是在干嘛」。
 * **在解析层就抽好，别让界面再去翻 `tool_input`** —— 抄 Lucarne 的第 3 条纪律（PRD 附录 B.4）。
 */
private fun summarize(c: ChatItem.ToolCall): String? = when (c.name) {
    "Bash" -> c.input.optString("command").takeIf { it.isNotBlank() }
    "Read", "Edit", "Write" -> c.input.optString("file_path").takeIf { it.isNotBlank() }
    "WebFetch" -> c.input.optString("url").takeIf { it.isNotBlank() }
    "WebSearch" -> c.input.optString("query").takeIf { it.isNotBlank() }
    "Agent", "Task" -> c.input.optString("description").takeIf { it.isNotBlank() }
    else -> c.input.toString().takeIf { it.length > 2 }?.take(200)
}
