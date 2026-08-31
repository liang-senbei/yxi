package app.yxi.ui

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

private val Pill = RoundedCornerShape(100.dp)

enum class Mode(private val zh: String) {
    Terminal("终端"), Chat("对话"), Files("文件"), Lab("实验室");

    // ⚠️ **label 必须是 get() 而不是构造参数。** enum 常量的参数在**类初始化时求值一次**，
    // 之后换语言它不会跟着变 —— 现象是底部导航栏 / 模式切换条永远停在启动时那种语言，
    // 而同一屏别的字都变了。get() 每次读都重新查表，还能被 Compose 当成状态读取。
    val label: String get() = t(zh)
}

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
    /** 进来时看哪个 tmux 会话。null = 不针对某个会话（从主机层直接进终端/文件）。
     *  ⚠️ **进来之后可以在工作区里直接换**（悬浮排列），所以它只是初值。 */
    startSession: String?,
    startCwd: String,
    /**
     * 想进哪个模式。**null = 用这个会话上次的偏好**（点会话卡片就是这种）。
     * 明确点了「开终端」「文件」就传具体值 —— 显式动作压过记忆，否则那两个按钮等于白设。
     */
    initial: Mode?,
    /**
     * **预热好的连接**（[app.yxi.MainActivity] 在你还在看板时就建好了）。
     *
     * ⚠️ **这是「进对话要等两三秒」的解法，而且刻意不共用看板那条。**
     * 共用一条的话，终端通道出事会把看板一起拖死 —— jsch 的会话读循环是全局一条，
     * 一个通道的缓冲塞满，连接上所有通道都不动（见本文件里那段 ⚠️⚠️ 和 #16）。
     * 所以这里是**另一条独立的连接**，只是提前建好了，不是共用。
     *
     * 它归 MainActivity 所有，**这里不许 disconnect** —— 断了下次又要重连。
     */
    preconnected: SshSession? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val connect = rememberSshConnector(store, keys, host)

    // ⚠️ **看的是哪个会话，是这一层的状态，不是参数。**
    // 悬浮排列切会话时只改这两个值 —— 下面那些连接状态是按 host 记的，**一个都不会重建**。
    var sessionName by remember(host.id) { mutableStateOf(startSession) }
    var cwd by remember(host.id) { mutableStateOf(startCwd) }

    // ⚠️ 以下全部只按 host.id 记：换会话不该重连。
    // 之前是按 (host, session) 记的，换个会话要重新握手 + 认证，模拟器上要两三秒。
    var ssh by remember(host.id) { mutableStateOf<SshSession?>(null) }
    var sftp by remember(host.id) { mutableStateOf<Sftp?>(null) }
    var shell by remember(host.id) { mutableStateOf<SshSession.Shell?>(null) }
    var status by remember(host.id) { mutableStateOf<String?>(t("连接中…")) }
    /** 终端当前 attach 在哪个会话上。跟 [sessionName] 不一致时要切过去 */
    var attached by remember(host.id) { mutableStateOf<String?>(null) }
    /** null = 还没查；"" = 有转录；非空 = 没有的原因 */
    var chatBlocked by remember(host.id, sessionName) { mutableStateOf<String?>(null) }
    var mode by remember(host.id) {
        mutableStateOf(initial ?: Prefs.mode(ctx, host.id, startSession) ?: Mode.Chat)
    }
    var switcher by remember { mutableStateOf(false) }
    /**
     * 标题下拉：**只列置顶的会话**，一步换过去。
     *
     * ⚠️ 两级是有意的：置顶的那几个是你天天在切的，值得一次点击就到；
     * 二十几个会话铺成卡片则是「找一个不常用的」，那是另一件事，
     * 不该让高频动作陪着低频动作一起等。
     * 下拉开着的时候**再点一次标题**就换成卡片墙。
     */
    var menu by remember { mutableStateOf(false) }
    // ⚠️ **开局就用缓存那份填上**（会话页每次刷新都会存）——
    // 原来这里是空的、等菜单打开才去抓，于是点开先看到一片空白再跳出内容。
    var quick by remember(host.id) {
        // ⚠️ 缓存那份是**整台机器的**会话，这里要的是**置顶的那几个** —— 别忘了筛，
        // 否则一点开就是二十条，跟菜单的用途正好相反
        val names = Pinned.get(ctx, host.id)
        mutableStateOf(app.yxi.agent.Recent.get(host.id).filter { it.name in names })
    }
    var dpad by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf(true) }

    /** 终端最后一次收到数据的时刻。用来判断「登录 shell 安静下来了没有」 */
    val lastOutput = remember { java.util.concurrent.atomic.AtomicLong(0) }
    /** 每重连一次 +1，用它作为「重建整条连接」的键。jsch 的 Session 不能复用，只能新建 */
    var generation by remember(host.id) { mutableStateOf(0) }

    // ⚠️ **终端永远深底，不跟着界面风格走。**
    // ANSI 彩色输出是按深底配的：浅底上黄色、亮绿几乎看不见，而那恰恰是
    // 警告和 diff 用的颜色。代价是浅色风格下切到终端有一下明暗跳变 ——
    // 自觉的取舍，见 [app.yxi.ui.theme.GeminiPalette] 的注释。
    val fg = app.yxi.ui.theme.TerminalFg
    val bg = app.yxi.ui.theme.TerminalBg
    val focus = remember { FocusRequester() }
    // 粘滞修饰键：工具条点了 Ctrl，下一个从软键盘来的字符带上 Ctrl（控件会自动清）
    val stickies = remember { app.yxi.term.StickyModifiers() }
    var kbVisible by remember { mutableStateOf(false) }
    /**
     * 历史模式：开着时在终端上**上下滑动 = 给 Claude Code 送翻页键**。
     *
     * ⚠️ **前后错了两次方向，实测才定下来的**：
     *
     * ① 让终端控件自己滚 —— **不行**。termlib 的 `ScrollController` 在 Kotlin 层是
     *    `internal`，拿编译器验过：`Cannot access 'interface ScrollController':
     *    it is internal in file`，外部一行都碰不到。
     * ② 驱动 tmux 的 copy-mode —— **也不行**，而且这个错更隐蔽：copy-mode 进得去，
     *    界面一切正常，但 tmux 右上角显示 `[0/0]`。查出来是
     *    **`alternate_on=1`** —— Claude Code 是全屏 TUI，占着**备用屏**，
     *    输出**根本不进 tmux 的历史**（实测本机所有会话 `history_size` 全是 0）。
     *    也就是说 tmux 那边压根没有东西可翻。
     * ③ **正解**：往上翻的是 **Claude Code 自己的视图**。它认 PageUp/PageDown，
     *    也认**鼠标滚轮**（它为点选项本来就开了鼠标追踪）。
     *    最后用滚轮：PageUp 一下 8 行，划一下就蹦过一整屏（用户说「像有档位」）；
     *    滚轮一下 1 行，映射到手指位移就平滑了。见 [HistoryScrim]。
     *
     * 教训：**「看不到前面的输出」这句话里的「输出」，得先搞清楚它存在谁手里。**
     * 我先后假设是控件、是 tmux，都错了 —— 它在那个全屏程序自己的缓冲里。
     */
    var history by remember(sessionName) { mutableStateOf(false) }
    /** 中文输入的退路，见 [app.yxi.term.TerminalView] 的类注释 */
    /** 对话里点了个文件路径要跳过去看。用完即清，见 [FilesScreen] 的 `onJumped` */
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var composer by remember { mutableStateOf<org.connectbot.terminal.ComposeController?>(null) }
    var composing by remember { mutableStateOf(false) }

    // ⚠️ **compose mode 默认就开。** 它名字叫「退路」，实际是**唯一**能让手机原生输入法
    // 好好干活的路：termlib 的 `ImeInputView.onCreateInputConnection` 把 inputType 报成
    // `NO_SUGGESTIONS | VISIBLE_PASSWORD`（不含 `TYPE_CLASS_TEXT`）—— 输入法一看「像密码框」，
    // 中文不给候选词、英文不给联想。那个值**写死在库里，没有参数可传**
    // （javap 翻过 `Terminal()` 和 `ImeInputView` 的全部签名），
    // 能拨的开关只有 compose mode 一个。
    //
    // 代价：变成「先攒一行、回车整行提交」（`Key.Enter → ComposeMode.commit()`，Esc 取消）。
    // 对**敲命令**来说这正好就是行编辑；只有 vim / less / y-n 这种**逐键**交互要关掉，
    // 工具条上的「中」就是干这个的。
    //
    // ⚠️ 工具条那些键（esc/tab/^C/方向键）不受影响 —— 它们直接 `shell.write()`，
    // 根本不过 IME，所以 compose 开着照样能打断。
    LaunchedEffect(composer) {
        val c = composer ?: return@LaunchedEffect
        c.startComposeMode()
        composing = c.isComposeModeActive
    }
    /** 语音识别出来的话，**先摆在这儿等你确认**，绝不直接送进终端 */
    var heard by remember { mutableStateOf<String?>(null) }
    val listen = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        heard = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    // ⚠️ 控件量出自己多大是在**连接建好之前**发生的，那一刻 shell 还是 null，
    // 尺寸就丢了；而尺寸之后不再变，onResize 也不会再触发一次 ——
    // 于是 tmux 那头永远停在写死的 80x24，而控件其实只有四十几列。
    // 表现是**终端画面整个是花的**（折行错位、边框断开），看着像连不上。
    // 所以把最后一次尺寸记下来，shell 一建好就补送。见 TROUBLESHOOTING #77。
    val lastDim = remember { java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null) }

    // 终端仿真器**先于连接建好** —— 这样连接过程中的报错也能直接写进终端显示出来
    val emulator: TerminalEmulator = remember(host.id) {
        TerminalEmulatorFactory.create(
            looper = Looper.getMainLooper(),
            initialCols = 80, initialRows = 24,
            defaultForeground = fg, defaultBackground = bg,
            onKeyboardInput = { bytes -> scope.launch { shell?.write(bytes) } },
            onResize = { dim ->
                lastDim.set(dim.columns to dim.rows)
                scope.launch { shell?.resize(dim.columns, dim.rows) }
            },
            autoDetectUrls = true,
        )
    }

    LaunchedEffect(host.id, generation) {
        // 预热的那条还活着就直接用 —— 省掉 TCP + 握手 + ed25519（手机上 1~3 秒）
        preconnected?.takeIf { it.isAlive && generation == 0 }?.let {
            ssh = it
            status = null
            chatBlocked = when {
                sessionName == null -> t("没有指定会话")
                TranscriptStream.latestFor(it, cwd) == null -> t("这个会话里没跑过 Claude Code")
                else -> ""
            }
            return@LaunchedEffect
        }
        var wait = 700L
        while (true) {
            val c = connect() ?: run { status = t("这台主机还没有可用的认证方式"); return@LaunchedEffect }
            val err = runCatching { c.session.connect(); ssh = c.session }.exceptionOrNull()
            if (err is kotlinx.coroutines.CancellationException) throw err   // 同上：取消不是连接失败
            if (err == null) break
            // 指纹变了绝不重试 —— 那不是网络问题，重试只会一遍遍撞同一堵墙
            if (c.known.changedDetected || generation == 0) { status = c.explain(err); return@LaunchedEffect }
            status = t("连接断了，正在重连…")
            delay(wait); wait = (wait * 2).coerceAtMost(5_000)
        }
        status = null
        // 对话模式要有转录才有内容可渲染。没有就置灰**并说明原因** —— 灰着不说话最气人
        chatBlocked = when {
            sessionName == null -> t("没有指定会话")
            TranscriptStream.latestFor(ssh!!, cwd) == null -> t("这个会话里没跑过 Claude Code")
            else -> ""
        }
    }

    // 第一次进终端模式才开 shell
    LaunchedEffect(mode, ssh) {
        if (mode != Mode.Terminal || shell != null) return@LaunchedEffect
        val s = ssh ?: return@LaunchedEffect
        runCatching {
            // 重连时会话早就建好、选项也设过了，省掉这一次往返
            if (sessionName != null && generation == 0) runCatching {
                s.exec(
                    "tmux has-session -t $sessionName 2>/dev/null || tmux new-session -d -s $sessionName; " +
                        // ⚠️ **关掉这个会话的状态栏。** 手机上它白占一行，而会话名我们顶栏已有
                        // （那条 `[cc-mail] 0:claude*` 是重复）。用 `-t 会话` 只关 Yxi 开的，别动桌面别的会话。
                        //
                        // ⚠️ 这里用**shell 的 `; `** 收尾，起一个**独立的 tmux 调用** ——
                        // 不能用 tmux 的 `\;` 串进后面那串，否则后面那个 `tmux` 会变成
                        // set-option 的参数，整条命令失效（状态栏关不掉，实测踩过）。
                        "tmux set -t $sessionName status off; " +
                        "tmux set -g set-titles on \\; set -g mouse on \\; set -g status-right ''"
                )
            }
            // 开通道时就用控件的真实尺寸；量不到才退回 80x24
            val (c0, r0) = lastDim.get() ?: (80 to 24)
            val sh = s.openShell(c0, r0)
            shell = sh
            // 万一 onResize 在这之后才来，上面那行已经对了；万一在这之前来过，这里补一次
            lastDim.get()?.let { (c, r) -> runCatching { sh.resize(c, r) } }
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
                    lastOutput.set(System.currentTimeMillis())
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            }
            if (sessionName != null) {
                // ⚠️ 不能连上就写：登录 shell（starship 那种）初始化时写进去的字节
                // 会被 tty 回显后冲掉 —— 实测现象是命令回显了却没执行（TROUBLESHOOTING #18）。
                //
                // 原来是等第一批输出 + 死等 900ms。现在改成**等它安静下来**：
                // 输出停了 250ms 就认为 shell 就绪。快的机器 ~0.4s 走完（重连更快），
                // 慢的机器也不会因为固定值太短而踩回 #18 —— 两头都更好。
                runCatching { kotlinx.coroutines.withTimeout(8000) { ready.await() } }
                withTimeoutOrNull(3_000) {
                    while (System.currentTimeMillis() - lastOutput.get() < 250) delay(60)
                }
                // ⚠️ **`-d` 不能省。** 用户在桌面也 attach 着同一会话时，tmux 把窗口撑到那个
                // 宽客户端的尺寸，手机 46 列塞不下 → 整屏折行、状态栏堆成一条条绿条（用户截图）。
                // `-d` 踢掉别的客户端，手机成唯一客户端 → 窗口缩到手机尺寸 → 不花屏。这正合遥控器定位。
                sh.write("tmux attach -d -t $sessionName\n")
                attached = sessionName
            }
        }.onFailure {
            // ⚠️ **取消不是失败。** 切模式 / 退出工作区时这个 effect 会被取消，
            // 挂起点抛 CancellationException，被 runCatching 一并吞掉 ——
            // 于是界面上留下一句「终端起不来：The coroutine scope left the composition」，
            // 而且**再进来也不会消失**（status 是记住的）。用户看到的就是「终端起不来」。
            // Kotlin 的铁律：CancellationException 必须原样抛回去。
            if (it is kotlinx.coroutines.CancellationException) throw it
            status = t("终端起不来：%s").format(it.message)
        }
    }

    /**
     * 换会话：**不重连、不重开通道**。
     *
     * 已经 attach 着的时候，`tmux switch-client` 就是干这个的 —— 同一个 tmux 客户端换到
     * 另一个会话，一次往返、几十毫秒。之前的做法是整个 [Workspace] 按 (host, session) 重建，
     * 等于重新握手 + ed25519 认证 + 起登录 shell，模拟器上两三秒。
     *
     * ⚠️ 还没 attach（终端模式还没进过）就什么都不用做 —— 上面那个 effect 会用新的
     * `sessionName` 直接 attach 过去。
     */
    LaunchedEffect(sessionName, shell) {
        val target = sessionName ?: return@LaunchedEffect
        val s = ssh ?: return@LaunchedEffect
        if (shell == null || attached == null || attached == target) return@LaunchedEffect
        runCatching {
            s.exec("tmux has-session -t '$target' 2>/dev/null || tmux new-session -d -s '$target'")
            s.exec("tmux switch-client -t '$target' 2>/dev/null")
            attached = target
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            status = t("切不过去：%s").format(it.message)
        }
    }

    /**
     * 看门狗：连接「假活」了就重建。
     *
     * ⚠️ 手机切网（WiFi↔4G、进电梯）时 TCP 不会立刻报错，界面看着正常但敲什么都没反应。
     * 靠 SSH 心跳（[SshSession] 里 4s×2）判死，这里每 1.5 秒查一次。
     * **光标位置靠 tmux 保住** —— 重连后重新 `tmux attach`，那一屏原样回来。
     * 没有 tmux 的裸终端（不针对会话时）就真的丢，这是 SSH 的性质，不是这里能补的。
     */
    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        while (true) {
            delay(600)
            if (s.isAlive) continue
            status = t("连接断了，正在重连…")
            runCatching { shell?.close() }; shell = null
            runCatching { sftp?.close() }; sftp = null
            generation++      // 触发上面那个 effect 重建
            return@LaunchedEffect
        }
    }

    LaunchedEffect(mode, ssh) {
        if ((mode == Mode.Files || mode == Mode.Chat) && sftp == null) {
            sftp = ssh?.let { runCatching { it.openSftp() }.getOrNull() }
        }
    }
    LaunchedEffect(mode, sessionName) { Prefs.setMode(ctx, host.id, sessionName, mode) }
    LaunchedEffect(shell, mode) {
        if (mode == Mode.Terminal && shell != null) runCatching { focus.requestFocus() }
    }

    DisposableEffect(host.id) {
        onDispose {
            shell?.close(); sftp?.close()
            // ⚠️ 预热那条归 MainActivity，断了下次进来又要重连 —— 只断自己建的
            if (ssh !== preconnected) ssh?.disconnect()
        }
    }

    // ⚠️ `imePadding()` 不能省：`enableEdgeToEdge` 下窗口是铺满的，
    // 软键盘弹起来会**盖住键盘工具条** —— 而 esc / tab / ^C 恰恰是打字时最需要的那几个键。
    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 10.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 点会话名 = 唤出悬浮排列。换会话是这个 app 最高频的动作，
            // 不该让人退回看板再滚一遍列表
            Box(Modifier.weight(1f)) {
            Column(
                // ⚠️ **「下拉开着时再点一次换成卡片墙」这条实测走不通** ——
                // Android 的 DropdownMenu 会盖一层全屏透明遮罩，第二次点被它吃掉，
                // 只会触发 onDismissRequest 关菜单，**根本碰不到标题**。
                // 写了也是死代码，所以改成：点 = 下拉（置顶那几个），
                // **长按 = 直接开卡片墙**，另外下拉里也留了「全部会话…」。
                Modifier.combinedClickable(
                    enabled = ssh != null,
                    onClick = { menu = true },
                    onLongClick = { switcher = true },
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        sessionName?.removePrefix("cc-") ?: host.alias,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    )
                    Text("▾", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                Text(
                    status ?: cwd,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Dim, maxLines = 1,
                )
            }

            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // ⚠️ **复制路径放第一项。** 用户要的是「把这个会话的目录粘到别处去」——
                // 而路径就显示在头部那行、点它弹的就是这个菜单，所以放在这儿是最短的路。
                // 不做成「点路径直接复制」：那块地方的点击已经归这个菜单了，
                // 抢过去会让「切会话」这个更常用的动作失灵。
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(t("复制路径"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                cwd,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Dim, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                            )
                        }
                    },
                    onClick = {
                        menu = false
                        app.yxi.ui.DevMode.copy(ctx, cwd, "path")
                        android.widget.Toast.makeText(ctx, t("路径已复制"), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
                HorizontalDivider()
                if (quick.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(t("还没有置顶的会话"), style = MaterialTheme.typography.bodySmall, color = Dim) },
                        onClick = { menu = false; switcher = true },
                    )
                } else quick.forEach { sess ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(sess.short, style = MaterialTheme.typography.bodyMedium)
                                if (sess.detail.isNotEmpty()) Text(
                                    sess.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Dim, maxLines = 1,
                                )
                            }
                        },
                        trailingIcon = { if (sess.name == sessionName) Text("✓", color = Muted) },
                        onClick = {
                            menu = false
                            // ⚠️ 只改这两个值 —— 跟卡片墙走同一条路，不重建连接
                            sessionName = sess.name
                            cwd = sess.cwd
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(t("全部会话…"), style = MaterialTheme.typography.bodyMedium, color = Muted) },
                    onClick = { menu = false; switcher = true },
                )
            }
            }
            if (mode == Mode.Terminal) {
                listOf("⌨" to (bar to { bar = !bar }), "✛" to (dpad to { dpad = !dpad }))
                    .forEach { (icon, st) ->
                        val (on, toggle) = st
                        Surface(
                            color = if (on) SurfaceContainerHigh else SurfaceContainer, shape = Pill,
                            modifier = Modifier.clip(Pill).clickable(onClick = toggle),
                        ) {
                            Text(
                                icon, Modifier.padding(12.dp, 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) Copper else Muted,
                            )
                        }
                    }
            }
        }

        ModeSwitcher(mode, chatBlocked) { mode = it }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.Terminal -> {
                    TerminalView(
                        emulator, focus, stickies,
                        showKeyboard = kbVisible,
                        onKeyboardVisible = { kbVisible = it },
                        onComposeController = { composer = it },
                        fg = fg, bg = bg,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // ⚠️ **只在历史模式下才盖这一层。** 平时盖着的话，
                    // 终端自己的选词、长按、URL 点击全被吃掉 —— 为了一个功能废掉三个
                    if (history) HistoryScrim(
                        onScroll = { up ->
                            // ⚠️ **送鼠标滚轮，不送 PageUp。** 实测 PageUp 一下翻 8 行，
                            // 手指划一下就蹦过去一整屏 —— 就是用户说的「像有档位一样」。
                            // 滚轮（SGR 1006）一下**只滚 1 行**，映射到手指位移就是平滑滚动。
                            // 上滚 `ESC[<64;1;1M`，下滚 `ESC[<65;1;1M`（坐标用 1,1 永远合法）。
                            //
                            // ⚠️ 能用的前提是 Claude Code 开着鼠标追踪（它为点选项本来就开了），
                            // 所以滚轮字节能被它接住。实测在真会话上滚一下顶行 44→43。
                            val b = if (up)
                                byteArrayOf(27, 91, 60, 54, 52, 59, 49, 59, 49, 77)   // ESC[<64;1;1M
                            else
                                byteArrayOf(27, 91, 60, 54, 53, 59, 49, 59, 49, 77)   // ESC[<65;1;1M
                            scope.launch { shell?.write(b) }
                        },
                    )
                }
                Mode.Chat -> ChatScreen(
                    ssh, sftp, sessionName.orEmpty(), cwd, host.id,
                    onOpenPath = { p -> jumpTo = p; mode = Mode.Files },
                    modifier = Modifier.fillMaxSize(),
                )
                Mode.Files -> FilesScreen(
                    sftp, cwd, jumpTo, onJumped = { jumpTo = null }, Modifier.fillMaxSize(),
                )
                // 实验室：UI 实验台。多数 demo 纯本地；「审核勾选」要 ssh 把结果写回服务器。
                Mode.Lab -> LabScreen(ssh, Modifier.fillMaxSize())
            }
            if (mode == Mode.Terminal && dpad) {
                DPad(
                    Modifier.align(Alignment.BottomEnd).padding(14.dp),
                    send = { bytes -> scope.launch { shell?.write(bytes) } },
                )
            }
        }

        if (mode == Mode.Terminal && bar) {
            KeyBar(
                // ⚠️ Ctrl 走 termlib 的 ModifierManager，不是自己改字节 ——
                // 控件在每次输入之后会替我们 clearTransients()，「点一下只管一个键」白送
                ctrlArmed = stickies.ctrl,
                onCtrl = { stickies.ctrl = !stickies.ctrl },
                onKeyboard = { kbVisible = !kbVisible },
                composing = composing,
                history = history,
                onHistory = { history = !history },
                onVoice = {
                    // ⚠️ **没有语音识别时要说一声。** 原来只是 `runCatching { launch }` ——
                    // 兜住了不崩，但**失败完全静默**：点了麦克风什么都不发生，一个字的解释都没有。
                    // 这不是边角情况：用户的荣耀 **GMS 是关的**，实测把识别服务禁掉之后
                    // `pm query-activities` 是 0 个 —— 也就是他手机上这个按钮一直是死的。
                    val vi = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, t("说吧"))
                    if (vi.resolveActivity(ctx.packageManager) == null) {
                        android.widget.Toast.makeText(
                            ctx, t("这台手机上没有语音识别（多半是没装或关了 Google 服务）"),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else runCatching { listen.launch(vi) }.onFailure {
                        android.widget.Toast.makeText(
                            ctx, t("叫不起语音识别：%s").format(it.message ?: ""),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onCompose = {
                    composer?.toggleComposeMode()
                    composing = composer?.isComposeModeActive ?: false
                },
                send = { bytes -> scope.launch { shell?.write(bytes) } },
            )
        }
    }
    // ⚠️ **终端模式下语音必须先确认。**
    // 识别错一个字，在服务器上就是**另一条命令**。对话模式还能在输入框里改，
    // 终端是直接打进 PTY 的 —— 没有反悔的机会。
    // 置顶的名字存在手机上，状态和 cwd 得问服务器。
    //
    // ⚠️ **缓存先画、后台再刷**：抓那一趟在手机网络下动辄一两秒，
    // 原来它挡在「点开菜单」和「看到内容」之间，用户的原话是
    // 「点击后显示有点太慢了，是不是我点击的时候才加载」——就是。
    // 现在 `quick` 开局就是缓存那份（上面），这里只负责把它刷新掉。
    LaunchedEffect(menu) {
        if (!menu) return@LaunchedEffect
        val s0 = ssh ?: return@LaunchedEffect
        val names = Pinned.get(ctx, host.id)
        // ⚠️ 一个置顶都没有时**不能把 quick 清空**：菜单里还要靠它显示
        // 「全部会话…」那条，清了就成了一个空菜单
        if (names.isEmpty()) return@LaunchedEffect
        runCatching { app.yxi.agent.SessionProbe.snapshot(s0) }
            .onSuccess { all ->
                app.yxi.agent.Recent.put(host.id, all)
                quick = all.filter { it.name in names }
            }
    }

    if (switcher) {
        Switcher(
            ssh = ssh,
            current = sessionName,
            hostId = host.id,
            onPick = { picked ->
                // ⚠️ 只改这两个值 —— 连接、SFTP 通道、终端仿真器全都按 host 记，一个都不重建。
                // 终端那边由 `tmux switch-client` 切过去，几十毫秒。
                sessionName = picked.name
                cwd = picked.cwd
            },
            onDismiss = { switcher = false },
        )
    }

    heard?.let { text ->
        AlertDialog(
            onDismissRequest = { heard = null },
            title = { Text(t("听到的是这句")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = SurfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
                        Text(
                            text, Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    Text(
                        t("确认后会原样打进终端并回车。识别错了就取消重说。"),
                        style = MaterialTheme.typography.labelSmall, color = Dim,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val t = text; heard = null
                    scope.launch { shell?.write((t + "\n").toByteArray()) }
                }) { Text(t("发进终端")) }
            },
            dismissButton = { TextButton({ heard = null }) { Text(t("取消")) } },
        )
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
                    modifier = Modifier.weight(1f).height(38.dp).clip(Pill).clickable {
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
            title = { Text(t("对话模式用不了")) },
            text = { Text(it + t("。\n\n对话模式渲染的是 Claude Code 的转录文件；这个会话里没有，所以没东西可显示。终端和文件模式照常可用。")) },
            confirmButton = { TextButton({ why = null }) { Text(t("知道了")) } },
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

/**
 * 历史模式下盖在终端上的那层：**把上下滑动翻译成鼠标滚轮**。
 *
 * ⚠️ 送的是给**那个全屏程序**的滚轮事件（SGR 1006），不是 tmux 的 copy-mode ——
 * Claude Code 占着备用屏，tmux 那边 `history_size` 是 0，没东西可翻。
 * ⚠️ **早先送的是 PageUp/PageDown，一下 8 行**，手感像有档位（用户反馈）。
 * 滚轮一下 1 行，映射到手指位移就平滑了。
 *
 * ⚠️ 方向：**手指往下 = 看更早**（跟所有列表一致）→ PageUp。写反了用户会觉得
 * 「越滑越回不去」。
 */
@Composable
private fun HistoryScrim(onScroll: (up: Boolean) -> Unit) {
    // ⚠️ **每滚一「行」的手指位移。** 滚轮一下滚 1 行，这里定「手指走多少像素算一行」。
    // 太小（跟着像素走）会送出几十个滚轮事件、每个都要 SSH 一个来回，反而卡；
    // 太大又回到「档位」感。18dp 上下实测手感接近原生滚动 —— 一次快划送十来个事件，能跟上。
    val LINE = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    var acc by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                state = androidx.compose.foundation.gestures.rememberDraggableState { dy ->
                    acc += dy
                    // 手指往下 = 看更早 = 上滚
                    while (acc >= LINE) { acc -= LINE; onScroll(true) }
                    while (acc <= -LINE) { acc += LINE; onScroll(false) }
                },
                onDragStopped = { acc = 0f },
            ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        ) {
            Text(
                t("历史模式 · 上下滑动翻页 · 再点「历史」退出"),
                Modifier.padding(12.dp, 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
