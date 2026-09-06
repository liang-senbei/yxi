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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
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

    /** 舞台底色：取自短片自己的纸感背景，这样"擦"的那半（透明底立绘）和"放"的那半（视频）接得上 */
    val Paper = Color(0xFFE1DAD7)

    fun artOf(rarity: String): Int = when {
        rarity.contains("金") || rarity.equals("gold", true) -> app.yxi.R.drawable.yx_wish_gold
        rarity.contains("红") || rarity.equals("red", true) -> app.yxi.R.drawable.yx_wish_red
        rarity.contains("紫") || rarity.equals("purple", true) -> app.yxi.R.drawable.yx_wish_purple
        else -> app.yxi.R.drawable.yx_wish_blue
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
    val ctx = LocalContext.current
    val idle = ImageBitmap.imageResource(app.yxi.R.drawable.yx_wish_idle)
    val done = ImageBitmap.imageResource(WishReveal.artOf(rarity))
    val tint = WishReveal.colorOf(rarity)
    val motion = !reducedMotion()   // ⚠️ 只有一处读这个系统开关（[reducedMotion]）

    // 擦到哪儿了：把画面切成 12×12 的格子，手指扫过就点亮一格。
    // 用格子不用路径：省内存、好算覆盖率，而且天然有"擦开的形状"。
    val grid = remember(rarity) { BooleanArray(GRID * GRID) }
    var wiped by remember(rarity) { mutableFloatStateOf(0f) }
    var burstAt by remember(rarity) { mutableStateOf(-1f) }
    var now by remember(rarity) { mutableFloatStateOf(0f) }

    LaunchedEffect(rarity) {
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            now = (System.nanoTime() - t0) / 1e9f
            // 没人擦也别卡住：到点自己擦开
            if (burstAt < 0f && (wiped >= 0.62f || now >= autoAfter || !motion)) burstAt = now
            if (burstAt >= 0f && now - burstAt > BURST) break
        }
        onDone()
    }

    Box(
        // 舞台按短片的比例排（540×878）：视频才不会被拉扁；立绘是方的，居中等比放进去，
        // 上下那点留白正好落在纸底色上，两半接得住。
        modifier.aspectRatio(540f / 878f).pointerInput(rarity) {
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
            val breath = if (!motion) 0f else kotlin.math.sin(now * 1.6f)
            val sc = 1f + 0.012f * breath
            val dy = -size.height * 0.008f * breath
            // 立绘是正方形：按宽度铺满、居中，上下留白（舞台比它高）
            val side = size.width * sc
            val w2 = side; val h2 = side
            val ox = (size.width - w2) / 2f; val oy = (size.height - h2) / 2f + dy
            drawImage(
                idle,
                dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                dstSize = IntSize(w2.toInt(), h2.toInt()),
            )
            // 擦开的格子里画"出货那张"——**擦到哪儿，颜色就露到哪儿**（颜色在落定之前就说话）
            val cw = size.width / GRID; val ch = size.height / GRID
            if (burst < 0f) {
                for (i in grid.indices) {
                    if (!grid[i]) continue
                    val gx = (i % GRID) * cw; val gy = (i / GRID) * ch
                    clipRect(gx, gy, gx + cw, gy + ch) {
                        drawImage(
                            done,
                            dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                            dstSize = IntSize(w2.toInt(), h2.toInt()),
                        )
                    }
                }
                // 星星上一点微光，告诉你这儿可以擦
                if (motion) {
                    val pulse = 0.35f + 0.25f * kotlin.math.sin(now * 3f)
                    drawCircle(tint.copy(alpha = .18f * pulse), size.minDimension * .12f, starCenter(size))
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
        // 爆开：放这一档的短片（有动效、有声音）。⚠️ 视频只负责"演"，
        //    结果早在 draw 接口返回时就落库了 —— 播不播、播完没播完都不影响归属。
        // 一记白闪接缝：短片是从中段切出来的，跟擦完那一帧对不上。
        // **闪一下再切**是最老实的遮法 —— 参考视频自己在 6.3 秒也是这么干的。
        if (burstAt >= 0f && motion) {
            val fa = ((now - burstAt) / 0.22f).coerceIn(0f, 1f)
            if (fa < 1f) Canvas(Modifier.matchParentSize()) {
                drawRect(Color.White.copy(alpha = (1f - fa) * 0.9f))
            }
        }
        // ⚠️ 白闪之后才挂播放器：AndroidView 是真的子 View，盖在 Compose 画的东西上面，
        //    白闪要是跟它同时存在就会被压住。**先闪 0.18 秒、再挂视频**，顺便给解码器准备的时间。
        if (burstAt >= 0f && now - burstAt > 0.18f) RarityClip(
            WishReveal.clipOf(rarity),
            Modifier.matchParentSize(),
            onEnd = { /* 时长由外面的 LaunchedEffect 统一收尾 */ },
        )
        if (burstAt < 0f) Text(
            t("擦一擦"),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f),
        )
    }
}

private const val GRID = 12
private const val BURST = 1.9f      // 四段短片里最长的那段

/** 星星在她怀里，大约在画面中偏下 —— 光环和星点都从这儿发 */
private fun starCenter(size: Size) = Offset(size.width * 0.42f, size.height * 0.52f)

/**
 * 放一段出货短片（`res/raw` 里的 mp4，带声音）。
 *
 * 用 **TextureView + MediaPlayer**，不引第三方播放器：这是一段 1.5 秒的小视频，
 * ExoPlayer 那一套（几百 KB 依赖 + 一堆生命周期）不值当。
 * ⚠️ 不用 `VideoView`：它是 SurfaceView，在 Dialog 里会有层级问题（盖不住 / 被盖住）。
 *
 * ⚠️ **静音开关跟着打击音那套走不合适**，这里跟随系统媒体音量即可；
 *    播放失败（解码不了、文件坏了）就**什么都不放**，直接当播完 —— 绝不卡住出货流程，
 *    因为东西早就是玩家的了。
 */
@Composable
private fun RarityClip(resId: Int, modifier: Modifier = Modifier, onEnd: () -> Unit = {}) {
    val ctx = LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { c ->
            android.view.TextureView(c).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    private var mp: android.media.MediaPlayer? = null

                    // ⚠️ Surface 得自己 release：`MediaPlayer.release()` 不管它，
                    //    `onSurfaceTextureDestroyed` 里 return true 释放的是 SurfaceTexture、不是这层包装。
                    private var sf: android.view.Surface? = null

                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        // ⚠️ 先把播放器接在手上再 try：create 成功但 setSurface/start 抛了的话，
                        //    整段 runCatching 会返回 null，那个已经创建出来的播放器就再也没人 release 了。
                        val player = runCatching { android.media.MediaPlayer.create(c, resId) }.getOrNull()
                        mp = player
                        if (player == null) { onEnd(); return }
                        val surface = android.view.Surface(st)
                        sf = surface
                        runCatching {
                            player.setSurface(surface)
                            player.setOnCompletionListener { onEnd() }
                            player.start()
                        }.onFailure {
                            // 放不了就当放完，别把出货卡住
                            runCatching { player.release() }
                            runCatching { surface.release() }
                            mp = null; sf = null; onEnd()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        runCatching { mp?.release() }; mp = null
                        runCatching { sf?.release() }; sf = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
    )
}
