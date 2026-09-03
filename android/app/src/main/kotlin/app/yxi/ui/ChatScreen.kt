package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
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
    fun startUpload(up: Upload) {
        val s0 = ssh ?: run { up.error = t("还没连上"); return }
        up.error = null; up.progress = 0f; up.cancelled = false
        up.job = scope.launch {
            // ⚠️ **先把字节读进内存，再谈传。** 读文件本身可能失败（授权过期、文件没了），
            // 那跟「传失败」是两码事，要分开报。
            val bytes = withContext(Dispatchers.IO) {
                app.yxi.ssh.catching { ctx.contentResolver.openInputStream(up.uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) { up.error = t("这个文件读不出来"); return@launch }
            // 排队：一个一个传，序号才连得上（并发时每个协程读到同一份 staged，五张全叫「图片1」，#156）
            uploadLock.withLock {
                if (up.cancelled) return@withLock
                val idx = staged.count { it.isImage == up.isImage } + 1
                val stamp = java.text.SimpleDateFormat("MMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
                val path = app.yxi.agent.Attachments.remotePath(sessionName, up.name, stamp)
                // ⚠️ **每次开一条新 SFTP 通道，别复用共享那条。** 复用的那条空闲久了会被服务器关掉、
                // 或上一次出错后进了坏状态，之后每次 put 都失败 —— 原话「附件上传要好几次才能成功」。
                // 还是试两次：新通道也可能撞上网络抖动。
                var ok: app.yxi.agent.Attachments.Staged? = null
                var lastErr: Throwable? = null
                repeat(2) {
                    if (ok != null || up.cancelled) return@repeat
                    val fresh = app.yxi.ssh.catching { s0.openSftp() }.getOrNull()
                    if (fresh == null) { lastErr = IllegalStateException(t("开不了 SFTP 通道")); return@repeat }
                    try {
                        ok = app.yxi.agent.Attachments.upload(fresh, sessionName, up.name, bytes, idx, up.isImage, stamp) { done, total ->
                            up.progress = if (total > 0) done.toFloat() / total else 0f
                            !up.cancelled          // ✕ 按下去这里返回 false，jsch 就停
                        }.copy(localUri = up.uri.toString())
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastErr = e
                        // 取消 / 失败：传了一半的那个别留在服务器上
                        runCatching { fresh.rm(path) }
                    } finally {
                        runCatching { fresh.close() }
                    }
                }
                when {
                    up.cancelled -> {}
                    ok != null -> { staged = app.yxi.agent.Attachments.renumber(staged + ok!!); queue.remove(up) }
                    else -> up.error = app.yxi.ssh.Sftp.explain(lastErr ?: RuntimeException())
                }
            }
            runCatching { app.yxi.agent.Attachments.sweep(s0) }   // 顺手清 3 天前的
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
            android.widget.Toast.makeText(ctx, t("语音识别就绪：按住麦克风说话，松开就识别（点一下不行）"), android.widget.Toast.LENGTH_LONG).show()
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
        if (items.isEmpty()) { items = shown; inc.ctx?.let { ctxUse = it } }
    }

    LaunchedEffect(sessionName, ssh) {
        // ⚠️ **连接没了要说一声，不能默默 return。** 原来这里直接 `?: return`，
        // 于是「正在重连」这个事实在对话页上**一个字都不显示** —— 配上空的 items
        // 就是一整块白。重连是 Workspace 那边自动做的，这里只负责别装死。
        val s = ssh ?: run {
            if (items.isEmpty()) status = t("连接断了，正在重连…")
            return@LaunchedEffect
        }
        // ⚠️ 会话名必须传 —— 转录按 sessionId 找，不按目录找。
        // 只给 cwd 的话，会话里 cd 过一次就再也找不到（用户报的 hexingyang 就是）。
        val file = TranscriptStream.latestFor(s, cwd, sessionName.orEmpty())
        if (file == null) {
            status = t("这个会话里没找到 Claude Code 的转录\n（%s）").format(cwd)
            return@LaunchedEffect
        }
        // ⚠️ **有东西看之前别把提示清掉。** 原来这里先 `status = null` 再去拉 0.58 MB，
        // 那几秒钟正好是「上面没提示、下面没内容」的纯白屏。
        status = if (items.isEmpty()) t("正在载入对话…") else null

        // ⚠️ **先画最新的一屏，再补历史。** `tail -n 800` 从最老那条开始吐、
        // 最新的最后才到，所以完整那次要等 4.17 MB 传完你才看得见最新内容。
        // 这里先要 60 行（0.58 MB，一个来回），立刻有东西看；
        // 下面那条完整流回来之后整体替换。
        runCatching { TranscriptStream.head(s, file) }
            .onSuccess { head ->
                if (head.isNotEmpty()) {
                    // ⚠️ 用 `Incremental` 而不是 `Transcript.parse` —— 后者不给 ctx。
                    // 只靠下面那条流的话，**会话闲着时上下文永远显示不出来**：
                    // `tail -f` 只送新行，没有新的 assistant 消息就没有 usage。
                    val inc0 = Transcript.Incremental()
                    items = withContext(Dispatchers.Default) {
                        inc0.add(head.asSequence()); inc0.snapshot()
                    }
                    inc0.ctx?.let { ctxUse = it }
                    // 存下来，下次从后台切回来能立刻画出这几条
                    app.yxi.agent.TranscriptCache.save(ctx, sessionName, cwd, head)
                }
                status = null
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (items.isEmpty()) status = t("载入失败：%s").format(it.message ?: "")
            }

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
        // ⚠️ **历史悄悄在后台灌，界面一直停在 head（最新一屏），不给用户看「从旧滚到新」。**
        // 病根：流是 `tail -n N` 从**最老**开始吐的，原来每来一批就 `items = inc.snapshot()`，
        // head 的最新内容立刻被「只含最老几行」的快照顶掉 —— 用户眼睁睁看着旧对话先加载、
        // 一路滚到新（原话：加载旧的对话先，又慢）。
        // 改成：inc 在后台默默攒，等它**追上 head 的最新那条**（key 对上）才把完整列表交出来；
        // 在那之前界面就是 head，稳稳停在最新。key 是 uuid，稳定，所以交接时最新那屏无缝不跳。
        val headLastKey = items.lastOrNull()?.key
        var caughtUp = headLastKey == null
        val inc = Transcript.Incremental()
        val pending = ArrayList<String>()
        // ⚠️ 收行和刷新是两个协程。`toList()` 和 `clear()` 之间来一行就会**丢**，所以都在同一把锁里。
        val lock = Any()
        launch {
            while (true) {
                delay(300)
                val batch = synchronized(lock) {
                    if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
                }
                if (batch.isEmpty()) {
                    // 一个空转周期 = 历史灌完。兜底：万一始终没匹配上 head 的 key，也把完整的放出来
                    if (!caughtUp) {
                        val snap = withContext(Dispatchers.Default) { inc.snapshot() }
                        if (snap.isNotEmpty()) { items = snap; caughtUp = true; inc.ctx?.let { ctxUse = it } }
                    }
                    if (items.isNotEmpty()) settled = true
                    continue
                }
                val snap = withContext(Dispatchers.Default) {
                    inc.add(batch.asSequence()); inc.snapshot()
                }
                // 还没追上 head 就先不换（继续显示 head=最新）；追上了才交出完整列表、之后每批都跟着更新
                if (!caughtUp && headLastKey != null && snap.any { it.key == headLastKey }) caughtUp = true
                if (caughtUp) { items = snap; inc.ctx?.let { ctxUse = it } }
            }
        }
        // ⚠️ 连接半路断了，`openExecStream` 会抛「session is down」——从这个 LaunchedEffect
        // 里逸出就是**闪退**（看聊天时连接抖一下就崩，见 TROUBLESHOOTING #125）。
        // catching 兜住（取消照抛，切页面照常），断了让看门狗重连，effect 会随 ssh 变化重启。
        app.yxi.ssh.catching {
            // backlog 从 800 收到 400：head 已经把最新一屏立刻显示了，历史在后台灌，
            // 400 行向上翻足够，传输/解析减半，追上得更快。
            TranscriptStream.stream(s, file, backlog = 400).collect { line -> synchronized(lock) { pending += line } }
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
    LaunchedEffect(items.size, settled) {
        if (items.isEmpty()) return@LaunchedEffect
        runCatching {
            // 灌历史时一律瞬移到底（停在最新）；灌完之后只有「粘着」才跟随 ——
            // 用户往上翻看旧消息时 stick=false，不会被新消息拽回底部（#61 那类错误）。
            if (!settled || stick) {
                listState.scrollToEnd()
                // ⚠️ 补一次。一批多条一次涌入时，最后一条的高度常在首次滚动**之后**才定下来，
                // 首次滚到的「底」其实差最后一条 —— 等布局稳一下再滚一次，才真正贴底。
                delay(120)
                if (!settled || stick) listState.scrollToEnd()
            }
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
    Column(Modifier.fillMaxSize()) {
        // 标题和路径由 Workspace 的头部管，这里只在出问题时说一句
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
        if (ctxUse != null || todayUse != null) {
            Row(
                // ⚠️ **必须能横滑。** 这一行现在有五格（⚡模式 / 模型 / 思考强度·模式 / 上下文 / 今日），
                // 窄屏放不下就会把左边的挤没 —— 加了「模式」这格之后风险是实打实的。
                // 横滑之后放不下也只是滑一下的事，不会有信息凭空消失。
                Modifier.fillMaxWidth().padding(18.dp, 0.dp, 18.dp, 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 模式快切：模型 / 思考强度 / ultracode，点开面板一点就切、可叠加
                Text(
                    t("⚡模式"),
                    Modifier.clickable(enabled = ssh != null) { showModes = true }.padding(end = 12.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                )
                // 模型名单独一格，**可点** —— 点开就是 `/model` 那个选单
                ctxUse?.model?.takeIf { it.isNotBlank() }?.let { m ->
                    Text(
                        if (modelBusy) t("开选单…") else m.removePrefix("claude-"),
                        Modifier
                            .clickable(enabled = ssh != null && !modelBusy) {
                                val s0 = ssh ?: return@clickable
                                scope.launch {
                                    modelBusy = true
                                    models = app.yxi.ssh.catching {
                                        app.yxi.agent.Model.open(s0, sessionName)
                                    }.getOrNull()
                                    if (models == null) {
                                        android.widget.Toast.makeText(
                                            ctx, t("它正忙着，或者输入框里有没发完的字 —— 等一下再点"),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    modelBusy = false
                                }
                            }
                            .padding(end = 10.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
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
                    if (bits.isNotEmpty()) Text(
                        bits.joinToString(" · "),
                        Modifier.padding(end = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Copper, maxLines = 1,
                    )
                }
                // 上下文单独一格、可点：快满了染琥珀，点一下发 /compact（手机上懒得敲那几个字母）
                ctxUse?.let { cu ->
                    // ponytail: 固定阈值 15 万 —— 逼近常见的 20 万自动压缩线；模型窗口不同就改这个数
                    val tight = cu.tokens >= 150_000
                    Text(
                        t("上下文 %s").format(tokenText(cu.tokens)),
                        Modifier
                            .clickable(enabled = ssh != null) {
                                val s0 = ssh ?: return@clickable
                                scope.launch { runCatching { SessionProbe.send(s0, sessionName, "/compact") } }
                                android.widget.Toast.makeText(ctx, t("已发 /compact —— 压一下上下文"),
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(end = 10.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (tight) Amber else MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
                todayUse?.let {
                    Text(
                        t("今日 %s · %s").format(it.tokenText, it.costText),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }

        // Claude 此刻在做计划里的哪一步 —— 取最近一次 TodoWrite 里 in_progress 那条，顶栏回显一眼看清进度
        val doingNow = remember(items) {
            items.filterIsInstance<ChatItem.ToolCall>().lastOrNull { it.name == "TodoWrite" }
                ?.input?.optJSONArray("todos")?.let { a ->
                    (0 until a.length()).asSequence().mapNotNull { a.optJSONObject(it) }
                        .firstOrNull { it.optString("status") == "in_progress" }
                        ?.let { it.optString("activeForm").ifBlank { it.optString("content") } }
                }?.takeIf { it.isNotBlank() }
        }
        doingNow?.let {
            Text(
                t("▶ 正在做 · %s").format(it),
                Modifier.fillMaxWidth().padding(18.dp, 0.dp, 18.dp, 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

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
        Box(Modifier.weight(1f)) {
            // 连着的同名工具卡合成一张（用户：「满屏都是 bash」），点开才铺开
            val rows = remember(items) { groupToolRuns(items) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 16.dp),
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
                    val fresh = seenKeys != null && item.key !in seenKeys!!
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
        }
        }   // CompositionLocalProvider(LocalUriHandler)

        // ⚠️ **三选一，而且「不知道」必须占一个位置。**
        // 少了中间那条的话，没连上/没同步跟「闲着」长得一模一样，
        // 用户会把一屏旧内容当成当前状态。
        when {
            ssh == null -> SyncNote(t("连接断了，正在重连…"))
            !synced -> SyncNote(t("正在取这个会话此刻的状态…"))
            live.busy -> LiveStatus(live.status, onStop = {
                val s0 = ssh
                if (s0 != null) scope.launch { runCatching { SessionProbe.sendKey(s0, sessionName, "Escape") } }
            })
        }

        pending?.let { p ->
            Box(Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp)) {
                PendingCard(
                    p, busy,
                    onPick = { o ->
                        val doSend: () -> Unit = {
                            scope.launch {
                                busy = true
                                // 送屏幕上写的那个数字本身，**不是列表下标**
                                awaitingFp = p.fingerprint
                                ssh?.let { SessionProbe.sendKey(it, sessionName, o.number.toString()) }
                                // 新屏由推流送达并解锁；这里只兜底，防万一没推过来卡住
                                delay(4000)
                                if (awaitingFp == p.fingerprint) { awaitingFp = null; busy = false }
                            }
                        }
                        // 危险审批（rm -rf / force-push / drop table …）先验一道指纹，防口袋误触
                        if (Risky.matches(p.title + " " + o.label)) Biometric.gate(ctx, o.label, doSend) else doSend()
                    },
                    onDiff = {
                        diffText = t("读取中…")
                        scope.launch { diffText = fetchGitDiff(ssh, cwd) }
                    },
                    // ←/→ 在问题之间走（真实 TUI 支持，脚注写着 Tab/Arrow keys to navigate）
                    onPrev = {
                        scope.launch {
                            busy = true
                            awaitingFp = p.fingerprint
                            ssh?.let { SessionProbe.sendKey(it, sessionName, "Left") }
                            delay(4000)
                            if (awaitingFp == p.fingerprint) { awaitingFp = null; busy = false }
                        }
                    },
                    onNext = {
                        scope.launch {
                            busy = true
                            awaitingFp = p.fingerprint
                            ssh?.let { SessionProbe.sendKey(it, sessionName, "Right") }
                            delay(4000)
                            if (awaitingFp == p.fingerprint) { awaitingFp = null; busy = false }
                        }
                    },
                    onSubmit = {
                        scope.launch {
                            busy = true
                            val s0 = ssh
                            if (s0 != null) {
                                // ⚠️ **不能硬编码「Right 一次就是 Submit 页」** —— 那只在停在最后一题时成立。
                                // 一路往右走，直到屏幕自己变成复核页，再选「Submit answers」。
                                var cur = p
                                repeat(6) {
                                    if (cur.review) return@repeat
                                    SessionProbe.sendKey(s0, sessionName, "Right")
                                    cur = waitScreenChange(cur.fingerprint) { pending } ?: cur
                                }
                                if (cur.review) {
                                    val submit = cur.options.firstOrNull { it.label.startsWith("Submit") }
                                    SessionProbe.sendKey(s0, sessionName, (submit?.number ?: 1).toString())
                                    waitScreenChange(cur.fingerprint) { pending }
                                }
                            }
                            busy = false
                        }
                    },
                )
            }
        }

        DiffSheet(diffText) { diffText = null }

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
        val composerShape = RoundedCornerShape(28.dp)
        Surface(
            color = Color.Transparent,
            shape = composerShape,
            modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 18.dp).heightIn(min = 56.dp)
                .clip(composerShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .background(glowBrush(busy = live.busy, waiting = pending != null)),
        ) {
            Row(
                Modifier.padding(6.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sftp != null) {
                    FlatIcon(Glyph.Plus, t("加附件")) { pick.launch("*/*") }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) { BasicTextFieldRow(draft) { draft = it } }
                // 语音：**服务器上有 `yxi-asr` 就按住说话**（识别在你自己的机器上跑，
                // 准得多、也不经过任何云 API）；没装就退回系统那个识别界面。
                if (onDevice || serverAsr) MicHold(
                    recording = recording,
                    busy = asrBusy,
                    onStart = {
                        holdHintAt = System.currentTimeMillis()
                        if (!hasMic) { askMic.launch(android.Manifest.permission.RECORD_AUDIO); false }
                        else recorder.start().let { why ->
                            if (why != null) {
                                android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                                false
                            } else true
                        }
                    },
                    onStop = {
                        val pcm = recorder.stop()
                        // ⚠️ 太短的 [Recorder.stop] 已经丢掉了 —— 不当错误报，但**点一下**（不到 300ms）要提示
                        //    「要按住」：这是从系统识别切到按住说话之后最常见的困惑
                        if (pcm == null && System.currentTimeMillis() - holdHintAt < 300) {
                            android.widget.Toast.makeText(ctx, t("要按住说话，松开才识别"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (pcm != null) scope.launch {
                            asrBusy = true
                            val said = if (onDevice) {
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
                val canSend = (draft.isNotBlank() || staged.isNotEmpty()) && queue.none { it.error == null }
                Surface(
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(enabled = canSend) {
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
        }
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
                scope.launch { runCatching { SessionProbe.send(s0, sessionName, cmd) } }
                android.widget.Toast.makeText(ctx, t("已发 %s").format(cmd), android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { showModes = false },
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

/** 胶囊里那种「无底色、点得动」的图标按钮。 */
/**
 * 按住说话的麦克风。
 *
 * ⚠️ **按住不是点一下。** 点一下开始、再点一下结束的话，用户没法确认自己录没录上；
 * 按住的语义是「手指在 = 在录」，松开就是说完了 —— 这也是所有语音输入的通用手势，
 * 不用教。
 *
 * ⚠️ **`onStart` 返回 false 时不进入录音态。** 没给权限、麦克风被占着都会走这条，
 * 那时候按钮不能变红 —— 变了就是在骗人「我在录」。
 *
 * ⚠️ 识别期间按钮变成转圈**且不可按**：实测传 + 跑模型要 3~5 秒，
 * 这几秒里再按一次会开一条新录音，把上一条的结果冲掉。
 */
@Composable
private fun MicHold(
    recording: Boolean,
    busy: Boolean,
    onStart: () -> Boolean,
    onStop: () -> Unit,
) {
    val on = recording
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                CircleShape,
            )
            .then(
                if (busy) Modifier
                else Modifier.pointerInput(Unit) {
                    // ⚠️ 用 `awaitEachGesture` 那一套，跟 [DPad] 里的按住逻辑同一个写法 ——
                    // 这个版本的 foundation 里就有它，而且行为可控：
                    // **手指抬起或手势被取消都算松开**，否则一滑出按钮就永远停不下来，
                    // 麦克风一直开着（别的 app 也用不了）。
                    awaitEachGesture {
                        awaitFirstDown()
                        if (!onStart()) return@awaitEachGesture
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            ch.consume()
                        }
                        onStop()
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> GlyphIcon(
                Glyph.Mic,
                if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                22.dp,
            )
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
private fun BasicTextFieldRow(value: String, onValue: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value, onValue,
        modifier = Modifier.padding(20.dp, 15.dp).fillMaxWidth(),
        // 最多 7 行，多了在框里滚 —— 别把对话顶没了
        maxLines = 7,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(t("说一句…"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
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
/** `656203` → `656K`。⚠️ 跟 [app.yxi.agent.Today.tokenText] 同一套写法，别两处不一样。 */
private fun tokenText(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.0fK".format(n / 1e3)
    else -> n.toString()
}

private fun copy(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("yxi", text))
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

/**
 * 滚到**真正的末尾**。
 *
 * ⚠️ `scrollToItem(last)` 只是把最后一条的**顶部**对齐视口顶部 ——
 * 那条要是比一屏长（长回复很常见），尾巴还在屏幕外。
 * 传个巨大的 `scrollOffset` 也不可靠。老老实实滚到滚不动为止。
 * 次数封顶，免得内容还在增长时转不出来。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToEnd() {
    // ⚠️ **自己读真正的最后一项，别信外面传进来的下标** —— 边界情况全栽在「下标过时」上：
    // 加载时 items 还在长，点的一刻 items.size 已经不是最新；懒加载下面几项还没组合，
    // `canScrollForward` 又会**提前**报 false，于是 scrollToItem 只跳到半路、循环第一下就 return，
    // 表现就是用户说的「点好几次才到底，每次只挪一点」。
    var last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    var stable = 0
    repeat(60) {
        // 每次判前等一帧：canScrollForward 从 layoutInfo 算，scrollToItem 完布局还没重量
        androidx.compose.runtime.withFrameNanos { }
        val n = layoutInfo.totalItemsCount - 1
        if (n > last) { last = n; scrollToItem(last); stable = 0 }   // 又来新内容，再跳到最后
        if (!canScrollForward) {
            // 连续两帧都到底才算真到底（一帧可能是布局没跟上的假象）
            if (++stable >= 2) return
        } else {
            stable = 0
            scroll { scrollBy(6000f) }
        }
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
    // ⚠️ **把附件那几行从正文里摘出来单独画。** 发出去之后气泡里是
    // `[图片1] /root/src/tmp/xxx/0902-091207-IMG_....jpg` —— 一条又长又没用的路径
    // 占四行，而用户想看的是**那张图**（他的原话：要像参考款一样出个缩略图）。
    // ⚠️ `remember`：正则跟原文一一对应，每次重组重扫一遍不划算。
    val (refs, body) = remember(text) { app.yxi.agent.Attachments.parseRefs(text) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth(0.85f)) {
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
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp),
                modifier = Modifier.clip(RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp)).combinedClickable(
                    onClick = {},
                    onLongClick = { menu = true },
                ),
            ) {
                Text(
                    body.ifBlank { text },
                    Modifier.padding(18.dp, 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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
        Box(Modifier.fillMaxWidth(0.85f)) {
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
