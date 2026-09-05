package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * 云曦 Q 版 —— 小管家页顶上那个会动的她。
 *
 * 素材是 4 张**整图**（`res/drawable-nodpi/yunxi_q_*.webp`，rembg 抠底），没有分层，
 * 所以做法是**「整图物理 + 姿势切换」**，不做伪分层眨眼、不做瞳孔追手（做不到就不假装，STYLE.md §0）：
 *  · 姿势 = 状态：挥手（进页问候）· 捧云（待机）· 举杖（办事中）· 抱星坐云（22–6 点困倦）。切换交叉淡入 350ms。
 *  · 呼吸：整体 scaleY 0.985–1.015，锚点在脚底，2.6 秒一呼一吸（跟输入框那颗药丸同一个节拍）；困倦时 4 秒。
 *  · 悬浮：举杖那张加正弦上下浮动。
 *  · 看向手指：按下 / 拖动时整体朝手指方向微倾 ±3° + 微平移，松手弹簧回位。
 *  · 戳一下：弹一下（spring 过冲）+ 把第几下告诉外面（连戳台词由页面选）；长按另有回调。
 *  · 拖拽：跟手，松手弹回。
 *  · 减弱动效：循环全停、不弹不倾，只保留姿势切换和气泡。
 *
 * ⚠️ 表情图是**可选**的：放了 `yunxi_q_blink` / `yunxi_q_smile` / `yunxi_q_surprised`（同捧云姿势）就自动接上 ——
 *    有闭眼图才有随机眨眼，有笑脸才有被戳后的笑，有惊讶才有被拖时的惊讶。没放就什么都不装。
 * ⚠️ 她头上戴的是用户当前装备的头像框（[drawAvatarFrame]），换装即时反映。
 */
enum class YunxiPose { Wave, Idle, Cast, Sleep }

