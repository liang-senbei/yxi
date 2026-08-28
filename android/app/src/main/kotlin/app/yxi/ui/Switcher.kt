package app.yxi.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Live
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.agent.SessionState
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

/**
 * 悬浮排列的会话切换 —— 像手机后台，一张张卡片横着排（决策 D18 / D17）。
 *
 * ⚠️ **上滑 = 归档，不杀 tmux 会话。**
 * 不可逆的操作绝不能是一个滑动手势 —— 手机上误滑太容易了。
 * 归档只是从这个列表里藏起来，服务器上那个会话一根毛都没动，随时能取消归档。
 * 真要杀会话：**长按 + 二次确认**。
 *
 * ⚠️ **视差要尊重系统的「移除动画」设置。**
 * 对前庭功能障碍的用户，视差会引发眩晕和恶心。系统里关了动画就一律不做位移和缩放。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Switcher(
    ssh: SshSession?,
    current: String?,
    hostId: String,
    onPick: (Session) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Session>>(emptyList()) }
    var archived by remember { mutableStateOf(Archive.get(ctx, hostId)) }
    var killing by remember { mutableStateOf<Session?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val shots = remember { mutableStateMapOf<String, String>() }
    val lives = remember { mutableStateMapOf<String, Live>() }
    val motion = remember { motionEnabled(ctx) }

    val list = remember(all, archived, showArchived) {
        if (showArchived) all.filter { it.name in archived }
        else all.filter { it.name !in archived }
    }
    val pager = rememberPagerState { list.size }

    LaunchedEffect(ssh) {
        val s = ssh ?: return@LaunchedEffect
        while (true) {
            runCatching { SessionProbe.snapshot(s) }.onSuccess { all = it }
            delay(5_000)
        }
    }

    // ⚠️ 只抓当前页和左右邻居的缩略图。全抓的话 20 个会话就是 20 次往返，
    // 每 5 秒一轮 —— 手机网络下这是自找的卡顿。
    LaunchedEffect(pager.currentPage, list, ssh) {
        val s = ssh ?: return@LaunchedEffect
        while (true) {
            for (i in (pager.currentPage - 1)..(pager.currentPage + 1)) {
                val name = list.getOrNull(i)?.name ?: continue
                runCatching { SessionProbe.peek(s, name, 14) }
                    .onSuccess { raw ->
                        shots[name] = raw.lines().filter { l -> l.isNotBlank() }.takeLast(9).joinToString("\n")
                        lives[name] = Live.parse(raw)
                    }
            }
            delay(3_000)
        }
    }

    // 打开时停在当前会话那张。
    // ⚠️ **只定位一次。** 原来键里带了 `list`，而会话列表每 5 秒刷新一次 ——
    // 于是你正翻着卡片，它每 5 秒把你拽回当前那张。表现是「怎么滑都滑不动」，
    // 很容易误判成手势没生效（我就先去查手势了）。
    var located by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        if (located || list.isEmpty()) return@LaunchedEffect
        val i = list.indexOfFirst { it.name == current }
        if (i >= 0) runCatching { pager.scrollToPage(i) }
        located = true
    }

    Box(
        // ⚠️ 整屏浮层要自己吃系统栏边距 —— 它不在 Scaffold 里面，
        // 不加的话标题和 ✕ 会画到状态栏上（第一版就是这样）
        Modifier.fillMaxSize()
            .background(Surface.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showArchived) t("归档的会话") else t("会话"),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                )
                if (archived.isNotEmpty()) {
                    Surface(
                        color = if (showArchived) SurfaceContainerHigh else SurfaceContainer, shape = Pill,
                        modifier = Modifier.clip(Pill).combinedClickable { showArchived = !showArchived },
                    ) {
                        Text(
                            t("归档 %d").format(archived.size), Modifier.padding(13.dp, 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (showArchived) Copper else Muted,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(color = SurfaceContainer, shape = Pill, modifier = Modifier.clip(Pill).combinedClickable(onClick = onDismiss)) {
                    Text("✕", Modifier.padding(15.dp, 7.dp), style = MaterialTheme.typography.labelLarge, color = Muted)
                }
            }

            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (showArchived) t("没有归档的会话") else t("没有会话"),
                        style = MaterialTheme.typography.bodyMedium, color = Dim,
                    )
                }
            } else {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 44.dp),
                    pageSpacing = 12.dp,
                ) { page ->
                    val s = list[page]
                    // 邻居缩小压暗、内容反向小幅位移 —— 三层不同速度的视差（D17）
                    val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
                    val near = (1f - off.coerceIn(0f, 1f))
                    val scale = if (motion) 0.86f + 0.14f * near else 1f
                    val dim = if (motion) 0.55f + 0.45f * near else 1f
                    SwitcherCard(
                        s = s,
                        shot = shots[s.name].orEmpty(),
                        live = lives[s.name] ?: Live.IDLE,
                        isCurrent = s.name == current,
                        scale = scale, dim = dim,
                        parallax = if (motion) (pager.currentPage - page + pager.currentPageOffsetFraction) * -28f else 0f,
                        archivedView = showArchived,
                        onTap = { onPick(s); onDismiss() },
                        onLong = { killing = s },
                        onSwipeUp = {
                            archived = if (s.name in archived) archived - s.name else archived + s.name
                            Archive.set(ctx, hostId, archived)
                        },
                    )
                }
                Text(
                    if (showArchived) t("上滑取消归档 · 长按结束会话") else t("上滑归档（不杀会话）· 长按结束会话"),
                    Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    // ⚠️ 杀会话是**不可逆**的，所以要长按 + 这一道确认，而且把后果写清楚
    killing?.let { s ->
        AlertDialog(
            onDismissRequest = { killing = null },
            title = { Text(t("结束会话 %s？").format(s.short)) },
            text = {
                Text(
                    t("会执行 tmux kill-session —— 那个会话里正在跑的东西**会被中断**，") +
                        t("没保存的内容没了，取消不了。\n\n") +
                        t("只是不想在列表里看见它的话，上滑归档就行，那个不动服务器。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton({
                    val n = s.name; killing = null
                    scope.launch { ssh?.let { runCatching { it.exec("tmux kill-session -t '$n'") } } }
                }) { Text(t("结束")) }
            },
            dismissButton = { TextButton({ killing = null }) { Text(t("取消")) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherCard(
    s: Session,
    shot: String,
    live: Live,
    isCurrent: Boolean,
    scale: Float,
    dim: Float,
    parallax: Float,
    archivedView: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    val sc by animateFloatAsState(scale, label = "scale")
    var dragY by remember { mutableStateOf(0f) }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { d ->
        dragY = (dragY + d).coerceAtMost(0f)
    }
    Box(
        Modifier.fillMaxSize().padding(vertical = 12.dp)
            .graphicsLayer {
                scaleX = sc; scaleY = sc
                alpha = dim
                translationY = dragY
            }
            // ⚠️ **必须用 `draggable` 而不是 `pointerInput { detectVerticalDragGestures }`。**
            // 后者会把**横向拖拽也吃掉** —— 卡片铺满整页，于是整个 pager 都滑不动了，
            // 而且不报任何错。摘掉它横滑立刻恢复，才定位到是它。
            // `draggable` 带方向锁：竖向归自己，横向让给上层的 pager。
            .draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                state = drag,
                onDragStopped = {
                    // 拖够一段才算 —— 轻轻蹭一下不该把卡片弄没了
                    if (dragY < -120f) onSwipeUp()
                    dragY = 0f
                },
            ),
    ) {
        Surface(
            color = if (isCurrent) SurfaceContainerHigh else SurfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).combinedClickable(onClick = onTap, onLongClick = onLong),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).background(dotColor(s.state), Pill))
                    Text(s.short, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    if (isCurrent) Text(t("当前"), style = MaterialTheme.typography.labelSmall, color = Copper)
                    else if (archivedView) Text(t("已归档"), style = MaterialTheme.typography.labelSmall, color = Dim)
                }
                // 优先显示「此刻在忙什么」：跑着 → ✽ 状态词（Teal）；刚跑完 → 用时；否则回落 detail/cwd
                val (liveText, liveColor) = when {
                    live.busy && !live.status.isNullOrBlank() -> "✽ " + live.status to Teal
                    !live.doneFor.isNullOrBlank() -> t("刚跑完 · %s").format(live.doneFor) to Copper
                    else -> s.detail.ifBlank { s.cwd } to Muted
                }
                Text(liveText, style = MaterialTheme.typography.labelSmall, color = liveColor, maxLines = 1)
                // 实时屏幕缩略 —— 直接画 capture-pane 的文本。
                // 比截图便宜得多，而且**认得出是哪个会话**靠的本来就是文字内容。
                Surface(
                    color = SurfaceContainerLowest, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        // 内容比卡片慢一拍 → 纵深感（D17 的第三层）
                        .graphicsLayer { translationY = parallax },
                ) {
                    // ⚠️ 缩略图要**不折行、超出就裁掉**：折行之后每一行都错位，
                    // 那就不再是「那个会话长什么样」了，认不出来。
                    Text(
                        shot.ifBlank { "…" },
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = Mono, fontSize = 8.sp, lineHeight = 10.sp,
                        ),
                        color = Dim,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/** ⚠️ `@Composable`：配色跟着风格走（[app.yxi.ui.Skin]），要读当前 Palette。 */
@androidx.compose.runtime.Composable
private fun dotColor(st: SessionState) = when (st) {
    SessionState.NeedsYou -> Amber
    SessionState.Working -> Teal
    // ⚠️ 原来这里是写死的深灰 —— 浅色风格下它会糊在白底上看不见
    else -> SurfaceContainerHighest
}

/**
 * 系统里关了动画就别做视差。
 * ⚠️ 这不是「体贴」，是无障碍要求 —— 对前庭功能障碍的用户，视差会引发眩晕和恶心。
 */
private fun motionEnabled(ctx: Context): Boolean = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)

/** 归档只是本地的一个名单 —— **服务器上什么都没动**。 */
private object Archive {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    fun get(ctx: Context, hostId: String): Set<String> =
        p(ctx).getStringSet("archived:$hostId", emptySet()) ?: emptySet()
    fun set(ctx: Context, hostId: String, v: Set<String>) =
        p(ctx).edit().putStringSet("archived:$hostId", v).apply()
}
