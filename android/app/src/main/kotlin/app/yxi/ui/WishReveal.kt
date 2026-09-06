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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
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
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }

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
        modifier.aspectRatio(1f).pointerInput(rarity) {
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
            drawImage(idle, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            // 擦开的格子里画"出货那张"——**擦到哪儿，颜色就露到哪儿**（颜色在落定之前就说话）
            val cw = size.width / GRID; val ch = size.height / GRID
            if (burst < 0f) {
                for (i in grid.indices) {
                    if (!grid[i]) continue
                    val gx = (i % GRID) * cw; val gy = (i / GRID) * ch
                    clipRect(gx, gy, gx + cw, gy + ch) {
                        drawImage(done, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    }
                }
                // 星星上一点微光，告诉你这儿可以擦
                if (motion) {
                    val pulse = 0.35f + 0.25f * kotlin.math.sin(now * 3f)
                    drawCircle(tint.copy(alpha = .18f * pulse), size.minDimension * .12f, starCenter(size))
                }
            } else {
                // 爆开：光晕在**人物背后**（画在图之前），环和星点在前面。
                // ⚠️ 光晕要是盖在图上面，她整个人会被染成那一档的颜色 —— 实测出蓝时像刷了层蓝漆。
                if (motion) burstGlow(burst, tint, starCenter(size))
                drawImage(done, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                if (motion) burstFx(burst, tint, starCenter(size))
            }
        }
        if (burstAt < 0f) Text(
            t("擦一擦"),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f),
        )
    }
}

private const val GRID = 12
private const val BURST = 1.2f

/** 星星在她怀里，大约在画面中偏下 —— 光环和星点都从这儿发 */
private fun starCenter(size: Size) = Offset(size.width * 0.42f, size.height * 0.52f)

/**
 * 爆开的光：一圈扩散的环 + 一层薄光晕 + 八颗四散的星点。
 * ⚠️ **1.2 秒内全部收完**，落定那一帧不留任何飞着的东西（STYLE.md「落定那一帧必须干净」）。
 */
private fun DrawScope.burstFx(age: Float, c: Color, at: Offset) {
    val k = (age / BURST).coerceIn(0f, 1f)
    val ease = 1f - (1f - k) * (1f - k)
    val fade = 1f - k * k
    val unit = size.minDimension

    drawCircle(                                                                          // 扩散的环
        c.copy(alpha = .8f * fade), unit * (0.12f + 0.62f * ease), at,
        style = Stroke(width = unit * 0.012f * (1f - k) + 1f),
    )
    for (i in 0 until 8) {                                                               // 四散的星点
        val a = (i * 45f + 12f) * (Math.PI / 180f).toFloat()
        val d = unit * 0.62f * ease * (0.75f + 0.06f * i)
        val r = unit * 0.018f * (1f - k * 0.5f)
        val p = at + Offset(kotlin.math.cos(a) * d, kotlin.math.sin(a) * d)
        rotate(45f, p) { drawRect(c.copy(alpha = .85f * fade), Offset(p.x - r, p.y - r), Size(r * 2, r * 2)) }
    }
}


/** 爆开的光晕：画在人物**背后**的一团柔光，往外淡出（不是给她刷一层颜色） */
private fun DrawScope.burstGlow(age: Float, c: Color, at: Offset) {
    val k = (age / BURST).coerceIn(0f, 1f)
    val r = size.minDimension * (0.16f + 0.62f * (1f - (1f - k) * (1f - k)))
    drawCircle(
        androidx.compose.ui.graphics.Brush.radialGradient(
            0f to c.copy(alpha = .55f * (1f - k)),
            0.55f to c.copy(alpha = .22f * (1f - k)),
            1f to Color.Transparent,
            center = at, radius = r,
        ),
        r, at,
    )
}
