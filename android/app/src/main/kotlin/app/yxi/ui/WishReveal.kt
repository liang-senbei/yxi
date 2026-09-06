package app.yxi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * 祈愿出货 ——「**擦星星**」（老板 2026-09-06 给了九宫格 + 六张状态图 + 一段参考视频）。
 *
 * 一次出货的样子：云曦抱着一颗**灰色星星**坐在云上 → 手指在星星上擦 → 擦开的地方**露出这一档的颜色**
 * → 擦到七成自动爆开：一圈同色光环扩散 + 星点四散 → 落定成对应表情（出红睁大眼、出蓝闭着眼、
 * 出金和出紫半睁）。
 *
 * ⚠️ **参考视频不能直接当动画**（老板问过）：它是线性的、分不了四个走向，演不了擦拭这个交互，
 *    左上角还有「AI生成」水印。所以这里是**用他那六张图 + 我们自己画的光效**做的，
 *    节奏参照视频量出来的（那段 15 秒里光涨 5 秒、6.3 秒闪白），压到 App 上约 2.5 秒。
 *
 * ⚠️ **动画纯粹是表现层**（cc-logto_yxi 2026-09-06）：结果在 `POST /api/wish/draw` 返回那一刻
 *    就已经落库了。所以**擦不擦、擦没擦完，东西都已经是玩家的**——
 *    中途退出、杀进程、断网，再进收藏页都能看到。这里**绝不**在动画中途重发请求，
 *    也**绝不**把"擦完"当成领取的条件。
 *
 * ⚠️ **颜色要在落定之前就说话**（drop-effect.md 第一条）：擦开的那一块立刻是这一档的颜色，
 *    悬念在"擦"的过程里，不在最后一瞬。
 * ⚠️ **落定那一帧必须干净**：光环和星点在落定前收完，不留残影（STYLE.md）。
 */
object WishReveal {

    /** 一档一个颜色。⚠️ 只用来选颜色 —— 语义按 `kind` 判（`rarity` 是奖池里的展示名，以后会改） */
    fun colorOf(rarity: String): Color = when {
        rarity.contains("金") || rarity.equals("gold", true) -> Color(0xFFF5C451)
        rarity.contains("红") || rarity.equals("red", true) -> Color(0xFFFF7A8A)
        rarity.contains("紫") || rarity.equals("purple", true) -> Color(0xFFB98AE8)
        else -> Color(0xFF7FC8F5)
    }

    /** 出货那一下的短片（老板 2026-09-06：「还是用视频」——静态图加我们画的光效他觉得不够连贯、也没声音） */
    fun clipOf(rarity: String): Int = when {
        rarity.contains("金") || rarity.equals("gold", true) -> app.yxi.R.raw.yx_wish_gold
        rarity.contains("红") || rarity.equals("red", true) -> app.yxi.R.raw.yx_wish_red
        rarity.contains("紫") || rarity.equals("purple", true) -> app.yxi.R.raw.yx_wish_purple
        else -> app.yxi.R.raw.yx_wish_blue
    }

    /** 舞台底色 = **短片自己的底色**。擦完那张就是短片第 1 帧，两半接得严丝合缝。 */
    val Paper = Color(0xFFE3E3E3)

    /** 结算卡的底色。片尾淡到它，换卡那一刻两边同色，看不出接缝。 */
    val Stage = Color(0xFF0B0D12)

    /**
     * 每档短片的时长（秒，实测）。**越稀有揭晓段越长**，通用蓄力段四档一样。
     * ⚠️ 片尾自带 0.45 秒淡入结算卡底色（`0xFF0B0D12`），所以放完那一刻直接换成结算卡
     * **看不出接缝** —— 不用在 App 里叠两层弹窗做交叉淡入。改片子就要改这张表。
     */
    fun lenOf(rarity: String): Float = when {
        rarity.contains("金") || rarity.equals("gold", true) -> 7.27f
        rarity.contains("红") || rarity.equals("red", true) -> 7.30f
        rarity.contains("紫") || rarity.equals("purple", true) -> 6.87f
        else -> 6.43f
    }
}

/**
 * 擦星星的那块画面。[rarity] 决定颜色和落定用哪张图；[onDone] 在爆开收尾后回调一次
 * （用来接着往下走，比如显示"获得了什么"）。
 *
 * [autoAfter] 秒之后没人擦就自己擦开 —— 连抽十次不该逼玩家擦十次，也不能让不想擦的人卡住。
 */
