package app.yxi.ui

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.TranscriptStream
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.KeyManager as Keys
import app.yxi.ssh.Sftp
import app.yxi.ssh.SshSession
import app.yxi.term.TerminalView
import app.yxi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

private val Pill = RoundedCornerShape(100.dp)

enum class Mode(val label: String) { Terminal("终端"), Chat("对话"), Files("文件") }

/**
 * 一台主机 + 一个会话的**工作区**：终端 / 对话 / 文件 三种模式共用**同一条 SSH 连接**。
 *
 * ⚠️ **连接和终端状态都提到了这一层**，这是「切换不断连」的关键：
 * 三个界面各自建连接的话，每切一次就重连一次 —— 慢，而且终端里
 * `tmux attach` 的滚动位置、正在编辑的半行命令全都没了。
 * 现在切换只是换掉**画面**：`SshSession` 和 `TerminalEmulator` 都活在这儿，
 * 终端的 shell 通道从头到尾没断过。
 *
 * ⚠️ 终端的 shell **按需才开** —— 你可能一路只看对话和文件，那就不该占一条通道。
 */
@Composable
fun Workspace(
    store: HostStore,
    keys: KeyManager,
    host: Host,
    /** tmux 会话名。null = 不针对某个会话（从主机层直接进终端/文件） */
    sessionName: String?,
    cwd: String,
    /**
     * 想进哪个模式。**null = 用这个会话上次的偏好**（点会话卡片就是这种）。
     * 明确点了「开终端」「文件」就传具体值 —— 显式动作压过记忆，否则那两个按钮等于白设。
     */
    initial: Mode?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val connect = rememberSshConnector(store, keys, host)

    var ssh by remember(host.id, sessionName) { mutableStateOf<SshSession?>(null) }
    var sftp by remember(host.id, sessionName) { mutableStateOf<Sftp?>(null) }
    var shell by remember(host.id, sessionName) { mutableStateOf<SshSession.Shell?>(null) }
    var status by remember(host.id, sessionName) { mutableStateOf<String?>("连接中…") }
    /** null = 还没查；"" = 有转录；非空 = 没有的原因 */
    var chatBlocked by remember(host.id, sessionName) { mutableStateOf<String?>(null) }
    var mode by remember(host.id, sessionName) {
        mutableStateOf(initial ?: Prefs.mode(ctx, host.id, sessionName) ?: Mode.Chat)
    }
    var dpad by remember { mutableStateOf(false) }

    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest
    val focus = remember { FocusRequester() }

    // 终端仿真器**先于连接建好** —— 这样连接过程中的报错也能直接写进终端显示出来
    val emulator: TerminalEmulator = remember(host.id, sessionName) {
        TerminalEmulatorFactory.create(
            looper = Looper.getMainLooper(),
            initialCols = 80, initialRows = 24,
            defaultForeground = fg, defaultBackground = bg,
            onKeyboardInput = { bytes -> scope.launch { shell?.write(bytes) } },
            onResize = { dim -> scope.launch { shell?.resize(dim.columns, dim.rows) } },
            autoDetectUrls = true,
        )
    }

    LaunchedEffect(host.id, sessionName) {
        val c = connect() ?: run { status = "这台主机还没有可用的认证方式"; return@LaunchedEffect }
        runCatching { c.session.connect(); ssh = c.session }
            .onFailure { status = c.explain(it); return@LaunchedEffect }
        status = null
        // 对话模式要有转录才有内容可渲染。没有就置灰**并说明原因** —— 灰着不说话最气人
        chatBlocked = when {
            sessionName == null -> "没有指定会话"
            TranscriptStream.latestFor(c.session, cwd) == null -> "这个会话里没跑过 Claude Code"
            else -> ""
        }
    }

    // 第一次进终端模式才开 shell
    LaunchedEffect(mode, ssh) {
        if (mode != Mode.Terminal || shell != null) return@LaunchedEffect
        val s = ssh ?: return@LaunchedEffect
        runCatching {
            if (sessionName != null) runCatching {
                s.exec(
                    "tmux has-session -t $sessionName 2>/dev/null || tmux new-session -d -s $sessionName; " +
                        "tmux set -g set-titles on \\; set -g mouse on \\; set -g status-right ''"
                )
            }
            val sh = s.openShell(80, 24)
            shell = sh
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            // ⚠️⚠️ **读循环必须挂在 Workspace 的 scope 上，不能挂在这个 LaunchedEffect 上。**
            // 挂在 effect 上的话，切到对话/文件模式时 effect 被取消 → 读循环一起死，
            // 但 shell 通道还开着、tmux 还在吐数据。
            // **jsch 的会话读循环是全局一条**：一个通道的缓冲塞满，
            // 整条连接上**所有**通道都不动了 —— 表现是「切到对话模式，一条消息都不出来」，
            // 而服务器上 `tail -f` 明明在跑。查了半天才想到是终端把连接堵死了。
            scope.launch(Dispatchers.IO) {
                val buf = ByteArray(16384)
                while (true) {
                    val n = runCatching { sh.output.read(buf) }.getOrElse { -1 }
                    if (n < 0) break
                    runCatching { emulator.writeInput(buf, 0, n) }
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            }
            if (sessionName != null) {
                // ⚠️ 不能连上就写：登录 shell 初始化时写进去的字节会被 tty 回显后冲掉
                // （实测现象是命令回显了却没执行）。等第一批输出 + 一个静默间隔。
                runCatching { kotlinx.coroutines.withTimeout(8000) { ready.await() } }
                kotlinx.coroutines.delay(900)
                sh.write("tmux attach -t $sessionName\n")
            }
        }.onFailure { status = "终端起不来：${it.message}" }
    }

    LaunchedEffect(mode, ssh) {
        if (mode == Mode.Files && sftp == null) {
            sftp = ssh?.let { runCatching { it.openSftp() }.getOrNull() }
        }
    }
    LaunchedEffect(mode) { Prefs.setMode(ctx, host.id, sessionName, mode) }
    LaunchedEffect(shell, mode) {
        if (mode == Mode.Terminal && shell != null) runCatching { focus.requestFocus() }
    }

    DisposableEffect(host.id, sessionName) {
        onDispose { shell?.close(); sftp?.close(); ssh?.disconnect() }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 10.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sessionName?.removePrefix("cc-") ?: host.alias,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                )
                Text(
                    status ?: cwd,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Dim, maxLines = 1,
                )
            }
            if (mode == Mode.Terminal) {
                Surface(
                    color = if (dpad) SurfaceContainerHigh else SurfaceContainer, shape = Pill,
                    modifier = Modifier.clickable { dpad = !dpad },
                ) {
                    Text(
                        "✛", Modifier.padding(13.dp, 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (dpad) Copper else Muted,
                    )
                }
            }
        }

        ModeSwitcher(mode, chatBlocked) { mode = it }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.Terminal -> TerminalView(emulator, focus, Modifier.fillMaxSize())
                Mode.Chat -> ChatScreen(ssh, sessionName.orEmpty(), cwd, Modifier.fillMaxSize())
                Mode.Files -> FilesScreen(sftp, cwd, Modifier.fillMaxSize())
            }
            if (mode == Mode.Terminal && dpad) {
                DPad(
                    Modifier.align(Alignment.BottomEnd).padding(14.dp),
                    send = { bytes -> scope.launch { shell?.write(bytes) } },
                )
            }
        }
    }
}

