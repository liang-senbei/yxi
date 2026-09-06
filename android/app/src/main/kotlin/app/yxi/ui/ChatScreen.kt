package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import app.yxi.ui.theme.Muted
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Pill = RoundedCornerShape(100.dp)

/**
 * 对话渲染模式 —— **这是主界面**（PRD 附录 D）。
 *
 * 数据源是 `~/.claude/projects` 下的转录 JSONL，**不刮终端屏幕**。
 * 发消息走 `tmux send-keys` 打进那个活着的会话，
 * 所以 Claude Code 的配置、权限、MCP、skills 原样生效 —— 我们不重新实现 agent 协议。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    /** ⚠️ 连接由 [Workspace] 持有并传进来 —— 切模式时这个 composable 会销毁，连接不能跟着断 */
    ssh: SshSession?,
    /** 附件上传要用。没有就把回形针按钮藏起来 */
    sftp: app.yxi.ssh.Sftp?,
    sessionName: String,
    cwd: String,
    /** 草稿按主机分开存要用它。见 [Drafts] */
    hostId: String,
    /** 点了 AI 回复里的文件路径。见 [app.yxi.agent.Linkify] */
    onOpenPath: (String) -> Unit = {},
    /** 光晕状态 (忙, 等你, 回答正在到达)：光晕由 Workspace 画（要铺到页眉那一截），这里只报状态 */
    onGlow: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> },
    /** 顶上那条「⚡模式 / 模型 / 思考 / 上下文 / 今日」显不显示 —— 由页眉上的 ⚡ 按钮开关（用户：常驻太难看） */
    showStats: Boolean = true,
    /** 上划收起 / 下滑展开（学 X）：true = 收起。页眉在 Workspace 那边，靠这个回调同步 */
    /** 报「上下栏退场了多少」（0 = 全在，1 = 全退完），跟着手指连续走 */
    onBars: (Float) -> Unit = {},
    /** 悬浮页眉的高度（px）。⚠️ **内容永远按它留白，不跟着收起变** —— 学 X：栏是盖在正文上的，
     *  退场时只是把栏挪走，底下的字本来就在那儿，不重排、不跳。 */
    headerPx: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ⚠️ **草稿从盘上读回来，不是空串。** 切模式 / 退出去 / App 被杀，打的字都还在。
    // 这行原来是 `remember { mutableStateOf("") }` —— 切一次终端字就没了（[Drafts] 的注释）。
    var draft by remember(sessionName) { mutableStateOf(Drafts.get(ctx, hostId, sessionName)) }
    var staged by remember(sessionName) { mutableStateOf<List<app.yxi.agent.Attachments.Staged>>(emptyList()) }

    // ⚠️ **两处都要存**：
    //   · 打字时防抖存一次 —— 管「App 被系统杀掉」
    //   · 销毁时立刻存一次 —— 管「切模式 / 退出去」，那一下防抖还没到点
    // 只写其中一处都会在某种走法下丢字。
    LaunchedEffect(draft) {
        delay(600)
        Drafts.set(ctx, hostId, sessionName, draft)
    }
    DisposableEffect(sessionName) {
        onDispose { Drafts.set(ctx, hostId, sessionName, draft) }
    }
    /**
     * 正在传 / 传失败的附件，一个一张小卡（名字 + 进度环 + ✕）。
     * ⚠️ **每个文件各自一个协程、各自能取消**（用户要的：上传中要有 ✕ 取消**该文件**）。
     * 原来一批多个是一条协程顺序传、只有一句「传着… n/m」—— 单个文件既看不见也停不下来，
     * 中途切走整批悄悄没了。现在：选中即出卡，传完变成 [staged] 里的标签，失败留在卡上点一下重试。
     * 仍然**一个一个传**（Mutex 排队）：序号要连得上，SFTP 通道也不吃并发。
     */
    val queue = remember(sessionName) { mutableStateListOf<Upload>() }
    val uploadLock = remember(sessionName) { kotlinx.coroutines.sync.Mutex() }
    /**
     * 这个会话此刻占多少上下文。⚠️ 顺着转录一起解出来的，**不额外跑一趟服务器**。
     * 名字不叫 `ctx` —— 这个文件里 `ctx` 已经是 `LocalContext`。
     */
    var ctxUse by remember(sessionName) { mutableStateOf<app.yxi.agent.Transcript.Ctx?>(null) }
    /** 这台机器**今天**烧了多少。⚠️ 拿不到就是 null，整块藏掉（[app.yxi.agent.Usage] 的规矩） */
    var todayUse by remember(sessionName) { mutableStateOf<app.yxi.agent.Today?>(null) }
    /** 快路的模型名单（本地选单开着）。null = 没开 */
    var fastModels by remember { mutableStateOf<app.yxi.agent.Model.Available?>(null) }
    var showEffort by remember { mutableStateOf(false) }
    /** `/model` 选单开着的时候放这儿。null = 没开 */
    var models by remember(sessionName) { mutableStateOf<List<app.yxi.agent.Model.Choice>?>(null) }
    var modelBusy by remember(sessionName) { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    /** 正在放大看的那张附件图。null = 没在看 */
    var preview by remember { mutableStateOf<app.yxi.agent.Attachments.Staged?>(null) }

    // 选文件（图片和任意文件走同一个选择器，类型看 MIME）
    //
    // ⚠️ **可以一次选多个**（用户要的）。用 `GetMultipleContents` 不是 `GetContent`：
    // 一张一张选、传完再点加号再选，五张图就是五轮操作 —— 手机上这个代价很实在。
    // ⚠️⚠️ **上传协程活得比一次 recomposition 长,断线重连后 `ssh` 参数会换成新对象。**
    //    每次尝试都从这儿重新读当前那条,别把启动那一刻的对象钉死(见 [app.yxi.agent.Uploader])。
    val latestSsh = rememberUpdatedState(ssh)
    /** 等一条活着的连接,最多等 maxWaitMs。轮询、不用 snapshotFlow(结构相等时不发新值,会永远挂着) */
    suspend fun aliveSsh(maxWaitMs: Long): app.yxi.ssh.SshSession? {
        val t0 = System.currentTimeMillis()
        while (true) {
            latestSsh.value?.takeIf { it.isAlive }?.let { return it }
            if (System.currentTimeMillis() - t0 >= maxWaitMs) return null
            delay(500)
        }
    }
    fun startUpload(up: Upload) {
        if (ssh == null) { up.error = t("还没连上"); return }
        up.error = null; up.progress = 0f; up.cancelled = false
        up.job = scope.launch {
            try {
            // ⚠️⚠️ **不再把整个文件读进内存。** 原来这里 `readBytes()`,一段视频就是一次几百 MB 的分配,
            //    直接 OOM / 被系统杀 —— 「上传视频经常失败」的根(Sftp.write 那条注释)。
            //    这里只先探一下**读不读得出来 + 多大**(授权过期、文件没了跟「传失败」是两码事,要分开报)。
            val size = withContext(Dispatchers.IO) {
                app.yxi.ssh.catching {
                    ctx.contentResolver.openAssetFileDescriptor(up.uri, "r")?.use { it.length } ?: -1L
                }.getOrNull()
            }
            if (size == null || size == 0L) { up.error = t("这个文件读不出来"); return@launch }
            // 排队:一个一个传,序号才连得上(并发时每个协程读到同一份 staged,五张全叫「图片1」,#156)
            uploadLock.withLock {
                if (up.cancelled) return@withLock
                val idx = staged.count { it.isImage == up.isImage } + 1
                val stamp = java.text.SimpleDateFormat("MMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
                // ⚠️ 重试/续传/换通道的规矩都在 [app.yxi.agent.Uploader] 里 —— 抽出去是为了让压力测试
                //    能跑到**这段真代码**(它原来在 Composable 里,测不着,修没修全靠读代码判断)。
                val r = app.yxi.agent.Uploader.upload(
                    aliveSsh = ::aliveSsh,
                    open = { ctx.contentResolver.openInputStream(up.uri) ?: error(t("这个文件读不出来")) },
                    total = size, sessionName = sessionName, name = up.name,
                    index = idx, isImage = up.isImage, stamp = stamp,
                    cancelled = { up.cancelled },
                    progress = { done, total -> up.progress = if (total > 0) done.toFloat() / total else 0f },
                )
                val ok = r.getOrNull()?.copy(localUri = up.uri.toString())
                when {
                    up.cancelled -> {}
                    ok != null -> { staged = app.yxi.agent.Attachments.renumber(staged + ok); queue.remove(up) }
                    else -> up.error = app.yxi.agent.Uploader.explain(r.exceptionOrNull() ?: RuntimeException())
                }
            }
            latestSsh.value?.let { s -> runCatching { app.yxi.agent.Attachments.sweep(s) } }   // 顺手清 3 天前的
            } finally {
                // ⚠️⚠️ **任何退出路径都必须让这张卡进入终态。**
                //    留在队列里而 `error == null` 意味着「还在传」,而发送按钮的条件是
                //    `queue.none { it.error == null }` —— 于是这张卡会把发送**永久变灰**,
                //    屏幕上还不给任何理由(用户 2026-09-04:「点发送在对话里没有发送出去」)。
                //    协程被取消(切页面、进程回收)时最容易撞上:那时候下面那个 when 根本不会执行。
                if (!up.cancelled && up.error == null && queue.contains(up)) {
                    up.error = t("传输中断了")
                }
            }
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // 选中的每一个先出卡，再各自开传 —— 用户立刻看到「都进来了、各自传到哪」
        for (u in uris) {
            val mime = ctx.contentResolver.getType(u).orEmpty()
            val isImage = mime.startsWith("image/")
            val up = Upload(u, queryName(ctx, u) ?: (if (isImage) "image" else "file"), isImage)
            queue += up
            startUpload(up)
        }
    }

    // 今日用量。⚠️ **不跟着对话刷**：它两分钟才有意义地变一次，
    // 而 `ccusage` 要扫整个 `~/.claude/projects`，跟着 300ms 的刷新节奏跑会把机器拖死。
    LaunchedEffect(ssh) {
        val s0 = ssh ?: return@LaunchedEffect
        while (true) {
            todayUse = app.yxi.ssh.catching { app.yxi.agent.Usage.today(s0) }.getOrNull()
            delay(120_000)
        }
    }

    /**
     * 语音走哪条路。**三层，从好到差**：
     *  ① [onDevice] —— 手机上算。不联网、不依赖服务器、离线可用，模型下好了就走它。
     *  ② [serverAsr] —— 服务器上有 `yxi-asr`。准，但要那台机器装过。
     *  ③ 都没有 —— 退回系统的 `RecognizerIntent`（**它自己不识别**，只是转交给
     *     手机上的识别器 App；GMS 关掉的手机上一个都没有，那按钮就是死的，见 #152）。
     */
    var onDevice by remember { mutableStateOf(app.yxi.agent.OnDeviceAsr.ready(ctx) && app.yxi.agent.OnDeviceAsr.supported) }
    var serverAsr by remember(ssh) { mutableStateOf(false) }
    /** 正在录音 */
    var recording by remember { mutableStateOf(false) }
    /** 正在识别（传上去 + 跑模型，实测 3~5 秒） */
    var asrBusy by remember { mutableStateOf(false) }
    val recorder = remember { Recorder() }
    var hasMic by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        if (!granted) android.widget.Toast.makeText(
            ctx, t("没给录音权限，用不了按住说话"), android.widget.Toast.LENGTH_LONG,
        ).show()
    }
    // ⚠️ **一条连接只探一次。** 它是一条 exec，本身不贵，但每次点麦克风都问一遍
    // 就是每次多一个来回的延迟 —— 而这个答案在一条连接的生命周期里不会变。
    // ⚠️ 模型一装好，麦克风就从「点一下开系统识别」变成「按住说话」—— 手势变了得说一声，
    //    不然用户照旧点一下，录了 50ms 被当误触丢掉，什么都不发生（用户原话：「下了模型语音就没用了」）。
    var holdHintAt by remember { mutableStateOf(0L) }
    LaunchedEffect(onDevice, serverAsr) {
        if (!(onDevice || serverAsr)) return@LaunchedEffect
        val p = ctx.getSharedPreferences("yxi", android.content.Context.MODE_PRIVATE)
        if (!p.getBoolean("hint.holdmic", false)) {
            android.widget.Toast.makeText(ctx, t("语音识别就绪：点一下麦克风开始说，再点一下结束"), android.widget.Toast.LENGTH_LONG).show()
            p.edit().putBoolean("hint.holdmic", true).apply()
        }
    }
    LaunchedEffect(ssh, app.yxi.agent.AsrModel.installed) {
        onDevice = app.yxi.agent.OnDeviceAsr.ready(ctx) && app.yxi.agent.OnDeviceAsr.supported
        // ⚠️ 手机上能算就不问服务器了 —— 省一个来回，也别去把服务器那个守护叫醒
        if (onDevice) return@LaunchedEffect
        val s0 = ssh ?: return@LaunchedEffect
        serverAsr = app.yxi.agent.Voice.available(s0)
    }
    // ⚠️ 界面没了要把录音停掉，否则麦克风一直被占着（别的 app 也用不了）。
    // ⚠️ 顺手把识别器放掉 —— 它压着几百 MB，常驻会让安卓在内存紧张时直接杀掉整个 App。
    DisposableEffect(Unit) {
        onDispose { runCatching { recorder.stop() }; app.yxi.agent.OnDeviceAsr.release() }
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
    /**
     * 进对话页时已经在的那批条目的 key。之后**新出现**的才做滑入淡入
     * —— 初始那几百条一起滑入是灾难，而且 从参考款抄的也只是「新消息进来」那一下。
     * null = 还没拿到第一批。
     */
    var seenKeys by remember(sessionName) { mutableStateOf<Set<String>?>(null) }
    /**
     * 已经放过进入动画的 key。⚠️ **一条只放一次，永远。**
     * 原来只看「在不在第一批里」，于是**只要那条被重新组合**（列表跳位置、条目滚出去又滚回来、
     * 转录重灌），它就再滑入淡入一次 —— 一屏的条目同时来这么一下，就是用户录到的
     * 「整屏白一下再淡回来，一秒两次」。见 #227。
     */
    val animated = remember(sessionName) { mutableSetOf<String>() }
    val openGroups = remember { mutableStateListOf<String>() }
    val motionOn = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    LaunchedEffect(items.isNotEmpty(), sessionName) {
        // 第一批到手就记下来；之后不再动 —— 新条目的 key 不在里面，就该动画
        if (seenKeys == null && items.isNotEmpty()) seenKeys = items.map { it.key }.toSet()
    }
    var status by remember { mutableStateOf<String?>(t("连接中…")) }

    var pending by remember { mutableStateOf<Pending?>(null) }
    // ⚠️ 此刻在忙什么、有哪些输入还排着队 —— **只有屏幕知道**，转录里没有。
    // 见 Live 的类注释和 TROUBLESHOOTING #72
    var live by remember { mutableStateOf(app.yxi.agent.Live.IDLE) }
    /**
     * 这个会话的**实时状态**拿到手了没。
     *
     * ⚠️ **「我知道它闲着」和「我还不知道」不是一回事，以前画成了同一个样子。**
     * `live` 初值是 IDLE，而 `live.busy == false` 时那行 Composing… 就不画 ——
     * 于是刚从后台切回来（连接还没重建、推流还没第一帧）的那几秒，
     * 界面看起来跟「一切正常、agent 闲着」一模一样。
     * 用户报的就是这个：切回来不显示现在在干什么，等一会儿才出来 ——
     * 而且**看不出它是旧的**，这比白屏更坑。
     * ⚠️ 键里带 `ssh`：重连会换一个新的 Session 对象，那一刻就该复位。
     */
    var synced by remember(ssh, sessionName) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // 「看改动」的 git diff 文本；非空就弹出 DiffSheet
    var diffText by remember { mutableStateOf<String?>(null) }
    /** 送键前那一刻的指纹 —— 屏幕推过来后指纹变了就说明动作生效了，可以解锁。 */
    var awaitingFp by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // ── 学 X：上划（往下读）把页眉和输入栏收起来，下滑再展开；到底了、或键盘开着，一律展开 ──
    // ⚠️ 用 nestedScroll 的 onPreScroll 看手势方向，不看列表位置：方向一换就重新累计，过 28dp 才动，
    //    免得手指抖一下两条栏就上下乱跳。
    /** 上下栏退了多少像素（0 = 全在，barsMax = 收完）。⚠️ 跟着手指连续走 —— X 的自然感全在这：
     *  不是「过了阈值啪一下收掉」，而是你划多少它退多少，松手才归位。 */
    var barsOff by remember(sessionName) { mutableFloatStateOf(0f) }
    /** 输入区（含快捷语、附件条）的实际高度：列表底部留这么多，不然最后一条被悬浮的输入框盖住 */
    var composerH by remember { mutableIntStateOf(0) }
    /** 输入框现在几行 —— 多行时换成 Gemini 那种两段式（文字在上、按钮在下） */
    var lines by remember(sessionName) { mutableIntStateOf(1) }
    var multi by remember(sessionName) { mutableStateOf(false) }
    val barsMax = (if (headerPx > 0) headerPx.toFloat() else with(LocalDensity.current) { 96.dp.toPx() })
    val barsConn = remember(barsMax, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val dy = available.y
                // ⚠️ **只认手指。** 程序滚动（点 ↓ 跳底部、新消息跟随）也会走这条 —— 那一下会把栏收起来，
                //    紧接着「到底了就展开」又把它展开，一收一展就是用户看到的「↓ 一直闪」（#226）。
                if (dy == 0f || source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                // ⚠️⚠️ **只认列表自己在滚。** 输入框（[BasicTextFieldRow]，maxLines=7）也在这个
                //    Box 里，草稿一长它内部就能滚 —— 而它滚的时候会把 delta 往上派发到这里，
                //    于是「在输入框里翻自己写的字」被当成「翻聊天记录」：栏收了，字一行没动。
                //    用户 2026-09-05 报的就是这个：只能把键盘调出来靠光标挪，才看得到草稿后半截。
                //    listState.isScrollInProgress 在拖拽开始时就为真，能干净地把两者分开。
                if (!listState.isScrollInProgress) return androidx.compose.ui.geometry.Offset.Zero
                // 方向按用户实测定：往下滑（回看历史）收起，往上滑（回到最新）展开。
                barsOff = (barsOff + dy).coerceIn(0f, barsMax)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    // ⚠️ 三处会给栏做动画（松手归位 / 到底展开 / 点一下互切），**同一时刻只许一段在跑**：两段同时写 barsOff 会抖，
    //    而且 260ms 的归位会把 220ms 的互切盖回去 —— 表现是「甩一下列表、点一下想把栏叫回来，栏又自己缩回去了」
    //    （审查查出的）。所以动画统一从 animateBars 起，起新的先掐掉旧的。
    val barsJob = remember { object { var job: Job? = null } }
    fun CoroutineScope.animateBars(to: Float, ms: Int) {
        barsJob.job?.cancel()
        barsJob.job = launch {
            androidx.compose.animation.core.animate(
                barsOff, to, animationSpec = tween(ms, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) { v, _ -> barsOff = v }
        }
    }
    // 松手归位：过半就收干净，没过半就弹回来 —— 中间那个半吊子状态不留（学 X）
    LaunchedEffect(listState, barsMax) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val to = if (barsOff > barsMax / 2f) barsMax else 0f
            if (barsOff != to) animateBars(to, 260)
        }
    }
    // 到底了一律展开（新消息来了得看得见输入框）
    LaunchedEffect(Unit) {
        snapshotFlow { listState.atBottom }.collect { if (it && barsOff != 0f) animateBars(0f, 220) }
    }
    val barsFrac = (barsOff / barsMax).coerceIn(0f, 1f)
    LaunchedEffect(barsFrac) { onBars(barsFrac) }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 点一下空白处 = 上下栏收/展互切（学阅读器）。每次点 +1，effect 按 key 重启
    var barsTap by remember(sessionName) { mutableIntStateOf(0) }
    LaunchedEffect(barsTap) {
        if (barsTap == 0) return@LaunchedEffect
        animateBars(if (barsOff > barsMax / 2f) 0f else barsMax, 220)
    }
    // ⚠️ **恒定，不跟着 barsHidden 变。** 变的话：点 ↓ 到底 → 栏展开 → 底部留白变大 → 又能往下滚 →
    //    「不在底部」→ ↓ 按钮重新冒出来还往上跳一截 —— 就是用户录到的「点了一直闪」（#226）。
    //    收起时那段留白也看不见：一到底就自动展开了，收起状态下你本来就不在底部。
    val composerPad = with(LocalDensity.current) { composerH.toDp() }
    // 历史灌完了没。灌的过程中一律瞬移到底，不做动画（见下面的 LaunchedEffect）
    var settled by remember(sessionName) { mutableStateOf(false) }
    // 「粘在底部」：在底部就跟着新消息走；手动往上翻就停；点 ↓ 会重新粘上。
    // ⚠️ 关键在「一次点到底」—— 以前要点好几次，因为点一下滚到底、历史又灌进来把你顶上去。
    // 现在只要粘着，之后每条新内容都自动跟到底，不用再点。
    var stick by remember(sessionName) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // ⚠️ **只有用户亲手拖动才改 stick，程序滚动/新内容一律不碰。**
        // 上一版栽在这：活跃会话里新内容一直来，程序滚到底的那一下经常在「刚到底又被新内容顶起」
        // 之间落定 —— 被当成「不在底部」→ stick 关掉 → 跟随停 → ↓ 按钮又冒出来，永远点不到底。
        // 现在：记住这次滚动是不是用户拖的（DragInteraction），只有用户拖完才按落点定 stick；
        // 程序滚（跳底部 / 跟随）settle 时**不动 stick**，跟随就不会被自己打断。
        var userDragged = false
        launch {
            listState.interactionSource.interactions.collect {
                if (it is androidx.compose.foundation.interaction.DragInteraction.Start) userDragged = true
            }
        }
        launch {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && userDragged) { stick = listState.atBottom; userDragged = false }
            }
        }
    }

    // ⚠️ **先把上次那几条从磁盘画出来。** 这条独立于连接 ——
    // 从后台切回来时进程可能已经被系统杀过一轮（荣耀这类 ROM 很凶），
    // 那时 `items` 是空的，而重新拉转录要一个 SSH 往返 + 0.58 MB。
    // 那几秒钟里 LazyColumn 上面什么都没有 = 纯白屏。用户原话：什么都不显示。
    LaunchedEffect(sessionName, cwd) {
        if (items.isNotEmpty()) return@LaunchedEffect
        val cached = app.yxi.agent.TranscriptCache.load(ctx, sessionName, cwd)
        if (cached.isEmpty() || items.isNotEmpty()) return@LaunchedEffect
        val inc = Transcript.Incremental()
        val shown = withContext(Dispatchers.Default) { inc.add(cached.asSequence()); inc.snapshot() }
        if (items.isEmpty()) {
            items = shown; inc.ctx?.let { ctxUse = it }
            // ⚠️ 这批是**磁盘上存的旧内容**，可能是几天前的。不吭声的话用户会当成当前对话
            //    （用户 2026-09-04 截图：「怎么会加载了很久很久之前的对话」）。
            status = t("这是上次的内容，正在取最新…")
        }
    }

    LaunchedEffect(sessionName, ssh) {
        // ⚠️ **连接没了要说一声，不能默默 return。** 原来这里直接 `?: return`，
        // 于是「正在重连」这个事实在对话页上**一个字都不显示** —— 配上空的 items
        // 就是一整块白。重连是 Workspace 那边自动做的，这里只负责别装死。
        val s = ssh ?: run {
            if (items.isEmpty()) status = t("连接断了，正在重连…")
            return@LaunchedEffect
        }
        // ⚠️ **进过的对话留在内存里**（[app.yxi.agent.ChatMemory]）：重进先把上次的条目原样摆出来、
        // 再只拉增量。原来每次都从头找文件、拉 0.5MB 首屏、再灌 4MB 历史 —— 用户：「退出去再进要等好久」。
        val memKey = app.yxi.agent.ChatMemory.key(hostId, sessionName)
        // ⚠️ offset 为 0 的缓存条目是**中毒**的（见下面 tailStart 那条注释）：拿它续流等于把整个转录从第一个字节重放。
        val remembered = app.yxi.agent.ChatMemory.get(memKey)?.takeIf { it.items.isNotEmpty() && it.offset > 0L }
        // ⚠️⚠️ **数据一落地就把列表钉到底**，不靠下面那个追底 effect。
        //    `rememberLazyListState()` 从第 0 条（= 窗口里**最老**的那条）开始画；追底 effect 是 collectLatest，
        //    活跃会话每 300ms 换一批 items 就把它取消一次 —— 动画永远跑不完，视图就停在很久以前的内容上
        //    （用户 2026-09-05 第四次报：截图停在两天前的对话）。requestScrollToItem 在**下一次布局**生效、
        //    不是动画、取消不了，索引给大了会被夹到最后一条。
        fun pinToEnd() = listState.requestScrollToItem(Int.MAX_VALUE / 2, END_OFFSET)
        if (remembered != null) {
            items = remembered.items; remembered.ctx?.let { ctxUse = it }; settled = true
            pinToEnd()
            status = t("这是上次的内容，正在取最新…")
        }
        // ⚠️ 会话名必须传 —— 转录按 sessionId 找，不按目录找。
        // 只给 cwd 的话，会话里 cd 过一次就再也找不到（用户报的 hexingyang 就是）。
        val file = TranscriptStream.latestFor(s, cwd, sessionName.orEmpty())
        if (file == null) {
            status = t("这个会话里没找到 Claude Code 的转录\n（%s）").format(cwd)
            return@LaunchedEffect
        }
        val entry: app.yxi.agent.ChatMemory.Entry
        /** 这一趟历史要灌多少字节（走缓存那条路是 0 = 没有历史要灌） */
        var expectBytes = 0L
        if (remembered != null && remembered.file == file) {
            entry = remembered
            // ⚠️ **这里要把「正在取最新…」清掉。** 原来只有 else 分支和「收到新行」时才清 ——
            //    会话闲着没有新行的时候，这条提示就**永远挂着**；而它在列表外面、跟列表同一个
            //    Column，挂着就一直占着一行高度。文件对上了就说明已经接上了，没什么可等的。
            status = null
        } else {
            // ⚠️ **转录换文件了，之前摆出来的那批就是别的对话，立刻扔掉。**
            //    留着的话屏幕上顶着一段几天前的对话，而且没有任何提示（用户截图报的就是这个）。
            if (remembered != null) { items = emptyList(); settled = false }
            // 第一次进（或转录文件换了：/clear、换了 uuid）：老路 —— 先画最新一屏，再灌历史
            // ⚠️ **有东西看之前别把提示清掉。** 原来这里先 `status = null` 再去拉 0.58 MB，
            // 那几秒钟正好是「上面没提示、下面没内容」的纯白屏。
            status = if (items.isEmpty()) t("正在载入对话…") else null
            // ⚠️ **先画最新的一屏，再补历史。** `tail -n 800` 从最老那条开始吐、
            // 最新的最后才到，所以完整那次要等 4.17 MB 传完你才看得见最新内容。
            // 这里先要 60 行（0.58 MB，一个来回），立刻有东西看；下面那条完整流回来之后整体替换。
            runCatching { TranscriptStream.head(s, file) }
                .onSuccess { head ->
                    if (head.isNotEmpty()) {
                        // ⚠️ 用 `Incremental` 而不是 `Transcript.parse` —— 后者不给 ctx。
                        val inc0 = Transcript.Incremental()
                        items = withContext(Dispatchers.Default) {
                            inc0.add(head.asSequence()); inc0.snapshot()
                        }
                        pinToEnd()
                        inc0.ctx?.let { ctxUse = it }
                        // 存下来，下次冷启动能立刻画出这几条
                        app.yxi.agent.TranscriptCache.save(ctx, sessionName, cwd, head)
                    }
                    status = null
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (items.isEmpty()) status = t("载入失败：%s").format(it.message ?: "")
                }
            // 历史从最后 400 行的字节起点开始跟随（不是 `tail -n`）：这样读到哪个字节是算得出来的，下次接着读
            // ⚠️ 拿不到就**在这儿等着重试**，别 return：effect 的 key 是 (sessionName, ssh)，连接只是抖一下、
            //    ssh 对象没换的话它不会重跑，用户就永远停在「正在重试…」上（Opus 审查 2026-09-05 指出的）。
            var ts: Pair<Long, Long>? = null
            while (ts == null) {
                ts = TranscriptStream.tailStart(s, file, 400)
                if (ts == null) {
                    if (items.isEmpty()) status = t("连接还没稳，正在重试…")
                    delay(2_000)
                }
            }
            // ⚠️⚠️ **tailStart 拿不到就停，绝不退回 0。** 原来是 `?: 0L`：连接正在重建（切后台回来那一刻）时
            //    exec 回空串 → tailStart 为 null → 从**第 1 个字节**开始 `tail -c +1 -f` —— 把 373MB 的整个转录
            //    从几周前的第一句话开始重放，手机边解析边滚，最后停在项目第一天的对话上
            //    （用户 2026-09-05 第五次报，截图是最早那次装公钥的对话）。而且这个 0 会写进 ChatMemory，
            //    之后每次进来都重放一遍。连接一稳（ssh 换新对象）这个 effect 会自己重跑。
            val start = ts.second
            // ⚠️ 这一趟要灌多少字节 —— 下面拿它当「灌完了没」的**确定判据**（见 caughtUp）
            expectBytes = (ts.first - ts.second).coerceAtLeast(0L)
            entry = app.yxi.agent.ChatMemory.Entry(file, Transcript.Incremental()).also {
                it.offset = start
                app.yxi.agent.ChatMemory.put(memKey, it)
            }
        }
        val fresh = entry !== remembered

        // 攒一批再解析：tail 一上来就吐几百行，逐行重解会把 UI 卡住。
        // ⚠️ **节流必须有「尾随刷新」**（TROUBLESHOOTING #86）：收行的只管往 buf 里塞，另一个协程定时刷。
        // ⚠️ **只喂新行**，不从头重解（#86）。
        // ⚠️ 第一次进：历史悄悄在后台灌，界面停在 head（最新一屏），等 inc **追上 head 的最新那条**（key 对上）
        //    才交出完整列表，不给用户看「从旧滚到新」。重进：inc 里已经是完整的，来一批换一批。
        val headLastKey = if (fresh) items.lastOrNull()?.key else null
        // ⚠️⚠️ **head 拉空了也不能提前放行**（原来 `|| headLastKey == null` 会让 caughtUp 一开始就为 true）：
        //    那样每 300ms 一批就整体换一次 items + 追底一次 —— 用户看到的是历史像放电影一样从旧滚到新，
        //    顶栏的上下文数字一路往上爬（1.1.10 实拍：294K → 915K 每帧一变）。宁可多等两秒空着，也不放电影。
        var caughtUp = !fresh
        // 这一趟已经吃进去多少字节 —— 跟 expectBytes 比，就知道历史灌完没有
        var eaten = 0L
        var idleTicks = 0
        // 完整列表已经上过屏（钉过一次底）
        var promoted = !fresh
        val inc = entry.inc
        val pending = ArrayList<String>()
        var pendingBytes = 0L
        // ⚠️ 收行和刷新是两个协程。`toList()` 和 `clear()` 之间来一行就会**丢**，所以都在同一把锁里。
        val lock = Any()
        launch {
            while (true) {
                delay(300)
                val (batch, bytes) = synchronized(lock) {
                    if (pending.isEmpty()) emptyList<String>() to 0L
                    else (pending.toList() to pendingBytes).also { pending.clear(); pendingBytes = 0L }
                }
                if (batch.isEmpty()) {
                    // ⚠️⚠️ **一个空转周期 ≠ 历史灌完。** 原来就是这么判的，是这个 bug 的主因：
                    //    收行的 collect 和这个 300ms ticker **跑在同一个主线程上**，而每批刷新都要
                    //    整体换 items（几百条重组重布局）+ 追底 —— 主线程一被占住，行就塞不进 pending，
                    //    ticker 反倒先被派发 → pending 空 → 被当成「灌完了」。
                    //    后果很重：一份**起点在 400 行前、结尾停在半路**的残缺快照被当成正式内容摆上屏，
                    //    还写进 ChatMemory（下面那行）—— 于是退回看板再进，看到的还是它，
                    //    顶栏的模型和上下文也跟着变成 400 行前那条消息的（用户 2026-09-05 录到的就是这个）。
                    //    现在只在**连着四拍都空**（≈1.2 秒，远超一次刷新的耗时）时才当兜底用。
                    //    真正的判据是下面的 eaten >= expectBytes：**数字节，不猜时序。**
                    idleTicks++
                    // ⚠️ **知道要灌多少字节时，绝不按「没动静」放行。** 手机网络上 4MB 历史是一阵一阵来的，
                    //    间隔随便就超过一秒多；按空转放行等于把半截当完整（#261 ①）—— 之后每批再换一次 items，
                    //    就是「一闪一闪 + 上下文数字乱跳」。只有 tailStart 拿不到大小（expectBytes == 0）时才退回
                    //    空转兜底，而且要连着 10 拍（3 秒）。
                    if (!caughtUp && expectBytes == 0L && idleTicks >= 10) {
                        val snap = withContext(Dispatchers.Default) { inc.snapshot() }
                        if (snap.isNotEmpty()) { items = snap; caughtUp = true; inc.ctx?.let { ctxUse = it }; promoted = true; pinToEnd() }
                    }
                    if (items.isNotEmpty() && caughtUp) settled = true
                    continue
                }
                idleTicks = 0
                val snap = withContext(Dispatchers.Default) {
                    inc.add(batch.asSequence()); inc.snapshot()
                }
                entry.offset += bytes
                eaten += bytes
                // ⚠️ 两条都算「追上了」：① 解析结果里出现了 head 的最后一条（最准）
                //    ② **字节数够了** —— tailStart 已经告诉我们这趟要灌多少，数够就是真灌完了，
                //       不用去猜「多久没来行 = 完了」。①失效（比如 head 末尾是排队条目、key 不稳）时靠②兜。
                if (!caughtUp && headLastKey != null && snap.any { it.key == headLastKey }) caughtUp = true
                if (!caughtUp && expectBytes > 0 && eaten >= expectBytes) caughtUp = true
                if (caughtUp) {
                    val firstFull = !promoted
                    items = snap; inc.ctx?.let { ctxUse = it }; status = null
                    entry.items = snap; entry.ctx = inc.ctx
                    // 60 行首屏 → 400 行完整列表那一下：换完立刻钉到底（锚点保住了也要钉，首屏最后一条可能不在屏底）
                    if (firstFull) { promoted = true; pinToEnd() }
                }
            }
        }
        // ⚠️ 连接半路断了，`openExecStream` 会抛「session is down」——从这个 LaunchedEffect
        // 里逸出就是**闪退**（#125）。catching 兜住（取消照抛，切页面照常），断了让看门狗重连。
        app.yxi.ssh.catching {
            TranscriptStream.streamFrom(s, file, entry.offset).collect { line ->
                // ⚠️⚠️ **心跳空行不算字节。** `SshSession.follow` 每 20 秒往流里 `printf '\n'` 一次防通道被掐，
                //    那一行**不在文件里**。原来照样 +1，在对话页停几分钟 offset 就比文件大小多出十几个字节；
                //    退回看板再进，`tail -c +N -f` 的 N 落在文件末尾之外 —— GNU tail 把它当成「文件被截断」，
                //    **从第 0 字节把整份转录重放一遍**（logcat：`tail: …jsonl: file truncated`；
                //    服务器上 30 字节的文件实测：起点多 3 字节、文件一长就整文件重吐）。
                //    这就是「退回看板再进 / 从后台切回，看到几周前的对话」的真根因（#273）——
                //    会话越闲越容易中（文件不长，多出的字节盖不掉）。转录是 JSONL，真正的空行不存在。
                if (line.isEmpty()) return@collect
                // ⚠️ 字节数按 UTF-8 算再加一个换行 —— 这是下次 `tail -c +N` 的起点，算错就会漏行或重行
                synchronized(lock) { pending += line; pendingBytes += line.toByteArray().size + 1 }
            }
        }
    }
    // ⚠️ 「此刻在等你选」这件事**只有屏幕知道** —— tool_use 要等工具跑完才落进转录。
    // 所以历史读转录、待答抓屏幕，两条路各司其职（见 Prompt 的类注释）。
    LaunchedEffect(ssh, sessionName) {
        val s = ssh ?: return@LaunchedEffect
        fun apply(p: Pending?, l: app.yxi.agent.Live) {
            pending = p
            live = l
            synced = true
            // 动作生效了（指纹变了 / 面板没了）就解锁
            if (awaitingFp != null && p?.fingerprint != awaitingFp) { awaitingFp = null; busy = false }
        }
        // ⚠️ **首选「变了才推」，不是轮询。** 轮询等于每次都要一个 SSH 往返，
        // 而抓屏本身是 0ms —— 延迟几乎全花在往返和轮询间隔上（用户报的「点一下等十秒」）。
        // 服务器侧自己比对，没变不过网；实测变化推到手机 **约 200ms**。
        app.yxi.ssh.catching {
            SessionProbe.watchScreen(s, sessionName).collect { (p, l) -> apply(p, l) }
        }
        // 推流断了（会话没了 / 通道被掐）→ 回落轮询，功能不受影响，只是慢一点
        while (true) {
            runCatching { SessionProbe.snapshot(s, sessionName) }.onSuccess { (p, l) ->
                pending = p
                live = l
                synced = true
                if (awaitingFp != null && p?.fingerprint != awaitingFp) { awaitingFp = null; busy = false }
            }
            delay(if (live.busy || pending != null) 700 else 2_500)
        }
    }
    // ⚠️ **`settled` 也要当键。** 只用 items.size 的话，最后一次定位发生在
    // 「历史还在灌、布局还在变」的时候，滚到一半列表又长高了 ——
    // 结果永远差最后一屏（最后一条被切掉，↓ 按钮赖着不走）。
    // 加上 settled：灌完那一刻**再定位一次**，这次布局是稳的。
    LaunchedEffect(listState) {
        // ⚠️ **攒一下再追（debounce）。** 转录是每 300ms 刷一批，一批里常常还分几次到，
        //    而队列里的消息（排队中的、别的 agent 注入的）会在末尾一冒一消 —— 每次变动都追一下，
        //    就是用户说的「一闪一闪一跳一跳」。等它安静 110ms 再追，中间那些过渡态就不用管了（#228）。
        // ⚠️⚠️ **盯的是 items 这个列表本身，不是 items.size。**
        //    行数不变而**内容变了**的情况真实存在：三条工具卡合成一组（size 不变、
        //    屏幕上少两行、高度掉几百像素）、排队消息出队变成真消息（out +1 / queued -1，
        //    size 完全不变）。只盯 size 的话这些时候**一次都不追**，位置就留在错的地方。
        // ⚠️ **collect 不是 collectLatest**：活跃会话每 300ms 换一批 items，collectLatest 会把上一次
        //    还没滚完的动画取消掉再等 110ms 重来 —— 在出字的会话里它**永远滚不到底**，
        //    这就是「停在很久以前的内容」的另一半根因（进场那一半靠 pinToEnd）。
        //    顺序执行的话每次最多 30 帧就完，攒几批也就多滚几下，不会跑丢。
        snapshotFlow { items to settled }.collect { (list, st) ->
            val n = list.size
            if (n == 0) return@collect
            if (!st) { runCatching { listState.scrollToEnd() }; return@collect }   // 灌历史：立刻贴底
            if (!stick) return@collect
            delay(110)
            runCatching { listState.scrollToEnd(smooth = true) }
        }
    }

    // ⚠️ 光晕铺**整页**、画在最底下。原来它只在列表那个 Box 里，到列表底边就截止，
    // 快捷语和输入框在外面、是平底 —— 待机聚光最亮的地方正好压在那条边上，
    // 用户划了条红线指着那道色差。参考款的聚光是在输入框**背后**的，本来就该铺到底。
    // ⚠️ 光晕现在由 Workspace 画在**整个工作区**底下（用户：「顶部的终端/对话/文件/实验室和 Yxi 那部分也要带上」），
    //    这里只把三个状态报上去；离开对话页时报一次全 false，别让光留在别的模式里。
    val glowBusy = live.busy
    val glowWait = pending != null
    val glowStream = live.busy && items.lastOrNull() is ChatItem.AssistantText
    LaunchedEffect(glowBusy, glowWait, glowStream) { onGlow(glowBusy, glowWait, glowStream) }
    DisposableEffect(Unit) { onDispose { onGlow(false, false, false) } }
    Box(modifier.fillMaxSize()) {
    // ⚠️ **别在这儿留页眉的位置。** 留了就是永久空一条：页眉收起时那块露的是底色，不是正文
    //    （用户：「上导航栏收起的地方却是有空白，X 的就不会」）。页眉的高度要进**列表的 contentPadding**——
    //    那是内容的一部分，手一划就滚上去了，栏退场时底下露出来的正好是字。
    val headerDp = with(LocalDensity.current) { headerPx.toDp() }
    // ⚠️ **列表外面这几行要自己让开页眉。** 页眉是**悬浮**在正文上的（高度只进了列表的
    //    `contentPadding`），而状态提示 / 状态带 / 「正在做」这三行不在列表里 —— 谁排第一，
    //    谁就得自己加这段 `top`，否则整行钻到页眉底下（实测跑到状态栏里去了，等于没显示）。
    //    ⚠️ 不能给 Column 整个加：那样三行都没有的时候会**空出永久的一条**（#228 那个白条）。
    val statsShown = showStats && (ctxUse != null || todayUse != null)
    // 顶上那几行（状态提示 / 状态条 / 正在做）现在**悬浮**在列表上方、跟页眉一起平移（见 [floatTop]），
    // 它们的高度要像页眉一样进列表的 contentPadding —— 这里量出来
    var stripH by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        // 标题和路径由 Workspace 的头部管，这里只在出问题时说一句
        // ⚠️ 这几行（状态提示 / 状态条）**要跟页眉一起收起来**。页眉在 Workspace 里按 barsFrac 上移，
        //    而它们在这个 Column 里、不在页眉里 —— 原来纹丝不动，用户 2026-09-05 录到的就是：
        //    「闪电标志（状态条）不关掉，往上滑页眉收了、这条还杵在顶上」。
        //    ⚠️ 不能只做 graphicsLayer 平移/淡出：那不改布局，列表不会跟着上来，顶上会留一条空带。
        //    所以用 [collapseBy]：**布局高度**按 barsFrac 缩到 0，内容往上推出去。
        // ⚠️⚠️ 这个 Column **不占布局高度**（[floatTop]），只画在列表上面；收起 = 平移 + 淡出。
        //    1.1.10 用的是「布局高度按 barsFrac 缩到 0」（collapseBy）—— 展开时列表被它往下推、
        //    展开完又跳回来，就是用户慢动作录到的「展开的同时内容还在下移」。页眉本来就是悬浮的，
        //    这几行跟页眉同一种做法才对：**收放只动画面，不动布局**。
        val hasTop = status != null || statsShown || doingNowText(items) != null
        Column(
            Modifier.fillMaxWidth().floatTop(barsFrac, headerPx) { h -> if (h != stripH) stripH = h }
                .padding(top = if (hasTop) headerDp else 0.dp),
        ) {
        status?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(18.dp, 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // 右上角那条细字：这个会话占了多少上下文 · 这台机器今天烧了多少。
        //
        // ⚠️ **为什么不是把 `/usage` 的输出搬过来**：`/usage` 只画在 TUI 上，
        // 转录里一个字都没有（用户自己也发现了「只有终端里面有」）。
        // 而这两个数是**现成的**：上下文来自最后一条 assistant 消息的 usage（顺着转录解出来），
        // 今日用量来自那台机器上的 ccusage。都不用动用户的会话。
        //
        // ⚠️ **拿不到就整行不画**，不显示 0、不显示「未知」——
        // 额度和花费显示一个假的比不显示危险得多，你会照着它决定今天开不开大活。
        if (showStats && (ctxUse != null || todayUse != null)) {
            androidx.compose.foundation.layout.FlowRow(
                // ⚠️ **放不下要换行，不能裁。** 这行有五格（模式 / 模型 / 思考强度 / 上下文 / 今日），
                // 窄屏一定放不下。原来是横滑，结果默认停在最左边、右边那两格数字**看着就是被切掉的**
                // —— 而右边那两格恰恰是要看的（上下文、今日花了多少）。换行了就一个都不少。
                Modifier.fillMaxWidth()
                    .padding(14.dp, 2.dp, 14.dp, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 模式快切：模型 / 思考强度 / ultracode，点开面板一点就切、可叠加
                StatChip(
                    t("模式"), MaterialTheme.colorScheme.primary, icon = Ico.Bolt,
                    onClick = if (ssh != null) ({ showModes = true }) else null,
                )
                // 模型名单独一格，**可点** —— 点开就是 `/model` 那个选单
                // ⚠️ 点开的是**本地选单**（名单来自 settings.json 的 availableModels，按主机缓存一次）——
                //    零往返、立刻弹。原来是点一下就去 TUI 里开 /model 选单再抓屏 4 次，等 1~4 秒
                //    （用户 2026-09-05：「切换模型选单延迟很高」）。慢的那条留作「仅本会话」的备选。
                ctxUse?.model?.takeIf { it.isNotBlank() }?.let { m ->
                    StatChip(
                        if (modelBusy) t("切换中…") else m.removePrefix("claude-"),
                        MaterialTheme.colorScheme.primary, mono = true,
                        onClick = if (ssh != null && !modelBusy) ({
                            val s0 = ssh
                            scope.launch {
                                fastModels = app.yxi.ssh.catching {
                                    app.yxi.agent.Model.available(s0, hostId)
                                }.getOrNull() ?: app.yxi.agent.Model.Available(listOf("default", "opus", "sonnet", "haiku"), "")
                            }
                        }) else null,
                    )
                }
                // 这个会话在什么**模式**：思考强度（max/high/mid）+ 计划模式。跟模型一样是**按会话**的。
                // ⚠️ 数据顺着转录一起解出来（assistant 行顶层的 `effort` + `{"type":"mode"}` 行），不额外跑服务器。
                ctxUse?.let { cu ->
                    val bits = buildList {
                        when (cu.effort) {
                            "max" -> add(t("最大思考")); "high" -> add(t("高强度")); "mid" -> add(t("中等"))
                            else -> if (cu.effort.isNotBlank()) add(cu.effort)
                        }
                        if (cu.mode == "plan") add(t("计划模式"))
                        // ponytail 强度（lite/full/ultra）——读得到才显示
                        if (cu.ponytail.isNotBlank()) add("ponytail " + cu.ponytail)
                    }
                    // 能点了：点开选思考强度（`/effort`）。按「蓝 = 能点」的规则改成主色。
                    if (bits.isNotEmpty()) StatChip(
                        bits.joinToString(" · "),
                        if (ssh != null) MaterialTheme.colorScheme.primary else Muted,
                        onClick = if (ssh != null) ({ showEffort = true }) else null,
                    )
                }
                // 上下文单独一格、可点：快满了染琥珀，点一下发 /compact（手机上懒得敲那几个字母）
                ctxUse?.let { cu ->
                    // ponytail: 固定阈值 15 万 —— 逼近常见的 20 万自动压缩线；模型窗口不同就改这个数
                    // ⚠️ 模型名里**明写**了 `[1m]` 才敢按 100 万算 —— 那是显式的窗口标记，不是从模型系列猜的
                    //    （猜窗口会显示出假百分比，见 [Transcript.Ctx] 的注释）。其余按 20 万那条常见的自动压缩线。
                    val tight = cu.tokens >= if (cu.model.contains("[1m]")) 750_000 else 150_000
                    StatChip(
                        t("上下文 %s").format(tokenText(cu.tokens)),
                        if (tight) Amber else Copper, mono = true,   // 能点（发 /compact）→ 主色；吃紧了才转琥珀
                        onClick = if (ssh != null) ({
                            val s0 = ssh
                            scope.launch { runCatching { SessionProbe.send(s0, sessionName, "/compact") } }
                            android.widget.Toast.makeText(ctx, t("已发 /compact —— 压一下上下文"),
                                android.widget.Toast.LENGTH_SHORT).show()
                        }) else null,
                    )
                }
                todayUse?.let { StatChip(t("今日 %s · %s").format(it.tokenText, it.costText), Muted, mono = true) }
            }
        }

        // Claude 此刻在做计划里的哪一步 —— 取最近一次 TodoWrite 里 in_progress 那条，顶栏回显一眼看清进度
        val doingNow = remember(items) { doingNowText(items) }
        doingNow?.let {
            Text(
                t("▶ 正在做 · %s").format(it),
                Modifier.fillMaxWidth()
                    .padding(18.dp, 0.dp, 18.dp, 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        }   // floatTop 的那个 Column

        // ⚠️ 库把链接点击交给 `LocalUriHandler`，所以在这儿换一个自己的。
        // 认出 [Linkify.SCHEME] 就转成「跳去看这个文件」，其余的（http 之类）
        // 原样交回系统 —— **别把外链也吞掉**，那就没法点开真正的网址了。
        val sysUri = androidx.compose.ui.platform.LocalUriHandler.current
        val uri = remember(sysUri, onOpenPath) {
            object : androidx.compose.ui.platform.UriHandler {
                override fun openUri(uri: String) {
                    if (uri.startsWith(app.yxi.agent.Linkify.SCHEME))
                        onOpenPath(uri.removePrefix(app.yxi.agent.Linkify.SCHEME))
                    else runCatching { sysUri.openUri(uri) }
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalUriHandler provides uri,
        ) {
        // ⚠️ detectTapGestures 只认**没被孩子消费**的点：链接 / 按钮 / 卡片各有自己的点击，点它们不切栏；
        //    拖动也不算（滚动消费了位移）。键盘开着时不切 —— 正打字呢，栏本来就固定在。
        Box(Modifier.weight(1f).nestedScroll(barsConn).pointerInput(imeOpen) {
            detectTapGestures { if (!imeOpen) barsTap++ }
        }) {
            // 连着的同名工具卡合成一张（用户：「满屏都是 bash」），点开才铺开
            val rows = remember(items) { groupToolRuns(items) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // 顶部留白 = 悬浮在上面那几行的高度（它们已经含了页眉的高度）；一行都没有就只留页眉
                contentPadding = PaddingValues(
                    16.dp,
                    (if (hasTop) with(LocalDensity.current) { stripH.toDp() } else headerDp) + 6.dp,
                    16.dp, composerPad + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(rows.size, key = { rows[it].key }) { i ->
                    val row = rows[i]
                    if (row is ChatRow.Group) {
                        ToolGroupCard(row.calls, open = row.key in openGroups) {
                            if (row.key in openGroups) openGroups.remove(row.key) else openGroups.add(row.key)
                        }
                        return@items
                    }
                    val item = (row as ChatRow.One).item
                    // 只给「加载完之后才出现」的条目做进入动画 —— 初始那几百条一起滑入是灾难
                    // 只有「加载完之后新来的」+「这条从没动画过」+「就在末尾附近」才动画：
                    // 老内容重新组合一律不动（#227），中间插进来的也不动（会把整屏顶得乱跳）。
                    val fresh = seenKeys != null && item.key !in seenKeys!! && i >= rows.size - 3 &&
                        animated.add(item.key)
                    EnterUp(animate = fresh && motionOn) {
                    Item(
                        item,
                        ssh = ssh,
                        // 点缩略图 = 全屏看那张图。复用发送前的那个预览器，
                        // 只是这次图在远端 —— [preview] 认 remotePath，本地 uri 留空。
                        onOpenRef = { r ->
                            if (r.isImage) preview = app.yxi.agent.Attachments.Staged(
                                label = r.label, remotePath = r.path, isImage = true,
                            )
                        },
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
                                        android.widget.Toast.makeText(ctx, t("收不回来：") + it.message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                    )
                    }   // EnterUp
                }
            }

            // ⚠️ **只在没在底部时才出现。** 一直挂着的话它就是块永久的遮挡 ——
            // 而绝大多数时候你本来就在底部（新消息会自动跟着走），那时它毫无用处。
            // derivedStateOf：不加的话每滚一帧都要重组整个 ChatScreen。
            // ⚠️ **粘着（stick）时一律不出现** —— 这才是「点了 ↓ 一直闪」的根（#226）：
            //    点 ↓ 之后 stick=true，会话又在出字，每 300ms 来一批 → 列表长高一点 → 有那么一两帧「不在底部」
            //    → 按钮淡入 → 跟随滚到底 → 淡出 …… 一秒闪两下。而粘着时它本来就没用（马上自己就到底了）。
            //    想要它回来：手指往上一拖，stick 就关了。
            val away by remember {
                derivedStateOf { items.isNotEmpty() && !stick && !listState.atBottom }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = away,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = composerPad + 12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable {
                        // ⚠️ 瞬移不做动画：几百条的列表上 animateScrollToItem 要滚好几秒，
                        // 而这个按钮的意思就是「立刻到底」
                        // ⚠️ 第二个参数是**在那一条内部再往下滚多少像素**。
                        // 不给的话是把最后一条的**顶部**对齐视口顶部 ——
                        // 那条要是比一屏长，尾巴还在屏幕外，用户会觉得按了没用。
                        // 给一个大数，Compose 会夹到列表真正的末尾。
                        // 点一下就粘住 —— 之后新内容自动跟到底，不用再点第二次
                        stick = true
                        scope.launch { listState.scrollToEnd() }
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
                // 上划收起、下滑展开（学 X）。键盘开着时永远在 —— 正打字呢不能把输入框收走
        // ⚠️ 不用 AnimatedVisibility：Box 里套着外层 Column 的作用域，Kotlin 会挑中 ColumnScope 那个重载然后报
        //    「不能用隐式接收者调用」；而且它收起时会把输入框卸掉、焦点和光标全丢。改成整块平移 + 淡出，组合树不动。
        // ⚠️ ×1.7：X 的下栏比上栏退得快一点，用户专门指出来了。键盘开着时永远在（正打字呢）。
        val hideFrac = if (imeOpen) 0f else (barsFrac * 1.7f).coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { translationY = size.height * hideFrac; alpha = 1f - hideFrac },
        ) {
        // 学 Gemini：整块**悬浮**在对话上面，底下的字从一层淡淡的渐变里透出来；高度报给列表当底部留白
        val fadeTo = MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { composerH = it.height }
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to fadeTo, 1f to fadeTo)),
        ) {
        // ⚠️ **终端里那行状态词也要在对话里看得见**（用户 2026-09-04：「Mustering…（32s · token）这些要在对话里显示」）。
        //    以前只有会话切换卡上有（[Switcher]），对话页里没有 —— 而对话页恰恰是盯着它干活的地方。
        //    这行字只有屏幕上有（转录里没有），所以来源是 `tmux capture-pane` 解析出来的 [app.yxi.agent.Live.status]。
        //    跑完之后 `doneFor` 会顶上来显示「刚跑完 · 13s」，几秒后自然消失。
        val statusLine = when {
            live.busy && !live.status.isNullOrBlank() -> "✽ " + live.status to app.yxi.ui.theme.Teal
            !live.doneFor.isNullOrBlank() -> t("刚跑完 · %s").format(live.doneFor) to app.yxi.ui.theme.Copper
            else -> null
        }
        statusLine?.let { (text, color) ->
            Text(
                text,
                Modifier.fillMaxWidth().padding(22.dp, 0.dp, 22.dp, 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = color,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (staged.isNotEmpty() || queue.isNotEmpty()) {
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
                                a.display,
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
                // 还在传 / 传失败的：名字 + 进度环（失败变红字，点名字重试）+ ✕（取消这一个）
                queue.forEach { up ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (up.error == null) androidx.compose.material3.CircularProgressIndicator(
                                progress = { up.progress }, modifier = Modifier.padding(start = 10.dp).size(14.dp), strokeWidth = 2.dp,
                            )
                            Text(
                                if (up.error == null) up.name.take(14) else t("%s · 失败，点我重试").format(up.name.take(10)),
                                Modifier.clickable(enabled = up.error != null) { startUpload(up) }.padding(8.dp, 6.dp, 6.dp, 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (up.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 1,
                            )
                            Text(
                                "✕",
                                Modifier.clickable {
                                    up.cancelled = true; up.job?.cancel(); queue.remove(up)
                                }.padding(6.dp, 6.dp, 12.dp, 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // 常用语 chip：没打字时才露出来，点一下填进草稿，省掉手机打字
        if (draft.isBlank()) {
            SnippetChips(onPick = { draft = it },
                modifier = Modifier.fillMaxWidth().padding(14.dp, 0.dp, 14.dp, 8.dp))
        }

        // 输入框：打进那个活着的会话，不调任何 API
        //
        // ⚠️ **整条是一个胶囊，不是四个圆按钮排排站。** 原来是 📎 🎤 输入框 ↑ 四块分开，
        // 每块之间 10dp 空隙，视觉上是「一排控件」而不是「一个输入区」；
        // 而且两个 emoji 图标跟界面里其余的线性图标不是一路。
        // 现在按 参考款那种做法收成一条：+ · 文字 · 🎤 · 发送，边界一条，里面才分格。
        // ⚠️ 底色不是死的：跟页面光晕**同一套色相**淡淡地流过去（用户：「输入框也要是渐变背景」）。
        // 光晕忙的时候在页面顶部，输入框离得远，这层自己的渐变让它不至于是一块平灰。
        // ⚠️ 输入框**有上限**：打一大段话原来会把整屏占满，前面的对话一行都看不见（用户截图）。
        //    最多 7 行，超了在框里自己滚。圆角用 28dp 不用 Pill：单行还是胶囊，多行不会变成一个巨大的椭圆。
        // 玻璃壳：光晕 / 呼吸 / 流动都在 [GlassPill] 里（用户要的立体玻璃；也顺手修掉透明面 + elevation 的黑影，#225）
        GlassPill(
            busy = live.busy, waiting = pending != null,
            modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 14.dp).heightIn(min = 60.dp),
        ) {
            val plusBtn: @Composable () -> Unit = {
                if (sftp != null) {
                    FlatIcon(Glyph.Plus, t("加附件")) { pick.launch("*/*") }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
            // 用户在设置里选的那套（默认自动）。⚠️ 选了「手机上算」但没下模型 / 选了「服务器上算」但那台没装，
            //    就不该假装能用 —— 点的时候直说，别默默换一套（用户根本不知道自己在用哪个）。
            val eng = app.yxi.agent.AsrModel.engine
            val useDevice = onDevice && eng != app.yxi.agent.AsrModel.Engine.Server &&
                eng != app.yxi.agent.AsrModel.Engine.System
            val useServer = serverAsr && !useDevice && eng != app.yxi.agent.AsrModel.Engine.Device &&
                eng != app.yxi.agent.AsrModel.Engine.System
            val micBtn: @Composable () -> Unit = {
                // 语音：**服务器上有 `yxi-asr` 就按住说话**（识别在你自己的机器上跑，
                // 准得多、也不经过任何云 API）；没装就退回系统那个识别界面。
                if (useDevice || useServer) MicTap(
                    recording = recording,
                    busy = asrBusy,
                    onStart = {
                        holdHintAt = System.currentTimeMillis()
                        if (!hasMic) { askMic.launch(android.Manifest.permission.RECORD_AUDIO); false }
                        else recorder.start().let { why ->
                            if (why != null) {
                                android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                                false
                            } else {
                                // ⚠️ **这一行以前压根没有。** `recording` 一直是 false —— 麦克风变红、
                                //    输入框变波形这些「正在录」的样子全都不生效（写的时候只传了状态，忘了置位）。
                                recording = true
                                true
                            }
                        }
                    },
                    onStop = {
                        recording = false
                        val pcm = recorder.stop()
                        // ⚠️ 太短的 [Recorder.stop] 已经丢掉了 —— 不当错误报，但**点一下**（不到 300ms）要提示
                        //    「要按住」：这是从系统识别切到按住说话之后最常见的困惑
                        if (pcm == null && System.currentTimeMillis() - holdHintAt < 300) {
                            android.widget.Toast.makeText(ctx, t("太短了，没录到东西"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (pcm != null) scope.launch {
                            asrBusy = true
                            val said = if (useDevice) {
                                // ① 手机上算 —— 不联网、不依赖服务器，最快也最省事
                                app.yxi.agent.OnDeviceAsr.transcribe(ctx, pcm) { why ->
                                    android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else {
                                // ② 退回服务端识别：拼个 wav 传上去
                                val s0 = ssh
                                if (s0 == null) {
                                    android.widget.Toast.makeText(ctx, t("还没连上"), android.widget.Toast.LENGTH_SHORT).show()
                                    null
                                } else {
                                    val f = Recorder.toWav(ctx, pcm)
                                    val r = app.yxi.agent.Voice.transcribe(s0, f) { why ->
                                        android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    runCatching { f.delete() }
                                    r
                                }
                            }
                            // ⚠️ **只填进输入框，绝不直接发** —— 识别错一个字，
                            //    在服务器上就是另一条命令。三条路都守这一条。
                            if (!said.isNullOrBlank()) draft = (draft.trimEnd() + " " + said).trim()
                            asrBusy = false
                        }
                    },
                ) else FlatIcon(Glyph.Mic, t("语音输入")) {
                    when (eng) {
                        app.yxi.agent.AsrModel.Engine.Device -> if (!onDevice) {
                            android.widget.Toast.makeText(ctx, t("你选了「手机上算」，但模型还没下 —— 去「我的 · 语音识别」下一个"), android.widget.Toast.LENGTH_LONG).show()
                            return@FlatIcon
                        }
                        app.yxi.agent.AsrModel.Engine.Server -> if (!serverAsr) {
                            android.widget.Toast.makeText(ctx, t("你选了「服务器上算」，但这台机器上没有 yxi-asr"), android.widget.Toast.LENGTH_LONG).show()
                            return@FlatIcon
                        }
                        else -> Unit
                    }
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
                }
                // ⚠️ 还有在传的先别发：发了它们就不在这条消息里了，用户以为丢了
            }
            val sendBtn: @Composable () -> Unit = {
                val busyUp = queue.count { it.error == null }
                val hasWord = draft.isNotBlank() || staged.isNotEmpty()
                val canSend = hasWord && busyUp == 0
                Surface(
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    // ⚠️ **变灰的按钮也要点得动。** 原来 `enabled = canSend`：还有附件在传的时候，
                    //    点发送**什么都不发生、也不说为什么** —— 这正是 STYLE.md 里那条
                    //    「一切失败都要说出来」禁止的。现在点了会说清在等什么。
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable {
                        if (!canSend) {
                            android.widget.Toast.makeText(
                                ctx,
                                when {
                                    busyUp > 0 -> t("还有 %d 个附件在传，传完再发 —— 现在发它们就不在这条消息里了").format(busyUp)
                                    else -> t("先写点什么，或者加个附件")
                                },
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            return@clickable
                        }
                        // 附件的路径映射贴在正文前面 —— Claude 自己去读那些文件
                        val t = (app.yxi.agent.Attachments.header(staged) + draft.trim()).trim()
                        // ⚠️ **先把本地那份种进缩略图缓存，再清 staged。** 图就在这台手机上，
                        // 发出去的气泡第一帧就该是图 —— 不是先一条路径、等 SFTP 拉回来再变。
                        Thumbs.seed(ctx, staged)
                        draft = ""; staged = emptyList()
                        // ⚠️ 立刻清盘上那份 —— 只清内存的话，防抖那 600ms 里退出去，
                        // 下次进来发过的话又冒出来一遍
                        Drafts.set(ctx, hostId, sessionName, "")
                        // ⚠️ **不能用界面的 scope** —— 点完立刻切走会把它取消，那句话就没了（见 [Sender]）
                        Sender.send(ctx, ssh, hostId, sessionName, t) { msg ->
                            draft = Drafts.get(ctx, hostId, sessionName)   // 话还回来了，回填输入框
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
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
            // 学 Gemini 打了很多字的样子：一行时四件套一排；多行时文字在上占满、按钮沉到下面一排
            // ⚠️ **同一个输入框实例在两种排版间搬家，不是销毁重建。** 0.9.92 是 if/else 两支各放一个 BasicTextField，
            //    行数一过 1↔2 就换实例，输入法的组合状态还挂在死掉的那个上 —— 用户：「输了文字删不掉了」（#224）。
            //    movableContentOf 让它带着内部状态（选区、组合）整个搬过去。多行排版一旦进入就粘住到清空，免得在边界来回跳。
            //    ⚠️ **搬家会把焦点弄丢** —— `movableContentOf` 保住的是**状态**，不是焦点：
            //    节点从 Row 里摘下来挂进 Column，焦点跟着断，**键盘当场收起来**。
            //    表现正是用户说的「打着字键盘莫名其妙自己收起，概率不大」——
            //    概率不大是因为 [multi] 是**粘住的**：一段草稿只在第一次换行那一下掉一次。
            //    所以搬完要把焦点要回来（原来有焦点才要，别在没打字的时候强行弹键盘）。
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            var focused by remember { mutableStateOf(false) }
            val field = remember {
                movableContentOf<String> { d ->
                    BasicTextFieldRow(d, focus, onFocus = { focused = it }, onLines = { lines = it }) { draft = it }
                }
            }
            LaunchedEffect(lines, draft) { multi = if (draft.isBlank()) false else (multi || lines > 1) }
            LaunchedEffect(multi) {
                if (!focused) return@LaunchedEffect
                // 等这一帧的重组落地，节点重新挂上去才要得到焦点
                withFrameNanos {}
                runCatching { focus.requestFocus() }
            }
            // 在录音：整条输入框换成波形 —— 这时候不需要键盘也不需要按钮
            // ⚠️ 只有**手机上算**这条路能边说边出字：服务端那条是「录完拼 wav 传上去」，
            //    流式要重做传输协议。走服务端时不显示临时结果 —— 不假装它在识别。
            if (recording) RecordingBar(recorder, live = useDevice) {
                val pcm = recorder.stop()
                if (pcm != null) scope.launch {
                    asrBusy = true
                    val said = if (useDevice) {
                        app.yxi.agent.OnDeviceAsr.transcribe(ctx, pcm) { why ->
                            android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val s0 = ssh
                        if (s0 == null) { android.widget.Toast.makeText(ctx, t("还没连上"), android.widget.Toast.LENGTH_SHORT).show(); null }
                        else {
                            val f = Recorder.toWav(ctx, pcm)
                            val r = app.yxi.agent.Voice.transcribe(s0, f) { why ->
                                android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                            }
                            runCatching { f.delete() }; r
                        }
                    }
                    if (!said.isNullOrBlank()) draft = (draft.trimEnd() + " " + said).trim()
                    asrBusy = false
                }
                recording = false
            } else if (!multi) Row(Modifier.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                plusBtn()
                Box(Modifier.weight(1f)) { field(draft) }
                micBtn(); sendBtn()
            } else Column(Modifier.padding(6.dp, 6.dp, 6.dp, 4.dp)) {
                Box(Modifier.fillMaxWidth()) { field(draft) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    plusBtn(); Spacer(Modifier.weight(1f)); micBtn(); sendBtn()
                }
            }

        }
        }
        }
        }
        }   // CompositionLocalProvider(LocalUriHandler)
    }
    }   // Box：光晕 + 整页

    // `/model` 选单。
    //
    // ⚠️ **点一下 = 只换这个会话**，不碰账号默认。
    // 实测：直接送数字 = 「saved as your default for new sessions」——
    // 在手机上顺手一点就把以后每个新会话的模型都改了，那是事后想不起来为什么的坑。
    // 想改默认得单独点那一行，文案里写明白。
    if (showModes) ModeSheet(
        onPick = { cmd ->
            val s0 = ssh
            if (s0 != null) {
                scope.launch {
                    // ⚠️ `/model X` 在有对话的会话里会弹「Switch model?」确认框，不按掉的话
                    //    用户下一条消息会被它吃掉（探针实测）。所以走 switchFast，它会补那个 Enter。
                    val alias = cmd.trim().removePrefix("/model").trim().takeIf { cmd.trim().startsWith("/model ") }
                    val err = app.yxi.ssh.catching {
                        if (alias != null) app.yxi.agent.Model.switchFast(s0, sessionName, alias)
                        else { SessionProbe.send(s0, sessionName, cmd); null }
                    }.getOrElse { it.message }
                    android.widget.Toast.makeText(ctx, err ?: t("已发 %s").format(cmd), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDismiss = { showModes = false },
    )

    // 快路模型选单：本地名单，点一下 = `/model <名字>`（两个来回）。
    // ⚠️ **说清楚它会改账号默认** —— 这是实测出来的语义，不是猜的（Model.kt 顶部）。
    //    想只换这个会话的走底下那行「仅本会话」，那条慢（要开 TUI 选单）。
    fastModels?.let { av ->
        val curModel = ctxUse?.model.orEmpty().removePrefix("claude-")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { fastModels = null },
            title = { Text(t("换模型")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    av.models.forEach { name ->
                        val isCur = name.isNotBlank() && (curModel.startsWith(name.substringBefore('[')) || name == av.default && curModel.isEmpty())
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val s0 = ssh ?: return@clickable
                                fastModels = null
                                scope.launch {
                                    modelBusy = true
                                    val err = app.yxi.ssh.catching { app.yxi.agent.Model.switchFast(s0, sessionName, name) }.getOrElse { it.message }
                                    modelBusy = false
                                    android.widget.Toast.makeText(ctx, err ?: t("已切到 %s · 也成了账号默认").format(name), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }.padding(4.dp, 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (name == av.default) Text(t("默认"), style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                    }
                    Text(
                        t("点一下就切，顺便写成账号默认（以后新会话也用它）。"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(4.dp))
                    // 慢路：只换这个会话，不碰默认 —— 要开 TUI 选单再抓屏，1~4 秒
                    Text(
                        t("只换这个会话，不改默认（慢，要开选单）→"),
                        Modifier.fillMaxWidth().clickable {
                            val s0 = ssh ?: return@clickable
                            fastModels = null
                            scope.launch {
                                modelBusy = true
                                models = app.yxi.ssh.catching { app.yxi.agent.Model.open(s0, sessionName) }.getOrNull()
                                if (models == null) android.widget.Toast.makeText(ctx, t("它正忙着，或者输入框里有没发完的字 —— 等一下再点"), android.widget.Toast.LENGTH_LONG).show()
                                modelBusy = false
                            }
                        }.padding(4.dp, 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton({ fastModels = null }) { Text(t("算了")) } },
        )
    }

    // 思考强度选单：`/effort <级别>`，一个来回。同样会写成默认（回显明说的）。
    if (showEffort) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showEffort = false },
        title = { Text(t("思考强度")) },
        text = {
            Column {
                listOf("max" to t("最大思考"), "high" to t("高强度"), "mid" to t("中等"), "low" to t("低")).forEach { (lv, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val s0 = ssh ?: return@clickable
                            showEffort = false
                            scope.launch {
                                val err = app.yxi.ssh.catching { app.yxi.agent.Model.setEffort(s0, sessionName, lv) }.getOrElse { it.message }
                                android.widget.Toast.makeText(ctx, err ?: t("已发 %s").format("/effort $lv"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }.padding(4.dp, 10.dp),
                    ) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (ctxUse?.effort == lv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(lv, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = Muted)
                    }
                }
                Text(t("会一并写成账号默认。"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton({ showEffort = false }) { Text(t("算了")) } },
    )

    models?.let { list ->
        val cur = list.firstOrNull { it.current }?.number ?: 1
        var asDefault by remember(list) { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                // ⚠️ 关掉也要发 Esc —— 面板留在屏幕上，看板抓屏会一直看到它
                val s0 = ssh
                scope.launch { s0?.let { app.yxi.agent.Model.cancel(it, sessionName) } }
                models = null
            },
            title = { Text(t("换模型")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val s0 = ssh ?: return@clickable
                                    scope.launch {
                                        app.yxi.ssh.catching {
                                            app.yxi.agent.Model.pick(s0, sessionName, cur, c.number, asDefault)
                                        }
                                        models = null
                                    }
                                }
                                .padding(4.dp, 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (c.current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    c.desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 2,
                                )
                            }
                            if (c.current) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { asDefault = !asDefault }.padding(4.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (asDefault) "☑" else "☐", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t("同时设为默认（以后新开的会话都用它）"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        t("不勾的话只换这个会话，别的会话和默认都不动。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton({
                    val s0 = ssh
                    scope.launch { s0?.let { app.yxi.agent.Model.cancel(it, sessionName) } }
                    models = null
                }) { Text(t("算了")) }
            },
        )
    }

    // 附件图片放大看。
    // ⚠️ **两条路，缺一不可。**
    //  · 刚选好还没发：本地那份（`localUri`）—— 文件就在这台手机上，
    //    再从服务器拉回来是白跑一趟。
    //  · **已经发出去的**（点消息里的缩略图进来）：`localUri` 是空的，
    //    因为那条消息是从转录读回来的，只有远端路径。
    //    原来只走本地那条，于是点缩略图必然弹「读不出来了 —— 授权可能已经失效」，
    //    而那句话还是错的（不是授权问题，是压根没去拿）。用户报的就是这个。
    preview?.let { a ->
        val uri = a.localUri
        val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, uri, a.remotePath, ssh) {
            value = withContext(Dispatchers.IO) {
                uri?.let {
                    app.yxi.ssh.catching {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(it))?.use { s -> s.readBytes() }
                    }.getOrNull()
                } ?: run {
                    // 远端那份。⚠️ 上限 12MB —— 放大看要的是清楚，比缩略图那 8MB 宽一点，
                    // 但也不能为一张原图把 App 拖死。
                    val s0 = ssh ?: return@run null
                    val sftp = app.yxi.ssh.catching { s0.openSftp() }.getOrNull() ?: return@run null
                    try { app.yxi.ssh.catching { sftp.read(a.remotePath, 12 shl 20) }.getOrNull() }
                    finally { runCatching { sftp.close() } }
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
                        // ⚠️ 原来这句写死「授权可能已经失效」—— 那只对本地那条路成立。
                        // 已发出去的图拉不到，绝大多数是**暂存区 3 天清掉了**。
                        // 说错原因比不说更糟：用户会去翻权限设置，翻半天没有用。
                        if (a.localUri != null) t("读不出来了 —— 这张图的授权可能已经失效")
                        else t("这张图已经不在服务器上了（暂存区只留 3 天）"),
                        Modifier.padding(14.dp, 10.dp, 14.dp, 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * 麦克风：**点一下开始，再点一下结束**。
 *
 * ⚠️ 原来是按住说话。用户 2026-09-04：「长按说话实在是太麻烦了，应该是点按说话」——
 * 手按着不能干别的（滚不动、看不了刚才那条），录长一点的内容尤其别扭。
 * 点按的代价是「不知道有没有录上」，所以配套做了 [RecordingBar]：**输入框整条变成实时波形**，
 * 波形动 = 真的在收音（数据来自 [Recorder.level]，不是假动画）。
 *
 * ⚠️ 识别期间转圈**且不可按**：传 + 跑模型要 3~5 秒，这几秒里再点一次会开新录音、冲掉上一条。
 */
@Composable
private fun MicTap(
    recording: Boolean,
    busy: Boolean,
    onStart: () -> Boolean,
    onStop: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (recording) MaterialTheme.colorScheme.errorContainer else Color.Transparent, CircleShape)
            .then(
                if (busy) Modifier
                else Modifier.clickable { if (recording) onStop() else onStart() }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
            )
            recording -> Box(
                // 在录：一个方块 = 停止（跟播放器一个语言，不用教）
                Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.error),
            )
            else -> GlyphIcon(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
        }
    }
}

/**
 * 录音时输入框里的那一条：**实时波形 + 计时 + 点一下结束**。
 *
 * ⚠️ 波形画的是 [Recorder.level] 的历史（每 60ms 采一格），不是循环动画 ——
 * 「有没有收到声音」这件事必须能从屏幕上看出来。麦克风被别的 app 占着时波形是平的，
 * 一眼就知道不对。
 */
@Composable
private fun RecordingBar(recorder: Recorder, live: Boolean, onStop: () -> Unit) {
    val bars = remember { mutableStateListOf<Float>() }
    var ms by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(60)
            bars += recorder.level
            if (bars.size > 48) bars.removeAt(0)
            ms = System.currentTimeMillis() - t0
        }
    }
    // ── 边说边出字（用户 2026-09-05：「我说多少它识别以后就跳出来，这样我能看到有没有讲错话」）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var partial by remember { mutableStateOf("") }
    if (live) LaunchedEffect(Unit) {
        while (true) {
            val t0 = System.currentTimeMillis()
            val pcm = recorder.snapshot()
            // 半秒以下解出来基本是空的，白费一次
            if (pcm != null && pcm.size >= Recorder.RATE / 2) {
                app.yxi.agent.OnDeviceAsr.partial(ctx, pcm)?.takeIf { it.isNotBlank() }?.let { partial = it }
            }
            // ⚠️⚠️ **自限速**：这一拍解了多久，就至少歇多久。
            //    SenseVoice 是离线模型，每次都从头解整段（见 OnDeviceAsr.partial）——
            //    音频越长解码越慢，固定间隔的话会越积越多，到后面手机发烫、字还越来越滞后。
            //    「花多久歇多久」让它自己降频：短句一秒好几拍，长录音自动稀疏下来。
            kotlinx.coroutines.delay(maxOf(700L, System.currentTimeMillis() - t0))
        }
    }
    val red = MaterialTheme.colorScheme.error
    Column {
    // ⚠️ 这是**临时结果**，会随着你继续说而整句变化（不是往后追加）——
    //    所以颜色比正文淡，让人一眼知道「还没定」。最终那次识别出来才进输入框。
    if (partial.isNotBlank()) Text(
        partial,
        Modifier.fillMaxWidth().padding(20.dp, 12.dp, 20.dp, 0.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        // 溢出时留住**后面**的字：正在说的那半句比开头要紧
        overflow = androidx.compose.ui.text.style.TextOverflow.StartEllipsis,
    )
    Row(
        Modifier.fillMaxWidth().padding(20.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(red))
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(28.dp)) {
            val n = 48
            val w = size.width / n
            val list = bars
            for (i in 0 until n) {
                val v = list.getOrElse(list.size - n + i) { 0f }
                // 放大一点：正常说话的峰值也就 0.2~0.5，原样画几乎看不见
                val h = (v * 3.2f).coerceIn(0.04f, 1f) * size.height
                drawRoundRect(
                    red.copy(alpha = 0.35f + 0.65f * (i.toFloat() / n)),
                    topLeft = androidx.compose.ui.geometry.Offset(i * w + w * 0.2f, (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(w * 0.6f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.3f),
                )
            }
        }
        Text(
            "%d:%02d".format(ms / 60000, (ms / 1000) % 60),
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.outline,
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(red))
        }
    }
    }
}

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
private fun BasicTextFieldRow(
    value: String,
    focus: androidx.compose.ui.focus.FocusRequester,
    onFocus: (Boolean) -> Unit = {},
    onLines: (Int) -> Unit = {},
    onValue: (String) -> Unit,
) {
    // 草稿超过 7 行 = 框里滚得动、但看不出「下面还有」。⚠️ maxLines 只限**视口高度**，
    // onTextLayout 给的 lineCount 是**全文行数**（不是截断后的），所以这个判断是准的。
    var over by remember { mutableStateOf(false) }
    androidx.compose.foundation.text.BasicTextField(
        value, onValue,
        modifier = Modifier.padding(20.dp, 15.dp).fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { onFocus(it.isFocused) },
        onTextLayout = {
            val n = if (value.isEmpty()) 1 else it.lineCount
            over = n > 7
            onLines(n)
        },
        // 最多 7 行，多了在框里滚 —— 别把对话顶没了
        maxLines = 7,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            // ⚠️ 超过 7 行时**把文字自己的底边淡出**，而不是在上面压一条实色渐变 ——
            //    输入框是 [GlassPill]（半透明玻璃 + 流动底色），压实色会变成一根灰条。
            //    DstIn 只改 alpha、不碰颜色，所以底下是什么都不影响。
            //    这一层只画不挡手：拖它就是拖输入框自己（收栏那条 nestedScroll 已经不抢，见 barsConn）。
            Box(
                Modifier
                    .graphicsLayer {
                        if (over) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        if (!over) return@drawWithContent
                        val fade = (18.dp.toPx() / size.height).coerceIn(0.05f, 0.4f)
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
            ) {
                if (value.isEmpty()) {
                    Text(t("说一句…"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                }
                inner()
            }
        },
    )
}

/**
 * 复制到剪贴板 + 吱一声。
 *
 * ⚠️ 用 `android.content.ClipboardManager` 而不是 Compose 的 `LocalClipboardManager` ——
 * 项目里另外两处（[HostsScreen] 的公钥、[DevMode] 的诊断）已经是这个写法，统一。
 */
/** `656203` → `656K`。⚠️ 跟 [app.yxi.agent.Today.tokenText] 同一套写法，别两处不一样。 */
private fun tokenText(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.0fK".format(n / 1e3)
    else -> n.toString()
}

private fun copy(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    // ⚠️ 标成敏感：安卓 13+ 复制后会弹一个**带内容预览**的浮层，对话正文会给旁边的人看见
    val clip = android.content.ClipData.newPlainText("yxi", text)
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
    // Android 13+ 系统自己会弹「已复制」的浮层，再 Toast 一次就是两层，所以只在旧系统上吱
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(ctx, t("已复制"), android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Item(
    item: ChatItem,
    onCopy: (String) -> Unit,
    onPopQueue: () -> Unit,
    // 缩略图要从服务器拉图 —— 所以这一层得拿得到连接
    ssh: app.yxi.ssh.SshSession? = null,
    onOpenRef: (app.yxi.agent.Attachments.Ref) -> Unit = {},
) = when (item) {
    is ChatItem.UserText -> UserBubble(item.text, onCopy, ssh, onOpenRef)
    is ChatItem.Queued -> QueuedBubble(item.text, onCopy, onPopQueue, ssh, onOpenRef)
    is ChatItem.Injected -> InjectedCard(item)
    is ChatItem.ApiError -> ApiErrorCard(item.text)
    // ⚠️ AI 的输出**不做长按菜单，做原生文本选择** —— 想要的多半是里面的一个 URL
    // 或者一段命令，整段复制反而要回头再删。SelectionContainer 给的是系统那套
    // 选择手柄 + 复制条，长按即起，双击选词。
    // （代价：长按被选择消费掉了，所以这一支不能再挂 combinedClickable。）
    is ChatItem.AssistantText -> Column(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            // ⚠️ `remember`：一条长回复每次重组都重扫一遍正则不划算，而它只跟原文有关
            val md = remember(item.markdown) { app.yxi.agent.Linkify.apply(item.markdown) }
            Markdown(
                md,
                // ⚠️ 一定要传 —— 库默认把 `##` 渲染成 45sp（正文的 3 倍）。见 [yxiMarkdown]
                typography = yxiMarkdown(),
                // 表格换成自己画的：横向滚动 + 单元格换行，不再一堆省略号（见 [MarkdownScrollTable]）
                components = com.mikepenz.markdown.compose.components.markdownComponents(
                    table = { MarkdownScrollTable(it) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // ⚠️ **必须有一条不依赖系统选择工具栏的复制路径。**
        // 用户报的：在 AI 回复里选中文字，弹出来的工具栏**只有「全选」没有「复制」**。
        // 那条工具栏是 Compose 的 `SelectionManager` 给的 —— 它拿不到可复制的文本时
        // 就只画「全选」，而 Markdown 那个库的排版组件不一定都注册进了选择区。
        // 这个我在开发机上复现不了（要真机 + 那个渲染器），所以**不赌它能修好**：
        // 直接给一颗按钮，把整段原文塞进剪贴板。选择手柄照旧留着，能用最好。
        //
        // ⚠️ 不做成长按 —— 长按已经归文本选择了，抢过去等于把「选一句」这个更细的能力废掉。
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable { onCopy(item.markdown) },
            ) {
                Text(
                    t("复制整段"),
                    Modifier.padding(10.dp, 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
    is ChatItem.Thinking -> ThinkingRow(item.text)
    is ChatItem.ToolCall -> ToolCard(item)
    is ChatItem.Unknown -> Unit   // 兜底：不认识的块静默跳过，不要在界面上留垃圾
}

/** 跳到「最后一条内部再往下这么多像素」——Compose 会夹到列表真正的末尾，长条目的尾巴也能露出来。 */
private const val END_OFFSET = 100_000

/**
 * 滚到**真正的末尾**。
 *
 * ⚠️ `scrollToItem(last)` 只是把最后一条的**顶部**对齐视口顶部 ——
 * 那条要是比一屏长（长回复很常见），尾巴还在屏幕外。所以要带 [END_OFFSET]，
 * 再靠下面 `repeat` 里的第一支（看得见最后一条时补差值）把剩下的几十像素找齐。
 * 次数封顶，免得内容还在增长时转不出来。
 *
 * ⚠️ **看不见最后一条的那两支，以前是两个空 `{}`** —— 也就是「离底部远」时这个函数
 * 一下都不滚，空转 30 帧就退出。用户报的「点了 ↓ 完全没反应」就是它：
 * ↓ 按钮只在**不在底部**时才出现，那时最后一条基本都不可见，正好落进空分支。
 * 改动的时候别把有 `when` 分支写成空的，编译器不会管。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToEnd(smooth: Boolean = false) {
    repeat(30) {
        val last = layoutInfo.totalItemsCount - 1
        if (last < 0) return
        val lastVis = layoutInfo.visibleItemsInfo.lastOrNull()
        val end = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        when {
            lastVis != null && lastVis.index >= last -> {
                // ⚠️ **看得见最后一条时只补差的那点像素，绝不整块跳。**
                //    `scrollToItem(last)` 是把最后一条的**顶部**顶到屏幕上沿 —— 最后一条要是长回复或大工具卡，
                //    中间那一帧画面就甩到别处去了（#228）。
                val delta = (lastVis.offset + lastVis.size - end).toFloat()
                if (delta <= 0.5f) return
                if (smooth) animateScrollBy(delta, tween(170, easing = LinearOutSlowInEasing))
                else scroll { scrollBy(delta) }
            }
            // ⚠️ **跟随时要「滑过去」，不是「跳过去」。** 会话在出字时每 300ms 来一批，
            //    每批瞬移一两百像素 —— 眼睛看到的就是「一闪一闪一跳一跳」（用户第三次报同一个现象）。
            //    滑 170ms 就成了「往下滚了一段」，是运动不是闪。远了才瞬移（点 ↓ 从半山腰跳底部那种）。
            smooth && lastVis != null && last - lastVis.index <= 4 -> {
                animateScrollToItem(last, END_OFFSET)
            }
            // 大 offset 会被夹到列表真正的末尾，一次到位
            else -> {
                scrollToItem(last, END_OFFSET)
            }
        }
        androidx.compose.runtime.withFrameNanos { }   // 等这次布局落定再看还差多少
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

/**
 * API 报错。**故意长得跟正文完全不一样。**
 *
 * ⚠️ 不走 markdown：它在转录里是一条 assistant 消息，用正文渲染的话
 * 屏幕上就像 Claude 一本正经地在跟你解释「服务器过载」——
 * 而这其实是**根本没轮到它说话**。红底 + 感叹号 + 等宽，一眼就知道是机器故障不是回答。
 *
 * ⚠️ 不占满宽度、不居中：它是时间线上的一个「这里断了一下」的标记，
 * 做得太抢眼反而会盖过真正的对话。
 */
@Composable
private fun ApiErrorCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp, 11.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "!",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text.trim(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 你说过的话。长按 → 复制整段。 */
@Composable
private fun UserBubble(
    text: String,
    onCopy: (String) -> Unit,
    ssh: app.yxi.ssh.SshSession? = null,
    onOpenRef: (app.yxi.agent.Attachments.Ref) -> Unit = {},
) {
    val bub = Skins.bubble(androidx.compose.ui.platform.LocalContext.current)
    // ⚠️ **把附件那几行从正文里摘出来单独画。** 发出去之后气泡里是
    // `[图片1] /root/src/tmp/xxx/0902-091207-IMG_....jpg` —— 一条又长又没用的路径
    // 占四行，而用户想看的是**那张图**（他的原话：要像参考款一样出个缩略图）。
    // ⚠️ `remember`：正则跟原文一一对应，每次重组重扫一遍不划算。
    val (refs, body) = remember(text) { app.yxi.agent.Attachments.parseRefs(text) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        var menu by remember { mutableStateOf(false) }
        // ⚠️⚠️ **contentAlignment 必须给。** Box 的孩子默认落在**左上角**，而
        //    里面那句 `Column(horizontalAlignment = Alignment.End)` 只在 Column
        //    **自己那点宽度**里生效 —— Column 是 wrap-content，所以它等于什么都没做。
        //    结果：长消息把 Column 撑到 85% 满宽，看着像右对齐；**短消息（「继续」两个字）
        //    就停在屏幕 15% 处**，用户 2026-09-05 截图报的就是这个。
        //    「看起来对」和「对」不是一回事：这类对齐 bug 只有短内容才露馅。
        Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.TopEnd) {
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 缩略图在气泡**外面上方** —— 跟参考款一样。放进气泡里的话，
            // 图和文字共用那个圆角背景，一张竖图会把气泡撑成一条，很难看。
            if (refs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    refs.forEach { AttachThumb(it, ssh, onOpenRef) }
                }
            }
            // ⚠️ 只有附件、没打字时**不画空气泡**（很常见：直接发一张图）
            if (body.isNotBlank() || refs.isEmpty())
            // 气泡的底色 / 描边 / 尾巴 / 角标由装扮决定，画法在 BubbleBox（个性化商店的预览用的是同一段，所见即所得）。
            // ⚠️ **默认那套故意没有颜色**（id 为空）—— 默认气泡必须跟着主题走，见 bubbleFill / bubbleInk。
            BubbleBox(
                bub,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    body.ifBlank { text },
                    Modifier.padding(18.dp, 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bubbleInk(bub),
                )
            }
          }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text(t("复制整段")) },
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
private fun QueuedBubble(
    text: String,
    onCopy: (String) -> Unit,
    onPopQueue: () -> Unit,
    ssh: app.yxi.ssh.SshSession? = null,
    onOpenRef: (app.yxi.agent.Attachments.Ref) -> Unit = {},
) {
    // ⚠️ 跟 [UserBubble] 一样把附件摘出来画缩略图 —— 原来这里画的是原文，
    // 「排队中」时是一条路径、入了转录才变成图，用户说「有点割裂」。
    val (refs, body) = remember(text) { app.yxi.agent.Attachments.parseRefs(text) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        var menu by remember { mutableStateOf(false) }
        // ⚠️⚠️ **contentAlignment 必须给。** Box 的孩子默认落在**左上角**，而
        //    里面那句 `Column(horizontalAlignment = Alignment.End)` 只在 Column
        //    **自己那点宽度**里生效 —— Column 是 wrap-content，所以它等于什么都没做。
        //    结果：长消息把 Column 撑到 85% 满宽，看着像右对齐；**短消息（「继续」两个字）
        //    就停在屏幕 15% 处**，用户 2026-09-05 截图报的就是这个。
        //    「看起来对」和「对」不是一回事：这类对齐 bug 只有短内容才露馅。
        Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.TopEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (refs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            refs.forEach { AttachThumb(it, ssh, onOpenRef) }
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
            shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            ),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp)).combinedClickable(
                onClick = {},
                onLongClick = { menu = true },
            ),
        ) {
            Column(Modifier.padding(18.dp, 12.dp)) {
                Text(
                    t("排队中 · 它忙完就轮到这条"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    body.ifBlank { text },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
        }   // Column
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(t("复制整段")) },
                onClick = { onCopy(text); menu = false },
            )
            // ⚠️ 文案必须说「都收回来」。TUI 的 `Up` 是全有全无的，
            // 排了三条按一次就三条一起回来 —— 写成「撤回这一条」是骗人的。
            DropdownMenuItem(
                text = { Text(t("收回改一改")) },
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
/** 「还不知道」那条。跟 [LiveStatus] 同一个位置、同一套视觉语言，但压成灰的、没有停止键。 */
@Composable
private fun SyncNote(text: String) {
    val dots = rememberInfiniteTransition(label = "sync")
    val a by dots.animateFloat(
        0.3f, 0.9f,
        infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth().padding(20.dp, 2.dp, 20.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = a), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text, Modifier.weight(1f), fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            maxLines = 1, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun LiveStatus(status: String?, onStop: () -> Unit) {
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
            status ?: t("在忙…"),
            // ⚠️ **它显得大不是因为字号大**（量过：12sp，比正文 16sp 还小），
            // 是视觉重量：labelMedium 自带 Medium 字重 + 强调色 + 独占一行。
            // 所以这里压的是字重和颜色，不是一味调小 —— 它还得看得见。
            // 单行截断：状态里带 token 数，长的会折成两行，那时候才是真的一大块。
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
        )
        // 一键「停」—— 发 Escape 打断当前回合。跑飞时不用进终端就能掐（Escape 在 SAFE_KEY 白名单里）
        Spacer(Modifier.width(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.errorContainer, shape = Pill,
            modifier = Modifier.clip(Pill).clickable(onClick = onStop),
        ) {
            Text(
                "■ " + t("停"),
                Modifier.padding(12.dp, 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
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
            modifier = Modifier.clip(Pill).clickable { open = !open },
        ) {
            Row(
                Modifier.padding(16.dp, 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (open) t("思考 ▾") else t("思考 ▸"),
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
        modifier = Modifier.size(46.dp).clip(Pill).clickable(onClick = onTap),
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
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { open = !open },
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
/**
 * 把注入内容洗成能看的纯文本。
 *
 * ⚠️ **ANSI 也要洗掉。** 命令输出里原样带着转义序列（`/model` 的回显就是），
 * 卡片是纯文本渲染，不洗的话屏幕上直接冒出 `[1m…[22m` 这种。
 */
private fun stripTags(t: String): String =
    app.yxi.agent.Transcript.clean(t)
        .replace(Regex("</?[a-z][a-z0-9-]*(\\s[^>]*)?>"), " ")
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n").trim()

/**
 * 送完键之后**等屏幕真的变了**再返回，而不是死等一个固定时长。
 *
 * ⚠️ 病根（用户报「点一个选项要等十秒才到下一题」）：原来是 `delay(500)` 然后抓一次 ——
 * TUI 往往还没重绘完，抓到的还是旧屏；而这期间轮询被 `busy` 停着，
 * 于是要等它恢复后的下一轮（最长 2.5 秒）甚至再下一轮才看得到新题。
 *
 * 现在：短间隔连抓，**指纹一变立刻返回**（通常 <1 秒）；每抓到一次就先喂给界面 [onEach]，
 * 让它跟着动。到上限还没变就返回最后一次的结果（可能是它真没变）。
 */
/**
 * 等**推流送来的新状态**，不自己去抓 —— 抓屏交给 [SessionProbe.watchScreen] 那条长连。
 *
 * ⚠️ 老版本在这里自己轮询 SSH，每轮一次就是一个往返，正是「点一下等十秒」的来源。
 * 现在只读本地状态（推流在实时更新它），零网络开销。
 */
private suspend fun waitScreenChange(
    before: String,
    timeoutMs: Long = 4000,
    get: () -> Pending?,
): Pending? {
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < timeoutMs) {
        val cur = get()
        if (cur == null || cur.fingerprint != before) return cur
        delay(60)
    }
    return get()
}


/**
 * 附件条上「还在传」的一个。[cancelled] 是给 SFTP 进度回调看的开关；[job] 是它自己的协程，✕ 只取消它。
 * ⚠️ 用 class 不用 data class：它是可变状态，放进 mutableStateListOf 要按引用比。
 */
internal class Upload(val uri: android.net.Uri, val name: String, val isImage: Boolean) {
    var progress by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)
    @Volatile var cancelled = false
    var job: kotlinx.coroutines.Job? = null
}


/**
 * 会话状态那条上的一格。
 *
 * ⚠️ **为什么改成药丸**（用户 2026-09-04：「⚡ 标志和下面的记录看起来很违和」）：
 * 原来这行是**五段裸文字**直接飘在正文上——蓝的「⚡模式 opus-5」、铜色的「最大思考」、
 * 琥珀的「上下文 163K」，字号字体各不同，底下没有承托。而这个 App 从看板到输入框
 * **一切都是药丸**（见 design/STYLE.md「药丸是基本形状语言」），只有这一条不是，
 * 于是它看着像别的软件掉进来的一行调试信息。
 *
 * 现在：每格一颗药丸，底色统一 `surfaceContainerHigh`（跟上面那排 终端/对话/文件/实验室 同一层），
 * **颜色只留给有含义的地方**——能点的用主色、思考强度用铜色、上下文吃紧了才染琥珀，其余一律 Muted。
 */
@Composable
private fun StatChip(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    mono: Boolean = false,
    icon: Ico? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Pill,
        modifier = if (onClick != null) Modifier.clip(Pill).clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            Modifier.padding(10.dp, 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { YxiIcon(it, 12.dp, color) }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.let {
                    if (mono) it.copy(fontFamily = FontFamily.Monospace) else it
                },
                color = color,
                maxLines = 1,
            )
        }
    }
}

/**
 * 让这一块**悬浮**在后面的内容上方：布局上不占高度（后面的列表从它底下开始，靠 contentPadding 让位），
 * 画面上按 [frac]（0 = 全在，1 = 收完）跟页眉一起往上平移 + 淡出。
 * ⚠️ **收放只动画面不动布局** —— 改布局高度会把列表推来推去（1.1.10 的 collapseBy 就是这么错的）。
 * [onHeight] 报出它的真实高度，给列表的 contentPadding 用。
 */
private fun Modifier.floatTop(frac: Float, headerPx: Int, onHeight: (Int) -> Unit): Modifier = this
    .zIndex(1f)
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        onHeight(p.height)
        layout(p.width, 0) { p.placeRelative(0, 0) }
    }
    .graphicsLayer {
        val f = frac.coerceIn(0f, 1f)
        translationY = -(headerPx + size.height) * f
        alpha = 1f - f
    }

/** Claude 此刻在做计划里的哪一步 —— 最近一次 TodoWrite 里 in_progress 那条 */
private fun doingNowText(items: List<ChatItem>): String? =
    items.filterIsInstance<ChatItem.ToolCall>().lastOrNull { it.name == "TodoWrite" }
        ?.input?.optJSONArray("todos")?.let { a ->
            (0 until a.length()).asSequence().mapNotNull { a.optJSONObject(it) }
                .firstOrNull { it.optString("status") == "in_progress" }
                ?.let { it.optString("activeForm").ifBlank { it.optString("content") } }
        }?.takeIf { it.isNotBlank() }