@Composable
fun WipeReveal(
    rarity: String,
    modifier: Modifier = Modifier,
    autoAfter: Float = 3.5f,
    onDone: () -> Unit = {},
) {
    val idle = ImageBitmap.imageResource(app.yxi.R.drawable.yx_wish_idle)
    val motion = !reducedMotion()   // ⚠️ 只有一处读这个系统开关（[reducedMotion]）
    val clipLen = WishReveal.lenOf(rarity)

    // 擦到哪儿了：把画面切成 12×12 的格子，手指扫过就点亮一格。
    // 用格子不用路径：省内存、好算覆盖率，而且天然有"擦开的形状"。
    val grid = remember(rarity) { BooleanArray(GRID * GRID) }
    var wiped by remember(rarity) { mutableFloatStateOf(0f) }
    var burstAt by remember(rarity) { mutableStateOf(-1f) }
    // ⚠️ **收尾计时的零点是 [playAt]（播放器真的 start 那一刻），不是擦完那一刻。**
    //    起播要先 attach → onSurfaceTextureAvailable → prepare → start，实测 0.1–0.4 秒；
    //    拿擦完当零点就会短算这一段，短片每次被砍掉尾巴 —— 片尾那段淡入结算卡底色的收尾
    //    正好被切掉，接缝原样回来，而且越慢的机器越明显。
    var playAt by remember(rarity) { mutableFloatStateOf(-1f) }
    var bail by remember(rarity) { mutableStateOf(false) }   // 放不了 / 切后台 / 用户点了跳过
    var now by remember(rarity) { mutableFloatStateOf(0f) }

    LaunchedEffect(rarity) {
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            now = (System.nanoTime() - t0) / 1e9f
            // 没人擦也别卡住：到点自己擦开
            if (burstAt < 0f && (wiped >= 0.62f || now >= autoAfter || !motion)) burstAt = now
            if (bail) break                                              // 放不了就立刻收，别让人干等
            if (playAt >= 0f && now - playAt > clipLen) break            // 正常：从真的开播算满一整条
            if (burstAt >= 0f && now - burstAt > clipLen + 2.5f) break    // 兜底：起播卡死也不能永远等
        }
        onDone()
    }

    // ⚠️ 整屏底色也要跟着片尾一起淡到结算卡底色。只淡中间那块视频的话，
    //    换成结算卡的那一刻四周会从浅灰"啪"地变黑 —— 接缝就露在这一圈上。
    //    写在 drawBehind 里而不是 composition 里：`now` 每帧都变，读在 composition 里会整页重组。
    Box(
        modifier.drawBehind {
            // ⚠️ 窗口按**实测**对齐视频里烤好的那段淡入（`[clipLen-0.60, clipLen-0.134]`，
            //    末尾那 0.134 秒是定格的黑帧）。差 0.13 秒就会在视频黑透之后、四周还是中灰，
            //    黑片子外面框一圈灰边 —— 新的接缝。零点同样用 [playAt]。
            val t0 = if (playAt >= 0f) playAt else return@drawBehind drawRect(WishReveal.Paper)
            // ⚠️ **宁可比视频晚，别比它早**。实测（22 秒录屏）四周按 clipLen-0.60 起淡时，
            //    比画面里真正变暗早了约 0.3 秒 —— 屏幕上是「黑框套着一块还亮着的视频」，
            //    比"浅色的边"更扎眼。往后挪、并且收得更快，让它在片尾之前追上。
            val tail = (((now - t0) - (clipLen - 0.40f)) / 0.28f).coerceIn(0f, 1f)
            // ⚠️ **不用 lerp**：`Color` 的 lerp 走 Oklab，而视频里的淡入是编码域线性（ffmpeg fade），
            //    两条曲线中段差七八级。叠一层 alpha 走 source-over，正好跟视频同域。
            drawRect(WishReveal.Paper)
            if (tail > 0f) drawRect(WishReveal.Stage.copy(alpha = tail))
        }
            // ⚠️ 短片期间**点一下要能跳过**。结算卡本来就能点着关,唯独这七秒不能跳的话,
            //    最坏路径是「擦 3.5 秒 + 放 7.3 秒」全程不可跳、返回键还是空实现,
            //    解码失败时更是对着一张静止图干等。
            .pointerInput(rarity) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (burstAt >= 0f) bail = true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
    Box(
        // 舞台按短片的真实比例排（608×1000）—— TextureView 是**拉伸填充**的，
        // 比例不对视频就会被压扁。擦拭那张图是同一段视频的第 1 帧，比例天生一致。
        Modifier.fillMaxWidth(0.92f).aspectRatio(608f / 1000f).pointerInput(rarity) {
            awaitEachGesture {
                val d = awaitFirstDown(requireUnconsumed = false)
                fun mark(p: Offset) {
                    val gx = (p.x / size.width * GRID).toInt()
                    val gy = (p.y / size.height * GRID).toInt()
                    // 手指有粗细：一次点亮周围一圈，不然要擦很久
                    for (dy in -1..1) for (dx in -1..1) {
                        val x = gx + dx; val y = gy + dy
                        if (x in 0 until GRID && y in 0 until GRID) grid[y * GRID + x] = true
                    }
                    wiped = grid.count { it } / (GRID * GRID).toFloat()
                }
                mark(d.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                    if (!ch.pressed) break
                    mark(ch.position)
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val burst = if (burstAt < 0f) -1f else now - burstAt
            // 呼吸：整张图非常轻微地起伏（±1.2%），加上一点上下浮动 —— 静止的立绘看着像卡住了
            // 开播之后就别摆了：视频是不缩放的，底图还在 ±1.2% 呼吸会在首帧到位时跳一下。
            // 停在开播那一刻最安全 —— 那一帧视频已经盖住底图，跳变看不见。
            val breath = if (!motion || playAt >= 0f) 0f else kotlin.math.sin(now * 1.6f)
            val sc = 1f + 0.012f * breath
            val dy = -size.height * 0.008f * breath
            // 短片是竖构图，铺满整个舞台（跟视频同比例，见上面的 aspectRatio）
            val w2 = size.width * sc; val h2 = size.height * sc
            val ox = (size.width - w2) / 2f; val oy = (size.height - h2) / 2f + dy
            drawImage(
                idle,
                dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                dstSize = IntSize(w2.toInt(), h2.toInt()),
            )
            // ⚠️ **擦的过程里不能有任何品质色**（老板 2026-09-07：「擦拭之前颜色和光晕不能变，
            //    擦拭完立马变」）。底下铺的是那张**中性的灰黑星星**（短片第 1 帧），
            //    没擦到的格子蒙一层**半透明**的霜 —— 星星始终看得见，擦开只是把霜抹掉。
            //    ⚠️ 霜**不能是不透明的**：那样擦之前是一片空白灰，
            //    老板要的是「擦拭的前面是灰黑星星」（2026-09-07），看不见就不算。
            //    颜色第一次出现是在短片开播那一帧，手心的光晕直接就是这一抽的品质色。
            val cw = size.width / GRID; val ch = size.height / GRID
            if (burst < 0f) {
                // ⚠️ **攒成一个 Path 画一次**，别逐格 drawRect：格子边界是浮点、
                //    Compose 的 Paint 默认开抗锯齿，相邻两格各覆盖半个像素，
                //    合成后边界比格心深一档 → 画面上浮出 11 竖 11 横的浅色格纹。
                //    单个 Path 是先合并覆盖率再混合，共享边天然没缝，也更省。
                val frost = Path()
                for (i in grid.indices) {
                    if (grid[i]) continue
                    val gx = (i % GRID) * cw; val gy = (i / GRID) * ch
                    frost.addRect(Rect(gx, gy, gx + cw, gy + ch))
                }
                drawPath(frost, WishReveal.Paper.copy(alpha = FROST))
                // 星星上一点微光，告诉你这儿可以擦。**白的，不带品质色。**
                if (motion) {
                    val pulse = 0.35f + 0.25f * kotlin.math.sin(now * 3f)
                    drawCircle(Color.White.copy(alpha = .22f * pulse), size.minDimension * .12f, starCenter(size))
                    // 星尘：慢慢往上飘的小点，飘出去就从下面回来（靠取模循环，不用管理粒子列表）
                    val c0 = starCenter(size)
                    for (i in 0 until 7) {
                        val ph = (now * 0.22f + i * 0.137f) % 1f
                        val a = (i * 51f) * (Math.PI / 180f).toFloat() + now * 0.3f
                        val rr = size.minDimension * (0.10f + 0.16f * ph)
                        val x = c0.x + kotlin.math.cos(a) * rr
                        val y = c0.y + kotlin.math.sin(a) * rr * 0.5f - size.height * 0.22f * ph
                        val al = (if (ph < 0.2f) ph / 0.2f else (1f - ph) / 0.8f).coerceIn(0f, 1f)
                        drawCircle(Color.White.copy(alpha = .75f * al), size.minDimension * 0.008f, Offset(x, y))
                    }
                }
            }
        }
        // 擦完就放这一档的短片（有动效、有声音）。⚠️ 视频只负责"演"，
        //    结果早在 draw 接口返回时就落库了 —— 播不播、播完没播完都不影响归属。
        // ⚠️ **不再垫白闪、也不再延迟挂载**：延迟挂载会让「擦完立刻亮」变成
        //    「擦完等一下才亮」，正是老板挑掉的毛病；垫白闪则是平白多闪一次。
        //    ⚠️ 别把"擦拭图和短片首帧一模一样"当成论据 —— **实测不一样**
        //    （逐像素 mean|Δ| 5–9，平坦背景角上差约 11 级），是有一点跳变的。
        //    留着它是因为「擦完立刻变」本来就要一下变化，不是因为它无缝。
        if (burstAt >= 0f) RarityClip(
            WishReveal.clipOf(rarity),
            Modifier.matchParentSize(),
            // 真的开播了才起表 —— 收尾时刻由这一下决定，不是由擦完那一下
            onStart = { if (playAt < 0f) playAt = now },
            onFail = { bail = true },
        )
        if (burstAt < 0f) Text(
            t("擦一擦"),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF5A5A5A),   // 固定深灰（5.37:1）：这块底是短片自己的浅灰，不跟皮肤走
        )
    }
    }
}