@Composable
private fun ModeSwitcher(mode: Mode, chatBlocked: String?, onPick: (Mode) -> Unit) {
    var why by remember { mutableStateOf<String?>(null) }
    Surface(
        color = SurfaceContainer, shape = Pill,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Mode.entries.forEach { m ->
                val blocked = m == Mode.Chat && !chatBlocked.isNullOrEmpty()
                Surface(
                    color = if (m == mode) SurfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent,
                    shape = Pill,
                    modifier = Modifier.weight(1f).height(38.dp).clickable {
                        // 置灰的不是「点不动」而是「点了告诉你为什么」
                        if (blocked) why = chatBlocked else onPick(m)
                    },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                blocked -> Dim
                                m == mode -> OnSurface
                                else -> Muted
                            },
                        )
                    }
                }
            }
        }
    }
    why?.let {
        AlertDialog(
            onDismissRequest = { why = null },
            title = { Text("对话模式用不了") },
            text = { Text("$it。\n\n对话模式渲染的是 Claude Code 的转录文件；这个会话里没有，所以没东西可显示。终端和文件模式照常可用。") },
            confirmButton = { TextButton({ why = null }) { Text("知道了") } },
        )
    }
}

/** 每个会话记住上次用的模式。SharedPreferences 就够 —— 不值得为三个字建一套存储。 */
private object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun key(hostId: String, session: String?) = "mode:$hostId:${session ?: "-"}"
    fun mode(ctx: Context, hostId: String, session: String?): Mode? =
        p(ctx).getString(key(hostId, session), null)?.let { n -> Mode.entries.firstOrNull { it.name == n } }
    fun setMode(ctx: Context, hostId: String, session: String?, m: Mode) =
        p(ctx).edit().putString(key(hostId, session), m.name).apply()
}