@Composable
fun YunxiPet(
    modifier: Modifier = Modifier,
    /** 外部要求的姿势（办事中传 [YunxiPose.Cast]）；null = 自动：进页挥手 2 秒 → 待机；22–6 点困倦 */
    pose: YunxiPose? = null,
    /** 头顶气泡里的话；空 = 不显示 */
    line: String = "",
    /** 被戳了第几下（1.5 秒内连续计数），页面按它挑台词 */
    onTap: (count: Int) -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val scope = rememberCoroutineScope()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    // ── 姿势 ──────────────────────────────────────────────────────────────
    var greeted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(2000); greeted = true }
    val hour = remember { java.time.LocalTime.now().hour }
    val autoPose = when {
        !greeted -> YunxiPose.Wave
        hour >= 22 || hour < 6 -> YunxiPose.Sleep
        else -> YunxiPose.Idle
    }
    val shown = pose ?: autoPose

    // 可选表情图：有就用，没有就 0（不装）
    fun optional(name: String) = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    val blinkRes = remember { optional("yunxi_q_blink") }
    val smileRes = remember { optional("yunxi_q_smile") }
    val surprisedRes = remember { optional("yunxi_q_surprised") }
    var blinking by remember { mutableStateOf(false) }
    var smiling by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 只在待机时眨：3–6 秒一次，闭 90ms。⚠️ 条件放 effect 里面而不是外面：composable 调用不能带 if
    LaunchedEffect(shown, blinkRes, motion) {
        if (blinkRes == 0 || !motion) return@LaunchedEffect
        while (shown == YunxiPose.Idle) {
            delay(Random.nextLong(3000, 6000)); blinking = true; delay(90); blinking = false
        }
    }
    val res = when {
        shown == YunxiPose.Idle && dragging && surprisedRes != 0 -> surprisedRes
        shown == YunxiPose.Idle && smiling && smileRes != 0 -> smileRes
        shown == YunxiPose.Idle && blinking && blinkRes != 0 -> blinkRes
        else -> when (shown) {
            YunxiPose.Wave -> app.yxi.R.drawable.yunxi_q_wave
            YunxiPose.Idle -> app.yxi.R.drawable.yunxi_q_hold_cloud
            YunxiPose.Cast -> app.yxi.R.drawable.yunxi_q_wand
            YunxiPose.Sleep -> app.yxi.R.drawable.yunxi_q_sleep
        }
    }

    // ── 循环：呼吸 / 悬浮 / 光晕 ─────────────────────────────────────────
    val loop = rememberInfiniteTransition(label = "yunxi")
    val breath by loop.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (shown == YunxiPose.Sleep) 4000 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val floatT by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "float")
    val b = if (motion) breath else 0.5f
    val scaleY = 0.985f + 0.03f * b
    val floatY = if (motion && shown == YunxiPose.Cast) sin(floatT * 2f * Math.PI.toFloat()) * 6f else 0f

    // ── 手指：倾斜 / 拖拽 / 弹跳 ────────────────────────────────────────
    val tilt = remember { Animatable(0f) }          // 度
    val shiftX = remember { Animatable(0f) }        // px
    // 拖拽：拖的时候是普通状态直接加（不为每一帧起协程），松手时把余量交给弹簧回位
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val backX = remember { Animatable(0f) }
    val backY = remember { Animatable(0f) }
    val bounce = remember { Animatable(1f) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val soft = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.fillMaxWidth(0.72f).aspectRatio(1f)
                // 身后一团呼吸的云光：浅色皮肤淡蓝、深色皮肤更亮一点
                .drawBehind {
                    val glow = if (dark) Color(0xFFBFD8FF) else Color(0xFF9DBCF5)
                    drawCircle(
                        Brush.radialGradient(listOf(glow.copy(alpha = 0.10f + 0.12f * b), Color.Transparent), center = center, radius = size.minDimension * 0.55f),
                        radius = size.minDimension * 0.55f, center = center,
                    )
                }
                .pointerInput(motion) {
                    detectTapGestures(
                        onPress = { pos ->
                            if (motion) {
                                val dx = ((pos.x - size.width / 2f) / (size.width / 2f)).coerceIn(-1f, 1f)
                                scope.launch { tilt.animateTo(dx * 3f, soft) }
                                scope.launch { shiftX.animateTo(dx * 8.dp.toPx(), soft) }
                            }
                            tryAwaitRelease()
                            scope.launch { tilt.animateTo(0f, soft) }
                            scope.launch { shiftX.animateTo(0f, soft) }
                        },
                        onTap = {
                            val now = System.currentTimeMillis()
                            taps = if (now - lastTap < 1500) taps + 1 else 1
                            lastTap = now
                            onTap(taps)
                            if (motion) scope.launch {
                                bounce.snapTo(0.92f)
                                bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                            }
                            if (smileRes != 0) scope.launch { smiling = true; delay(1400); smiling = false }
                        },
                        onLongPress = { onLongPress() },
                    )
                }
                .pointerInput(motion) {
                    if (!motion) return@pointerInput
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            val x = dragX; val y = dragY; dragX = 0f; dragY = 0f
                            scope.launch { backX.snapTo(x); backX.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
                            scope.launch { backY.snapTo(y); backY.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
                        },
                        onDragCancel = { dragging = false; dragX = 0f; dragY = 0f },
                    ) { change, d ->
                        change.consume()
                        // 拖不远：越拉越沉，像被云托着
                        dragX = (dragX + d.x * 0.6f).coerceIn(-size.width * 0.25f, size.width * 0.25f)
                        dragY = (dragY + d.y * 0.6f).coerceIn(-size.height * 0.15f, size.height * 0.25f)
                    }
                }
                .graphicsLayer {
                    translationX = shiftX.value + dragX + backX.value
                    translationY = dragY + backY.value + floatY
                    rotationZ = tilt.value
                    scaleX = bounce.value
                    this.scaleY = scaleY * bounce.value
                    transformOrigin = TransformOrigin(0.5f, 0.97f)   // 锚点在脚底，呼吸是「从脚往上长」
                },
        ) {
            // 减弱动效：姿势切换和气泡都瞬切（Compose 动画不读系统的时长缩放，得自己判）
            Crossfade(res, animationSpec = if (motion) tween(350) else snap(), label = "pose") { r ->
                Image(painterResource(r), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            }
            // 她头上戴用户当前的头像框（换装即时反映）
            val frame = Skins.frame(ctx)
            if (frame.kind != Skins.FrameKind.NONE) Box(
                Modifier.fillMaxWidth().aspectRatio(1f).drawBehind {
                    val c = Offset(size.width * 0.5f, size.height * 0.3f)
                    drawAvatarFrame(frame, c, size.width * 0.27f, 3.dp.toPx(), if (motion) floatT * 360f else 0f)
                },
            )
        }
        // 头顶气泡
        AnimatedVisibility(
            line.isNotBlank(),
            enter = if (motion) fadeIn() + slideInVertically { it / 2 } else EnterTransition.None,
            exit = if (motion) fadeOut() else ExitTransition.None,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
                shadowElevation = 0.dp,
            ) {
                Text(
                    line, Modifier.padding(14.dp, 9.dp).fillMaxWidth(0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