private const val GRID = 12

/**
 * 霜的浓度：**必须半透明** —— 星星要一直看得见，擦只是把霜抹掉。
 * ⚠️ 实测过对比度（星星最暗处 `#4F4242` vs 底 `#E2E0DF`，原图 7.29:1）：
 *    0.72 → **1.55:1**（日光下基本看不见，等于又变回空白灰）· 0.60 → 1.92:1 · **0.50 → 2.35:1**。
 *    老板要的是「擦拭的前面是灰黑星星」，看不见就不算数，所以取 0.50。
 */
private const val FROST = 0.50f

/** 星星在她怀里，大约在画面中偏下 —— 光环和星点都从这儿发 */
private fun starCenter(size: Size) = Offset(size.width * 0.42f, size.height * 0.52f)

/**
 * 放一段出货短片（`res/raw` 里的 mp4，带声音）。
 *
 * 用 **TextureView + MediaPlayer**，不引第三方播放器：七秒的小视频，
 * ExoPlayer 那一套（几百 KB 依赖 + 一堆生命周期）不值当。
 * ⚠️ 不用 `VideoView`：它是 SurfaceView，在 Dialog 里会有层级问题（盖不住 / 被盖住）。
 *
 * ⚠️ **[onStart] 必须真的在 `start()` 之后回调**：外面那套收尾计时是拿它当零点的。
 *    拿"擦完那一刻"当零点会短算 —— 起播（解码器实例化 + prepare）实测 0.1–0.4 秒，
 *    低端机更久，于是短片每次都被砍掉尾巴，**片尾那段淡入结算卡底色的收尾正好被切掉**，
 *    好不容易消掉的接缝原样回来，而且专挑慢机型出现。
 * ⚠️ 用 `prepareAsync()` 不用 `MediaPlayer.create()`：后者在主线程里同步 prepare，
 *    那 0.1–0.4 秒是**卡住主线程**的（擦拭的手感当场就掉帧）。
 * ⚠️ 播放失败（解码不了、文件坏了）就**立刻**回调 [onFail]，让外面马上收 ——
 *    绝不让人对着一张静止图干等七秒。东西早就是玩家的了，不差这一段表演。
 * ⚠️ 切后台要停：Activity 只是 stopped 时 View 不 detach，
 *    `onSurfaceTextureDestroyed` 不触发，声音会在后台继续放七秒。
 */
