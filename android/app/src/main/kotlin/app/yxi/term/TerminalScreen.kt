package app.yxi.term

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.yxi.ssh.HostConfig
import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * 真终端：SSH shell channel ⇄ ConnectBot `termlib` 的 Compose 终端控件。
 *
 * 选它的理由（PRD 附录 B 已更新）：Maven Central 上拿得到、Apache-2.0、
 * **Compose 原生**（我们整个 app 就是 Compose）、自带 IME 处理（中文输入那个坑）、
 * 无障碍、选区、滚动、URL 检测，四个 ABI 的 `.so` 都预编译好——我们一行原生代码不用编。
 *
 * 数据流是对称的两条：
 *   SSH 输出 → [TerminalEmulator.writeInput] → 控件绘制
 *   控件按键 → `onKeyboardInput` → SSH 输入
 */
@Composable
fun TerminalScreen(
    store: app.yxi.ssh.HostStore,
    keys: app.yxi.ssh.KeyManager,
    host: app.yxi.ssh.Host,
    attachTo: String?,
    modifier: Modifier = Modifier,
) {
    val connector = app.yxi.ui.rememberSshConnector(store, keys, host)
    // 终端控件的回调在它自己的时机触发，异常不能逸出去崩 app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>("连接中…") }
    var shell by remember { mutableStateOf<SshSession.Shell?>(null) }

    // 没有焦点，控件收不到按键 —— 打字全部落空
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }

    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest

    // 先建终端，再连 SSH —— 这样连接过程中的报错也能直接写进终端显示出来
    val emulator: TerminalEmulator = remember {
        TerminalEmulatorFactory.create(
            looper = Looper.getMainLooper(),
            initialCols = 80,
            initialRows = 24,
            defaultForeground = fg,
            defaultBackground = bg,
            // 你在终端里敲的每一个键最终从这里出来
           onKeyboardInput = { bytes -> scope.launch { shell?.write(bytes) } },
            // 尺寸变了要告诉远端，否则它还按老宽度折行
            onResize = { dim -> scope.launch { shell?.resize(dim.columns, dim.rows) } },
            autoDetectUrls = true,
        )
    }

    LaunchedEffect(Unit) {
        val c = connector()
        if (c == null) {
            status = "这台主机还没有可用的认证方式——去主机列表里补密码或装公钥"
            return@LaunchedEffect
        }
        val known = c.known
        val session = c.session
        runCatching {
            session.connect()
            // tmux 的准备工作走独立的 exec channel，跟终端通道分开 ——
            // 终端的输入通道是留给用户的，不该拿来打配置命令
            if (attachTo != null) {
                runCatching {
                    session.exec(
                        "tmux has-session -t $attachTo 2>/dev/null || tmux new-session -d -s $attachTo; " +
                            "tmux set -g set-titles on \\; set -g mouse on \\; " +
                            "set -g status-right '' \\; " +
                            "unbind -q -T root WheelUpStatus \\; unbind -q -T root WheelDownStatus"
                    )
                }
            }

            // ⚠️ 终端通道必须用 ChannelShell，不能用 ChannelExec+PTY：
            // 后者的输入流在【没有数据可读时会提前返回 EOF】，而通道本身还 connected —— 
            // 表现是 attach 上了、渲染了一屏就断（实测 `sleep 25` 只读到 13 字节就 EOF）。
            val sh = session.openShell(80, 24)
            shell = sh
            status = null

            // 收到第一批输出才算 shell 就绪
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            val pump = launch(Dispatchers.IO) {
                val buf = ByteArray(16384)
                var total = 0L
                while (true) {
                    val n = runCatching { sh.output.read(buf) }.getOrElse { e ->
                        android.util.Log.w("YxiTerm", "读出错 ${e::class.simpleName}: ${e.message}"); -1
                    }
                    if (n < 0) {
                        android.util.Log.w("YxiTerm",
                            "pump EOF: 累计 ${total}B, ch.connected=${sh.isConnected}"); break
                    }
                    total += n
                    runCatching { emulator.writeInput(buf, 0, n) }
                        .onFailure { android.util.Log.e("YxiTerm", "writeInput 抛了: ${it::class.simpleName}: ${it.message}") }
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            }

            if (attachTo != null) {
                // ⚠️ 不能连上就写：登录 shell（starship 那种）初始化要时间，
                // 在它开始读之前写进去的字节会被 tty 回显、然后被冲掉 ——
                // 实测现象是命令回显了却没执行。
                // 等第一批输出 + 一个静默间隔，确认 shell 已经在读了再发。
                runCatching { kotlinx.coroutines.withTimeout(8000) { ready.await() } }
                kotlinx.coroutines.delay(900)
                sh.write("tmux attach -t $attachTo\n")
            }

            pump.join()
            status = "连接已断开"
        }.onFailure {
            status = if (known.changedDetected) {
                "⚠️ 主机指纹变了，已拒绝连接。\n服务器可能被重装过——确认无误后请在主机列表里删掉这台再重加。"
            } else {
                "失败：${it::class.simpleName}: ${it.message}"
            }
        }
    }

    LaunchedEffect(shell) { if (shell != null) runCatching { focus.requestFocus() } }

    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.w("YxiTerm", "onDispose —— 组合被销毁，主动关闭 shell")
            shell?.close()
        }
    }

    Box(modifier.fillMaxSize()) {
        Terminal(
            terminalEmulator = emulator,
            modifier = Modifier.fillMaxSize(),
            focusRequester = focus,
        )
        status?.let {
            Text(
                it,
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