@Composable
private fun RarityClip(
    resId: Int,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onFail: () -> Unit = {},
) {
    // ⚠️ `AndroidView` 的 factory 只跑一次，闭包捕获的是**第一次组合**的那个 lambda。
    //    不用 rememberUpdatedState 包一层的话，这两个回调是死引用。
    val started by rememberUpdatedState(onStart)
    val failed by rememberUpdatedState(onFail)
    val hold = remember { arrayOfNulls<Any>(2) }   // [0]=MediaPlayer [1]=Surface，给生命周期那边收

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                runCatching { (hold[0] as? android.media.MediaPlayer)?.release() }
                runCatching { (hold[1] as? android.view.Surface)?.release() }
                hold[0] = null; hold[1] = null
                failed()          // 回到前台时直接是结算卡，不留一段放不动的画面
            }
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { c ->
            android.view.TextureView(c).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        val player = android.media.MediaPlayer()
                        hold[0] = player
                        val surface = android.view.Surface(st)
                        hold[1] = surface
                        runCatching {
                            c.resources.openRawResourceFd(resId).use { fd ->
                                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                            }
                            player.setSurface(surface)
                            player.setOnPreparedListener { it.start(); started() }
                            player.setOnErrorListener { _, _, _ -> failed(); true }
                            player.prepareAsync()
                        }.onFailure {
                            runCatching { player.release() }
                            runCatching { surface.release() }
                            hold[0] = null; hold[1] = null
                            failed()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        // ⚠️ Surface 得自己 release：`MediaPlayer.release()` 不管它，
                        //    这里 return true 释放的是 SurfaceTexture、不是这层包装。
                        runCatching { (hold[0] as? android.media.MediaPlayer)?.release() }
                        runCatching { (hold[1] as? android.view.Surface)?.release() }
                        hold[0] = null; hold[1] = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
    )
}
