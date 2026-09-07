package app.yxi.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.yxi.agent.Rhythm
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin
import app.yxi.ui.rhythm.Hit
import app.yxi.ui.rhythm.HitFx
import app.yxi.ui.rhythm.HitFxs
import app.yxi.ui.rhythm.Named
import app.yxi.ui.rhythm.Rng
import app.yxi.ui.rhythm.ShatterFx
import app.yxi.ui.rhythm.Shatters
import app.yxi.ui.rhythm.StageGlow
import app.yxi.ui.rhythm.WildBg
import app.yxi.ui.rhythm.SwipeMarks
import app.yxi.ui.rhythm.SwipeNote
import app.yxi.ui.rhythm.Tile
import app.yxi.ui.rhythm.Words
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.em

/**
 * 活动 ·「云曦节拍」—— 四轨下落式音游（老板 2026-09-06：想要 Phigros / Orzmic 那种）。
 *
 * 三个状态：选曲 → 打谱面 → 结算。规则和取舍见 `Yxi_Entertainment/design/rhythm-prd.md`。
 *
 * ⚠️ **时间一律取自播放器的音频时钟**（[audioClock]），不自己数帧：
 *    UI 帧率会掉、会跳，音频不会。自己数拍的音游几十秒后必然音画分家。
 * ⚠️ 判定不准别让玩家以为是自己手残（STYLE.md「不骗人」）：结算页会把这局的**平均偏差**摆出来，
 *    偏得多就直接给一个「自动校准」的按钮。
 */
@Composable
fun RhythmScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<Pair<Rhythm.Song, String>?>(null) }
    var demo by remember { mutableStateOf(false) }             // 演示：自动完美，不计成绩
    var result by remember { mutableStateOf<Done?>(null) }
    var synced by remember { mutableIntStateOf(0) }
    // 规则和最好成绩以服务端为准（分数不是客户端说了算）；拿不到就用本地那份，照样能玩
    LaunchedEffect(Unit) { Rhythm.sync(ctx); synced++ }

    BackHandler(enabled = playing != null || result != null) { playing = null; result = null }

    // ── 横屏（老板 2026-09-06 看了 Phigros 之后定的：音游要横版）──
    // **打谱面和结算页都横着**，回到选曲页才转回来 —— 一局打完手机还横在手里，
    // 结果页却竖过来，那一下很难受。
    // ① 还原写在 onDispose 里：**怎么离开都还原**（返回键、手势、切后台、被杀），
    //    还原成 UNSPECIFIED 而不是硬写 PORTRAIT，免得跟以后的横屏页打架。
    // ② **不要**去 Manifest 给 Activity 加 screenOrientation，那会影响所有页面。
    // ③ ⚠️ 依赖 MainActivity 的 `android:configChanges="orientation|screenSize|…"`：
    //    有它转屏才不重建 Activity，正在打的这一局才不会被清掉。这一页 rememberSaveable 用量是 0，
    //    哪天有人删了那行 configChanges，转屏的一瞬间整局游戏会丢干净。删之前先来改这里。
    val activity = remember(ctx) {
        generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    val landscape = playing != null || result != null
    DisposableEffect(activity, landscape) {
        val oldOri = activity?.requestedOrientation
        val w = activity?.window
        val c = w?.let { androidx.core.view.WindowInsetsControllerCompat(it, it.decorView) }
        val oldCut = w?.attributes?.layoutInDisplayCutoutMode
        if (landscape) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            if (android.os.Build.VERSION.SDK_INT >= 28) w?.attributes = w.attributes?.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            c?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (landscape) {
                activity?.requestedOrientation = oldOri ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                c?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                if (android.os.Build.VERSION.SDK_INT >= 28 && oldCut != null) {
                    w?.attributes = w.attributes?.apply { layoutInDisplayCutoutMode = oldCut }
                }
            }
        }
    }

    val r = result
    val p = playing
    when {
        r != null -> ResultCard(r.chartId, r.result, r.elapsedMs, r.hits, auto = r.auto, onAgain = {
            val song = Rhythm.songs(ctx).first { it.id == r.chartId.substringBefore('_') }
            result = null; demo = r.auto; playing = song to r.chartId.substringAfter('_')
        }, onBack = { result = null; synced++ })                     // 结算页整屏通铺：不要 Scaffold 的内边距

        p != null -> GameBoard(p.first, p.second, auto = demo, onDone = { id, res, ms, hits ->
            if (!demo) Rhythm.saveBest(ctx, id, res)                 // 演示不算成绩（也不上报）
            playing = null; result = Done(id, res, ms, hits, demo)
        }, onQuit = { playing = null })                              // 打谱面同理，整屏通铺

        else -> SongList(synced, onPick = { s, d -> demo = false; playing = s to d }, onDemo = { s, d -> demo = true; playing = s to d }, modifier = modifier)
    }
}

/** 一局打完的结果：谱面 id · 本地算的成绩 · 用时 · 判定序列（上报给服务端重算） */
private data class Done(val chartId: String, val result: Rhythm.Result, val elapsedMs: Int, val hits: String, val auto: Boolean = false)

// ── 选曲 ────────────────────────────────────────────────────────────────────
@Composable
private fun SongList(synced: Int, onPick: (Rhythm.Song, String) -> Unit, onDemo: (Rhythm.Song, String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var listTick by remember { mutableIntStateOf(0) }
    val songs = remember(synced, listTick) { Rhythm.songs(ctx) }
    // 关卡联网（老板 09-07）：进来先拉一次公网清单，拉到就刷新列表；拉不到静默用本地
    LaunchedEffect(Unit) { Rhythm.refreshInBackground(ctx) { if (it) listTick++ } }   // 不绑页面生死：页面走了也要把谱下完
    val scope = rememberCoroutineScope()
    val downloading = remember { mutableStateMapOf<String, Float>() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(t("云曦节拍"), Modifier.padding(20.dp, 18.dp, 20.dp, 4.dp), style = MaterialTheme.typography.headlineMedium)
        Text(
            t("跟着拍子打音块。曲子有我们自己写的，也有魔王魂的免费曲（署名在曲名下）。"),
            Modifier.padding(20.dp, 0.dp, 20.dp, 14.dp),
            style = MaterialTheme.typography.bodyMedium, color = Muted,
        )
        // 音块说明（老板 09-07：「有哪几种方块，最好在云曦节拍里标注一下」）
        Row(Modifier.padding(20.dp, 6.dp, 20.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(
                Triple(Rhythm.Kind.TICK, "点", "到线点一下"), Triple(Rhythm.Kind.SLIDE, "长按", "按住到尾巴"),
                Triple(Rhythm.Kind.TRACE, "拖", "到线时按着就算"), Triple(Rhythm.Kind.SWIPE, "划", "到线时往箭头方向划"),
            ).forEach { (k, name, how) ->
                Column(Modifier.weight(1f)) {
                    Box(Modifier.width(34.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(noteColor(k)))
                    Text(t(name), style = MaterialTheme.typography.labelMedium)
                    Text(t(how), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
        songs.forEach { s ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp),
            ) {
                Column(Modifier.padding(16.dp, 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(s.zh), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(t("%d BPM · %d 秒").format(s.bpm, s.seconds), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    // 外来曲子的署名：授权条款要求的原话，原样显示（design/music/licenses/）
                    if (s.credit.isNotBlank()) Text(s.credit, style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(10.dp))
                    val ready = remember(s, listTick, downloading[s.id]) { Rhythm.ready(ctx, s) }
                    if (!ready) {
                        // 清单独有的曲子：先下音频（一两 MB），下完这张卡就变成正常的难度按钮
                        val p = downloading[s.id]
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = p == null) {
                                downloading[s.id] = 0f
                                scope.launch {
                                    val ok = Rhythm.download(ctx, s) { downloading[s.id] = it }
                                    downloading.remove(s.id); if (ok) listTick++
                                }
                            },
                        ) {
                            Column(Modifier.padding(14.dp, 10.dp)) {
                                Text(if (p == null) "⤓ " + t("下载这首") else t("下载中 %d%%").format((p * 100).toInt()), style = MaterialTheme.typography.titleSmall)
                                Text(if (s.audioBytes > 0) "%.1f MB".format(s.audioBytes / 1048576.0) else t("曲子在公网上，下一次就好"), style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.diffs.forEach { d ->
                            val best = remember(synced) { Rhythm.best(ctx, "${s.id}_$d") }
                            Surface(
                                color = when (d) { "hard" -> Copper.copy(alpha = .14f); "frenzy" -> Color(0xFFE0457B).copy(alpha = .18f); "wild" -> Color(0xFF7B61FF).copy(alpha = .20f); else -> MaterialTheme.colorScheme.surfaceContainerHigh },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onPick(s, d) },
                            ) {
                                Column(Modifier.padding(14.dp, 10.dp)) {
                                    Text(t(Rhythm.diffName(d)), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        best?.let { "${it.rank} · ${it.score}" } ?: t("还没打过"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (best == null) Muted else Amber,
                                    )
                                }
                            }
                        }
                        // 演示（老板 09-07：「播放一个完美校准所有音符的视频」）：进游戏界面，音符到线自动完美，不计成绩
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onDemo(s, s.diffs.last()) },
                        ) {
                            Column(Modifier.padding(14.dp, 10.dp)) {
                                Text("▶ " + t("演示"), style = MaterialTheme.typography.titleSmall)
                                Text(t(Rhythm.diffName(s.diffs.last())) + " · " + t("全完美"), style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    }
                }
            }
        }
        // 手感自己调（老板 2026-09-06：「这些你都让我自己调节」）
        var tuning by remember { mutableStateOf(false) }
        Row(Modifier.padding(20.dp, 10.dp, 20.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Toggle(t("手感调节"), tuning) { tuning = !tuning }
        }
        if (tuning) FeelPanel()
        // 计分方式换了（加了连击倍率、满分从 110 万到约 210 万），服务端会把最好成绩清一次。
        // 不解释的话，玩家会以为自己的成绩莫名其妙没了。
        Text(
            t("计分方式换了：连击到 8 / 15 / 30 有加成，最好成绩重新算。已经领过的奖励不受影响。"),
            Modifier.padding(20.dp, 12.dp, 20.dp, 0.dp),
            style = MaterialTheme.typography.labelSmall, color = Muted,
        )
        val off = Rhythm.offsetMs(ctx)
        if (off != 0) Text(
            t("判定偏移 %d ms（自动校准过）").format(off),
            Modifier.padding(20.dp, 10.dp), style = MaterialTheme.typography.labelSmall, color = Muted,
        )
    }
}

/** 一个开关药丸：开着是实心，关着是描边。跟设置页那些开关一个意思，但这里要小、要在一行里 */
@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(100.dp),
        modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable { onClick() },
    ) {
        Text(
            (if (on) "· " else "") + label,
            Modifier.padding(14.dp, 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else Muted,
        )
    }
}

// ── 打谱面 ──────────────────────────────────────────────────────────────────
/** 四种音块的颜色（游戏里、图例里同一份） */
private fun noteColor(k: Rhythm.Kind): Color = when (k) {
    Rhythm.Kind.TICK -> Color(0xFFFFC08A)
    Rhythm.Kind.SLIDE -> Color(0xFF8AD4F5)
    Rhythm.Kind.TRACE -> Color(0xFFF5A8C8)
    Rhythm.Kind.SWIPE -> Color(0xFF9EE6A8)
}

private class Live(val chart: Rhythm.Chart) {
    var perfect = 0; var good = 0; var miss = 0
    var combo = 0; var maxCombo = 0
    /** 判定序列（P/G/M，按发生顺序）——上报给服务端算分用 */
    val seq = StringBuilder()
    /** 本地即时分数：按连击档位边打边加。服务端会用同一套规则重算，结算页以它为准 */
    var live = 0.0
    val errs = ArrayList<Float>()
    /** 每条轨下一个还没判的音符下标，省得每帧从头扫 */
    /** 轨用「全局轨号」g = 判定线 × LANES + 轨：多条判定线（老板 09-07）就是把轨的编号空间拉长，判定 / 按住 / 闪光的代码一行不用改 */
    val laneCount = chart.lineCount * Rhythm.LANES
    val next = IntArray(laneCount)
    val heldCount = IntArray(laneCount)          // 同一条轨可能不止一根手指；抬起一根不能把长按判死
    val flash = FloatArray(laneCount) { -9f }  // 每条轨最后一次判定的时刻（秒）
    val flashJudge = arrayOfNulls<Rhythm.Judge>(laneCount)
    var lastJudge: Rhythm.Judge? = null
    var lastJudgeAt = 0f
    /** 判定发生时喊一声：音效 + 震动挂在这儿，绘制不管这些 */
    var onJudge: ((Rhythm.Judge, Int, Rhythm.Kind, Float) -> Unit)? = null

    // ── 玩家造成的校准线倾斜（trace / swipe 打中时把线往那个方向带一下）──
    private var tiltDeg = 0f
    private var tiltAt = -9f

    /**
     * 打中一个 trace / swipe：把倾斜**朝这次的方向拉一把**（不是累加）。
     * 方向相反的两下挨着中，自然互相抵消，不会转飞。
     */
    fun tilt(now: Float, dir: Int) {
        tiltDeg = (tiltAt(now) * 0.55f + dir * 6f).coerceIn(-6f, 6f)   // 试验台：6°·摆动(1)
        tiltAt = now
    }

    /**
     * [now] 时刻还剩多少倾斜。
     * ⚠️ **按时间衰减，不按帧**（cc-Yxi 2026-09-06 提醒）：写成"每帧乘 0.9"的话，
     *    120Hz 手机上的衰减速度是 60Hz 模拟器的两倍 —— 我在模拟器上调好的 0.4 秒回正，
     *    到老板手机上就成了 0.2 秒，而且代码看着完全正确，根本查不出来。
     *    `exp(-dt/τ)`，τ=0.15 秒 ≈ 0.4 秒回正，60/90/120Hz 一个手感。
     */
    fun tiltAt(now: Float): Float {
        val dt = now - tiltAt
        if (dt < 0f || dt > 3f) return 0f
        // 试验台的弹簧回正（老板 09-06 定的 摆动=1 回正=2.7）：exp(-dt/(0.32/2.7))·cos(9·dt) —— 甩过去、弹两下、回正
        return tiltDeg * kotlin.math.exp(-dt / (0.32f / 2.7f)) * kotlin.math.cos(dt * 9f)
    }

    val byLane: List<List<Rhythm.Note>> = (0 until laneCount).map { g -> chart.notes.filter { it.line * Rhythm.LANES + it.lane == g } }
    /** 演示模式（老板 09-07 的「播放」）：长按当作一直按着 */
    var autoHold = false
    /** 这条轨或相邻一条上有手指（轨窄了，判定放宽到 ±1） */
    fun heldNear(lane: Int): Boolean { val b = lane / Rhythm.LANES * Rhythm.LANES; return (maxOf(b, lane - 1)..minOf(b + Rhythm.LANES - 1, lane + 1)).any { heldCount[it] > 0 } }   // 不跨线

    fun hit(j: Rhythm.Judge, at: Float, lane: Int = -1, kind: Rhythm.Kind = Rhythm.Kind.TICK) {
        if (lane in 0 until laneCount) { flash[lane] = at; flashJudge[lane] = j }
        seq.append(when (j) { Rhythm.Judge.PERFECT -> 'P'; Rhythm.Judge.GOOD -> 'G'; else -> 'M' })
        onJudge?.invoke(j, lane, kind, at)
        val per = Rhythm.rules.base / chart.units.coerceAtLeast(1)
        when (j) {
            // ⚠️ 倍率按**打这一下时**的连击算（连击先加再乘：第 8 下就享受 ×1.2）
            Rhythm.Judge.PERFECT -> { perfect++; combo++; live += per * Rhythm.multAt(combo) }
            Rhythm.Judge.GOOD -> { good++; combo++; live += per * Rhythm.rules.goodFactor * Rhythm.multAt(combo) }
            Rhythm.Judge.MISS -> { miss++; combo = 0 }
        }
        maxCombo = maxOf(maxCombo, combo)
        lastJudge = j; lastJudgeAt = at
    }

    fun result(): Rhythm.Result {
        val acc = Rhythm.accuracy(chart.units, perfect, good)
        val med = if (errs.isEmpty()) 0 else errs.sorted()[errs.size / 2].roundToInt()
        return Rhythm.Result(
            // ⚠️ 用**边打边累计的那个分**（含连击倍率），不是 Rhythm.score() 那条老公式。
            //    用老公式的话：游戏里 201 万 → 结算页一瞬间掉到 110 万 → 服务端回话又跳回 211 万。
            //    "两个数对不上"正是加倍率时最该防的事（审查抓到的）。
            currentScore(), acc, Rhythm.rank(acc),
            perfect, good, miss, maxCombo, med,
        )
    }
}

/**
 * 舞台上跟判定无关、每帧都变的东西：爆点 / 碎裂 / 竖线 / 底光节奏 / 无线时刻。
 * ⚠️ 主线程裸对象，**不进 composition**（每帧变的状态进了组合就是每帧重组整页，#13）。
 * 写在判定回调、读在 Canvas 的绘制 lambda，两边都在 UI 线程。
 */
private class StageState {
    class Burst(val at: Float, val lane: Int, val kind: Rhythm.Kind, val perfect: Boolean, val seed: Float, val fx: Named<HitFx>, val scale: Float, val yk: Float = 1f)
    class Shat(val at: Float, val lane: Int, val kind: Rhythm.Kind, val seed: Float, val fx: ShatterFx, val yk: Float = 1f)
    class VLine(val at: Float, val lane: Int, val jitter: Float, val yk: Float = 1f)
    /** 下一次判定的特效画在 judgeY × 这个系数处（1 = 线上）。狂热演示提前爆：音符还在半空就炸（老板 09-07） */
    var judgeYk = 1f
    val bursts = ArrayList<Burst>(); val shatters = ArrayList<Shat>(); val vlines = ArrayList<VLine>()
    var lastHitAt = -9f; var lastTierAt = -9f
    // 无线时刻（老板：只在最高档可能出现；线闪烁后消失、全屏皆可校准；10~13 秒；一局最多一次）
    var freeOn = false; var freeUsed = false; var freeStart = 0f; var freeUntil = 0f
    fun freeLive(t: Float) = freeOn && t >= freeStart + FREE_FLICKER && t < freeUntil
    val rnd = java.util.Random()
    fun pickHit() = HitFxs.all[rnd.nextInt(HitFxs.all.size)]
    fun pickShatter() = Shatters.all[rnd.nextInt(Shatters.all.size)].fx

    companion object {
        const val FREE_FLICKER = 0.8f
        /** 九款出自五个人之手，尺度按各组自报的撑满直径归一到 ≈4.6u（规格 §4.4） */
        val HIT_SCALE = mapOf("快门" to .92f, "六边框" to .86f, "三角翻转" to .86f, "均衡器" to .95f, "方粒" to .86f, "火花" to .86f, "螺旋尘" to .84f, "聚爆" to .86f, "碎环" to .96f)
        fun hitEnvelope(k: Float) = if (k < 0.15f) 1f else Math.pow((1f - (k - 0.15f) / 0.85f).toDouble(), 1.2).toFloat()
        val VLINE_P = floatArrayOf(.30f, .50f, .70f, .90f)
    }
}

@Composable
private fun GameBoard(
    song: Rhythm.Song,
    difficulty: String,
    auto: Boolean,
    onDone: (String, Rhythm.Result, Int, String) -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val chart = remember(song, difficulty) { Rhythm.load(ctx, song.id, difficulty) }
    val live = remember(chart) { Live(chart).also { it.autoHold = auto } }
    val frenzy = chart.difficulty == "frenzy"
    val stage = remember(chart) { StageState() }
    val offset = remember { Rhythm.offsetMs(ctx).toFloat() }
    var now by remember { mutableFloatStateOf(-3f) }        // 秒；负数 = 倒计时
    val startedAt = remember { System.currentTimeMillis() }
    var combo by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(3) }
    var missAt by remember { mutableFloatStateOf(-9f) }      // 只有 Miss 弹字；每一下都弹太吵（规格 §1.3）
    var praise by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var score by remember { mutableIntStateOf(0) }
    var freeShow by remember { mutableStateOf(false) }   // 无线时刻那行字：stage.freeOn 不是 Compose state，改了不会重组（审查 P1）
    // ⚠️ 舞台**固定深色**，不跟浅色/深色主题走；颜色全部写死常量（#14：Copper 是槽位名不是颜色名）。
    //    四种音块的颜色 = 老板定的淡橙 / 浅蓝 / 樱粉 / 浅绿（规格 §1.1）。
    val ink = Color(0xFFE8EDF5)
    val kindColor = { k: Rhythm.Kind -> noteColor(k) }
    val hitPerfect = Color(0xFFF3E3B8); val hitGood = Color(0xFF9FCBF0)
    val missColor = Color(0xFFE5484D)
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val press = remember { FloatArray(live.laneCount) { -9f } }
    val view = LocalView.current
    val sfx = remember { RhythmSfx(ctx) }
    DisposableEffect(sfx) { onDispose { sfx.release() } }
    val soundVol = if (Rhythm.soundOn(ctx)) Rhythm.soundVol(ctx) else 0f
    val hapticLv = if (Rhythm.hapticOn(ctx)) Rhythm.haptic(ctx) else 0
    val fxScale = Rhythm.fxScale(ctx)
    val lineScale = Rhythm.lineScale(ctx)
    val flashOn = Rhythm.flashOn(ctx)                        // 癫狂难度的闪屏 / 抖动：用户可关（光敏）
    // 下落时长：谱面里写了就听谱面的（老板：按关卡和难度定），老谱没写才用手感面板那个值
    val approach = chart.approach ?: Rhythm.approach(ctx)

    // 判定回调：声音 + 震动 + 舞台特效（爆点 / 碎裂 / 竖线 / 跳档 / 无线时刻），全部由「判定」触发
    DisposableEffect(live, soundVol, hapticLv) {
        live.onJudge = { j, lane, kind, at ->
            sfx.play(kind, j, soundVol)
            if (j == Rhythm.Judge.MISS) {
                missAt = at
            } else {
                view.hapticTick(hapticLv, j == Rhythm.Judge.PERFECT)
                val r = stage.rnd
                stage.lastHitAt = at
                if (lane in 0 until live.laneCount) {
                    stage.bursts += StageState.Burst(at, lane, kind, j == Rhythm.Judge.PERFECT, r.nextFloat(), stage.pickHit(), 1f, stage.judgeYk)
                    if (stage.bursts.size > 40) stage.bursts.removeAt(0)
                    stage.shatters += StageState.Shat(at, lane, kind, r.nextFloat(), stage.pickShatter(), stage.judgeYk)
                    if (stage.shatters.size > 30) stage.shatters.removeAt(0)
                }
                val tier = Rhythm.tiers.count { live.combo >= it.combo }
                Rhythm.tierWord(live.combo)?.takeIf { it.isNotBlank() }?.let { praise = it to System.currentTimeMillis(); stage.lastTierAt = at }
                // 无线时刻：最高档每一下 35% 的机会，一局只一次
                if (!stage.freeUsed && Rhythm.tiers.isNotEmpty() && tier == Rhythm.tiers.size && r.nextFloat() < 0.35f) {   // 最高档才有（档位数以服务端下发为准）
                    stage.freeUsed = true; stage.freeOn = true; stage.freeStart = at; freeShow = true
                    stage.freeUntil = at + StageState.FREE_FLICKER + 10f + r.nextFloat() * 3f
                    praise = "Free" to System.currentTimeMillis()
                }
                // 竖向校准线：概率按档位走 30 → 50 → 70 → 90%
                if (lane in 0 until live.laneCount && r.nextFloat() < StageState.VLINE_P[minOf(3, tier)]) {
                    stage.vlines += StageState.VLine(at, lane, r.nextFloat() * 20f - 10f, stage.judgeYk)
                    if (stage.vlines.size > 20) stage.vlines.removeAt(0)
                }
            }
        }
        onDispose { live.onJudge = null }
    }

    val mp = remember {
        runCatching {
            if (song.remoteAudio) MediaPlayer().apply { setDataSource(Rhythm.audioFile(ctx, song).path); prepare() }   // 公网下载的曲子（关卡联网）
            else MediaPlayer.create(ctx, song.raw)
        }.getOrNull()
    }
    DisposableEffect(mp) { onDispose { mp?.let { runCatching { it.stop() }; it.release() } } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mp) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) { mp?.let { runCatching { it.pause() } }; onQuit() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (mp == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("这首曲子放不出来，先换一首。"), style = MaterialTheme.typography.bodyLarge)
            TextButton(onQuit) { Text(t("返回")) }
        }
        return
    }

    // 有手指按着的轨（trace 不用点：落线时按着就算，规格 §1.1）
    val anyPointer = remember { intArrayOf(0) }

    // 音频时钟：每帧读播放位置，两次读数之间用单调时钟补插值
    LaunchedEffect(chart) {
        var base = 0; var baseAt = 0L
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            val wall = (System.nanoTime() - t0) / 1e9f
            if (wall < 3f) {
                now = wall - 3f
                val c = (3f - wall).toInt() + 1
                if (c != countdown) countdown = c
                continue
            }
            if (countdown != 0) countdown = 0
            if (!mp.isPlaying && base == 0) { runCatching { mp.start() }; baseAt = System.nanoTime() }
            val pos = runCatching { mp.currentPosition }.getOrDefault(base)
            if (pos != base) { base = pos; baseAt = System.nanoTime() }
            val pred = base / 1000f + (System.nanoTime() - baseAt) / 1e9f
            now = if (abs(pred - now) > .25f) pred else maxOf(now, pred)
            // trace：这条轨上有手指按着（或无线时刻里有任何手指）就吃掉窗口里的 trace
            val free = stage.freeLive(now)
            for (l in 0 until live.laneCount) if (live.heldNear(l) || (free && anyPointer[0] > 0)) {
                if (hitLane(live, l, now, offset, free) { it.kind == Rhythm.Kind.TRACE }) { combo = live.combo; score = live.currentScore() }
            }
            if (auto) {                                      // 演示（老板 09-07：「掉到校准线就自动校准」）：到点即完美，不看手指
                val head = now + offset / 1000f
                for (l in 0 until live.laneCount) {
                    val lanes = live.byLane[l]; var i = live.next[l]
                    while (i < lanes.size && lanes[i].judged != null) i++
                    if (i >= lanes.size) continue
                    val n = lanes[i]
                    if (frenzy && stage.freeLive(now)) {
                        // 狂热演示的「半空提前爆」只在无线时刻（老板 09-07 二次：「应该只有校准线消失才可以随便校准」）：线在的时候一律到线再判
                        val lead = (0.15f + 0.45f * n.seed) * approach
                        if (head >= n.t - lead) {
                            stage.judgeYk = (1f - (n.t - head) / approach).coerceIn(0.05f, 1f)
                            n.judged = Rhythm.Judge.PERFECT; live.errs += 0f; live.hit(Rhythm.Judge.PERFECT, now, l, n.kind)
                            stage.judgeYk = 1f
                            if (n.kind == Rhythm.Kind.SWIPE || n.kind == Rhythm.Kind.TRACE) live.tilt(now, if (n.dir < 0) -1 else 1)
                            combo = live.combo; score = live.currentScore()
                        }
                    } else if (head >= n.t) {
                        if (hitLane(live, l, now, offset, false) { true }) {
                            if (n.kind == Rhythm.Kind.SWIPE || n.kind == Rhythm.Kind.TRACE) live.tilt(now, if (n.dir < 0) -1 else 1)
                            combo = live.combo; score = live.currentScore()
                        }
                    }
                }
            }
            judgeMisses(live, now, offset) { combo = live.combo; score = live.currentScore() }
            if (stage.freeOn && now >= stage.freeUntil + StageState.FREE_FLICKER) { stage.freeOn = false; freeShow = false }
            // 结束：最后一个音符过了 2.5 秒**而且**曲子放到接近结尾（或播放器自己停了）。
            // ⚠️ 只看最后一个音符不行：外来曲子结尾淡出、谱面只在起音够强处放音符，最后一个音符可能在 77 秒、
            //    曲子 94 秒 —— 提前结算的话 elapsedMs < durationMs × 0.9，服务端判 too_fast「没打完不算成绩」（E2E 抓到的）。
            val lastNote = chart.notes.lastOrNull()?.t ?: 0f
            if ((now > lastNote + 2.5f && now > song.seconds - 0.8f) || (base > 0 && !mp.isPlaying)) {
                onDone(chart.id, live.result(), (System.currentTimeMillis() - startedAt).toInt(), live.seq.toString())
                return@LaunchedEffect
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(Color(0xFF0B0D12)).pointerInput(chart) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (auto) return@awaitEachGesture      // 演示：只看，不吃手指
                // 场地画在线的坐标系里（规格 §5）：手指位置先反变换回线的坐标系再分轨、再算划动方向 ——
                // 线立着的时候「左右滑」就是屏幕上的上下滑。
                // 多线：这根手指归离它最近的那条**在场的**判定线（屏幕上点到线段的距离），整个手势都跟着这条线
                // 癫狂难度的镜头（zoom / 拉伸 / 转 / 抖）套在整个场地外面：手指坐标先反变换出镜头，再归线、再分轨
                fun unCamera(p: Offset): Offset {
                    val fx = chart.stageAt(now)
                    if (chart.stage.isEmpty()) return p
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val (shx, shy) = shakeAt(now, fx.shake, h, motion && flashOn)
                    val ox = p.x - (w / 2f + shx); val oy = p.y - (h / 2f + shy)
                    val rad = -fx.spin * (Math.PI / 180f).toFloat()
                    val rx = ox * kotlin.math.cos(rad) - oy * kotlin.math.sin(rad); val ry = ox * kotlin.math.sin(rad) + oy * kotlin.math.cos(rad)
                    return Offset(rx / (fx.zoom * fx.sx) + w / 2f, ry / (fx.zoom * fx.sy) + h / 2f)
                }
                val downPos = unCamera(down.position)
                val lineIdx = run {
                    var best = 0; var bestD = Float.MAX_VALUE
                    for (k in 0 until chart.lineCount) {
                        if (chart.lineAlphaAt(now, k) <= 0f) continue
                        val lp = linePose(chart, k, live, now, lineScale, size.width.toFloat(), size.height.toFloat())
                        val rad = -lp.deg * (Math.PI / 180f).toFloat()
                        val ox = downPos.x - lp.cx; val oy = downPos.y - lp.cy
                        val d = kotlin.math.abs(ox * kotlin.math.sin(rad) + oy * kotlin.math.cos(rad)) / lp.sTravel   // 手指到线的法向距离（线坐标系里）
                        if (d < bestD) { bestD = d; best = k }
                    }
                    best
                }
                val laneBase = lineIdx * Rhythm.LANES
                fun toLocal(pos0: Offset): Offset {
                    val pos = unCamera(pos0)
                    val lp = linePose(chart, lineIdx, live, now, lineScale, size.width.toFloat(), size.height.toFloat())   // ⚠️ 必须和画的那边同一份，不然点的和看的错位
                    val ly = size.height * 0.78f
                    val rad = -lp.deg * (Math.PI / 180f).toFloat()
                    val ox = pos.x - lp.cx; val oy = pos.y - lp.cy
                    return Offset(
                        (ox * kotlin.math.cos(rad) - oy * kotlin.math.sin(rad)) / lp.sLane + size.width / 2f,
                        (ox * kotlin.math.sin(rad) + oy * kotlin.math.cos(rad)) / lp.sTravel + ly,
                    )
                }
                fun laneOf(pos: Offset): Int = laneBase + ((toLocal(pos).x / size.width) * Rhythm.LANES).toInt().coerceIn(0, Rhythm.LANES - 1)
                var lane = laneOf(down.position)
                android.util.Log.d("YxiRhythm", "down lane=$lane now=$now pos=${down.position}")
                live.heldCount[lane]++; anyPointer[0]++
                press[lane] = now
                val free = stage.freeLive(now)
                // 按下吃 tick / slide 的头 / trace（按着就算，点下去当然也算）；swipe 要滑
                hitNear(live, lane, now, offset, free) { it.kind != Rhythm.Kind.SWIPE }
                    .also { if (it) { combo = live.combo; score = live.currentScore() } }
                val l0 = toLocal(down.position)
                // swipe 判定（老板 09-07：「左滑右滑没有真正被触发」）——原来只认「落下 0.45 秒内划过 3% 屏宽、一次触摸只认一回」，
                // 按住等音符再划、或者手指不抬连着左右扫，都不算。改成看**最近 0.18 秒的位移**：什么时候划都行，
                // 换方向立刻可以再触发，同方向 0.30 秒后可以再触发（一个动作只算一次）。
                // ⚠️ 轨迹存**屏幕坐标**，算位移时把最老那点用**当前**姿态重投影：线在转的时候手指不动、局部 x 也会漂
                //    （90° 过渡 0.18 秒能漂 90px > 门槛），存局部 x 会凭空触发 swipe（审查）。两端同一坐标系，漂移抵消。
                val trail = ArrayDeque<Pair<Float, Offset>>()         // (时刻, 屏幕坐标)
                trail.addLast(now to down.position)
                var lastSwipeAt = -9f; var lastSwipeDir = 0
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val l1 = toLocal(ch.position)
                    val dx = l1.x - l0.x; val dy = l1.y - l0.y
                    trail.addLast(now to ch.position)
                    while (trail.size > 1 && now - trail.first().first > 0.18f) trail.removeFirst()
                    val dxWin = l1.x - toLocal(trail.first().second).x
                    // 门槛 3% 屏宽（老板：放宽点）；立竖时局部 dx 被 1/sLane 放大，门槛也乘回去，屏幕上的手指距离不变（审查）
                    val thr = size.width * 0.03f / linePose(chart, lineIdx, live, now, lineScale, size.width.toFloat(), size.height.toFloat()).sLane
                    if (kotlin.math.abs(dxWin) > thr) {
                        val dir = if (dxWin > 0) 1 else -1
                        if (dir != lastSwipeDir || now - lastSwipeAt > 0.30f) {
                            val freeNow = stage.freeLive(now)
                            val ok = hitNear(live, lane, now, offset, freeNow) { it.kind == Rhythm.Kind.SWIPE && (freeNow || it.dir == 0 || it.dir == dir) }
                            android.util.Log.d("YxiRhythm", "swipe lane=$lane dx=${dxWin.toInt()} dir=$dir now=$now hit=$ok")
                            if (ok) { lastSwipeAt = now; lastSwipeDir = dir; combo = live.combo; score = live.currentScore(); live.tilt(now, dir) }   // 没中不占冷却（审查）
                            trail.clear(); trail.addLast(now to ch.position)   // 从头量：一个动作别触发两次
                        }
                    }
                    val moved = kotlin.math.abs(dx) > size.width * 0.02f || kotlin.math.abs(dy) > size.height * 0.04f
                    val cur = if (moved) laneOf(ch.position) else lane
                    if (cur != lane) {                     // 手指挪到别的轨：按着的状态跟着走（trace 靠这个）
                        live.heldCount[lane] = (live.heldCount[lane] - 1).coerceAtLeast(0)
                        lane = cur
                        live.heldCount[lane]++
                        press[lane] = now
                    }
                }
                live.heldCount[lane] = (live.heldCount[lane] - 1).coerceAtLeast(0)
                anyPointer[0] = (anyPointer[0] - 1).coerceAtLeast(0)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = now
            val W = size.width; val H = size.height
            val laneW = W / Rhythm.LANES
            val judgeY = H * 0.78f                                    // 线的坐标系里线永远在这儿
            val fx = chart.stageAt(t)                                 // 癫狂难度的舞台特效（别的难度全是默认值）
            val noteH = (H * 0.011f).coerceIn(6f * density, 11f * density) * fx.notes   // 薄片（老板选的），CSS 6~11px
            val noteW = W * 0.0811f * fx.notes                        // 原 0.052×1.2=0.0624，老板 09-07：沿线方向加长 30%；12 条轨每条 0.0833
            val bench = H / 580f                                      // 网页试验台是 580 CSS px 高：特效里的线宽 / 点半径按它缩放（port-hit 提醒）

            // ── 底光：连击越高越亮；每下小闪；跳档猛闪 + 琥珀 + 台阶（规格 §3）──
            val tierLevel = Rhythm.tiers.count { live.combo >= it.combo }
            val heatK = (live.combo / 30f).coerceAtMost(1f)
            val hitPulse = (1f - (t - stage.lastHitAt) / 0.20f).coerceIn(0f, 1f)   // 命中那一下底光闪：老板 09-07「之前说过的，不明显」→ 0.15→0.45、0.15s→0.20s
            val tierPulse = (1f - (t - stage.lastTierAt) / 0.6f).coerceIn(0f, 1f).let { it * it }
            val energy = chart.energyAt(t)
            val amount = 0.55f + 0.45f * heatK + 0.20f * tierLevel + 0.45f * hitPulse + 0.90f * tierPulse + 0.35f * energy
            if (fx.bg == 0 || !motion) with(StageGlow) { drawStageGlow(t, if (motion) amount else 0.55f, if (motion) tierPulse else 0f, 1.75f, if (motion) energy else 0f) }
            else with(WildBg) { drawWildBg(fx.bg, t, ((t * chart.bpm / 60f) % 1f + 1f) % 1f, energy) }
            val (shakeX, shakeY) = shakeAt(t, fx.shake, H, motion && flashOn)
            withTransform({                                           // 镜头：整个场地一起缩放 / 拉伸 / 转 / 抖（触摸那边 unCamera 反变换）
                translate(W / 2f + shakeX, H / 2f + shakeY)
                rotate(fx.spin, Offset.Zero)
                scale(fx.zoom * fx.sx, fx.zoom * fx.sy, Offset.Zero)
                translate(-W / 2f, -H / 2f)
            }) {

            for (lineK in 0 until chart.lineCount) {
            val lineAlpha = chart.lineAlphaAt(t, lineK)                // 出现窗口 × alpha 关键帧（闪烁）
            if (lineAlpha <= 0f) continue
            val lp = linePose(chart, lineK, live, t, lineScale, W, H)
            val deg = lp.deg; val lineCx = lp.cx; val lineCy = lp.cy
            val gBase = lineK * Rhythm.LANES                           // 这条线的全局轨号起点
            val su = kotlin.math.sqrt(lp.sLane * lp.sTravel)             // 特效用的等比系数（抵消场地的非等比缩放，见爆点处注释）
            val kx = su / lp.sLane; val ky = su / lp.sTravel
            withTransform({
                translate(lineCx, lineCy)
                rotate(deg, Offset.Zero)
                scale(lp.sLane, lp.sTravel, Offset.Zero)                 // 立竖时压轨道 / 拉飞行距离（见 linePose）
                translate(-W / 2f, -judgeY)
            }) {
                val over = maxOf(W, H) * 0.6f                         // 场地画得比屏幕大得多：转到任何角度都不露边
                // （轨与轨之间原来有条淡白线 —— 老板 09-07：不要了）

                // 按下反馈：极淡，只说「你按了」
                if (motion) for (l in 0 until Rhythm.LANES) {
                    val age = t - press[gBase + l]
                    if (age in 0f..0.12f) drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .06f * (1 - age / 0.12f))), startY = judgeY * 0.5f, endY = judgeY),
                        Offset(laneW * l, judgeY * 0.5f), Size(laneW, judgeY * 0.5f),
                    )
                }
                // 漏：那段线短暂变暗红
                for (l in 0 until Rhythm.LANES) {
                    val age = t - live.flash[gBase + l]
                    if (live.flashJudge[gBase + l] == Rhythm.Judge.MISS && age in 0f..0.35f) drawLine(
                        missColor.copy(alpha = .9f * (1 - age / 0.35f)), Offset(laneW * l + 8f, judgeY), Offset(laneW * (l + 1) - 8f, judgeY), 2.5f * bench,
                    )
                }

                // ── 判定线：纯白发丝 + 极窄柔光；命中那一瞬亮一下；无线时刻闪烁 → 消失 → 闪回 ──
                var lineA = lineAlpha
                if (stage.freeOn) {
                    lineA = if (!motion) 0.15f                                    // 减弱动效：不闪（12Hz 全亮全灭是光敏最忌讳的），固定暗一档
                    else if (t < stage.freeStart + StageState.FREE_FLICKER || t >= stage.freeUntil) (if (((t * 12f).toInt() % 2) == 1) 1f else 0.15f) else 0f
                }
                var glow = 0f
                for (l in 0 until Rhythm.LANES) { val a = t - live.flash[gBase + l]; if (a in 0f..0.14f && live.flashJudge[gBase + l] != Rhythm.Judge.MISS) glow = maxOf(glow, 1f - a / 0.14f) }
                if (lineA > 0f) {
                    drawLine(Color.White.copy(alpha = (.10f + .12f * glow) * lineA), Offset(-over, judgeY), Offset(W + over, judgeY), (8f + 10f * glow) * bench)
                    drawLine(Color.White.copy(alpha = .92f * lineA), Offset(-over, judgeY), Offset(W + over, judgeY), (2.2f + 0.8f * glow) * bench)
                }

                // ── 爆点（画在音符下面）：老板挑的九款随机 + 宿主层统一（尺度 / 淡出包络 / 起手白芯）──
                if (motion) {
                    stage.bursts.removeAll { t - it.at > 0.65f }
                    for (b in stage.bursts) {
                        val k = (t - b.at) / 0.6f
                        if (k < 0f || k > 1f || b.lane / Rhythm.LANES != lineK) continue
                        val cx = laneW * (b.lane % Rhythm.LANES) + laneW / 2f
                        val judgeY = judgeY * b.yk                          // 狂热演示：半空就爆
                        val col = if (b.perfect) hitPerfect else hitGood
                        val tint = mix(col, kindColor(b.kind), 0.35f)      // 爆点带一点音符自己的颜色
                        val u = maxOf(noteH * 2.6f, H * 0.03f) * fxScale * b.scale
                        val env = StageState.hitEnvelope(k)
                        // 场地在线立起来时是非等比缩放的（sLane / sTravel）。音符跟着压扁还行，爆点压扁就难看（老板 09-07）：
                        // 这里把非等比的部分抵消掉，只留一个等比系数 su = √(sLane·sTravel)，爆点在任何角度都是圆的
                        withAlphaLayer(env, Offset(cx - u * 4f * kx, judgeY - u * 4f * ky), Size(u * 8f * kx, u * 10f * ky)) {   // 下边多留 2u：火花落到 5u 以下会被裁（审查 P3）
                            withTransform({ translate(cx, judgeY); scale(bench * kx, bench * ky, Offset.Zero) }) {
                                with(b.fx.fx) { draw(Hit((u / 1.3f) * (StageState.HIT_SCALE[b.fx.name] ?: .9f) / bench, tint, b.perfect, noteH / bench), k, Rng(b.seed)) }
                                if (k < 0.08f) {                                   // 共用的起手白芯：九款同一个第一帧（同样不压扁）
                                    val f = 1f - k / 0.08f; val r = noteH * 0.9f * (0.6f + 0.4f * f) / bench
                                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .9f * f), Color.White.copy(alpha = .35f * f), Color.Transparent), Offset.Zero, r * 2.2f), r * 2.2f, Offset.Zero)
                                }
                            }
                        }
                    }
                }

                // ── 音符 ──
                val head = t + offset / 1000f
                val free = stage.freeLive(t)
                chart.notes.forEach { n ->
                    if (n.line != lineK) return@forEach
                    val dt = n.t - head
                    if (dt > approach || (n.judged != null && !n.hold)) return@forEach
                    if (n.judged != null && n.tailDone) return@forEach
                    if (dt < -0.4f && !n.hold) return@forEach
                    val y = judgeY * (1f - dt / approach)
                    val cx = laneW * n.lane + laneW / 2f
                    val c = kindColor(n.kind)
                    if (n.hold) {
                        val tailY = (judgeY * (1f - (n.t + n.dur - head) / approach)).coerceAtLeast(0f)
                        val bottom = minOf(y, judgeY)
                        if (bottom > tailY) {
                            val holding = n.judged != null && n.judged != Rhythm.Judge.MISS && (live.heldNear(gBase + n.lane) || live.autoHold || (free && anyPointer[0] > 0)) && head <= n.t + n.dur
                            val bw = noteW * 0.9f
                            drawRect(c.copy(alpha = if (n.judged == Rhythm.Judge.MISS) .10f else if (holding) .34f else .22f), Offset(cx - bw / 2, tailY), Size(bw, bottom - tailY))
                            drawRect(Color.White.copy(alpha = if (holding) .75f else .45f), Offset(cx - bw / 2 + .5f, tailY + .5f), Size(bw - 1f, bottom - tailY - 1f), style = Stroke(1.2f * bench))
                            if (holding && motion) drawHoldFx(n, head - n.t, cx, judgeY, tailY, bottom, bw, noteH, c, stage, t, fxScale)
                        }
                    }
                    if (n.judged == null && y >= -20f && y <= judgeY + noteH) {
                        if (n.kind == Rhythm.Kind.SWIPE) {
                            val mark = SwipeMarks.all[n.markIdx % SwipeMarks.all.size].fx
                            withTransform({ scale(bench, bench, Offset.Zero) }) {
                                with(mark) { draw(SwipeNote(cx / bench, y / bench, noteW / bench, noteH / bench, c, n.dir, judgeY / bench, laneW * n.lane / bench, laneW / bench), (t % 0.8f) / 0.8f, Rng(n.seed)) }
                            }
                        } else drawSliver(cx, y, noteW, noteH, c, n.kind == Rhythm.Kind.TRACE, bench)
                    }
                }

                // ── 竖向校准线：垂直判定线、±10° 偏转、白、0.28 秒淡出（按档位概率出现）──
                if (motion) {
                    stage.vlines.removeAll { t - it.at > 0.45f }
                    for (v in stage.vlines) {
                        val k = (t - v.at) / 0.42f
                        if (k < 0f || k > 1f || v.lane / Rhythm.LANES != lineK) continue
                        val cx = laneW * (v.lane % Rhythm.LANES) + laneW / 2f              // 被点中的那块的中心
                        val judgeY = judgeY * v.yk
                        val up = H * (0.22f + 0.10f * k); val down = H * 0.05f   // 往上抽长再淡掉
                        val a = (1f - k) * (1f - k * 0.5f)
                        rotate(v.jitter, Offset(cx, judgeY)) {
                            drawLine(Color.White.copy(alpha = .28f * a), Offset(cx, judgeY - up), Offset(cx, judgeY + down), 7f * bench, cap = StrokeCap.Round)   // 柔光
                            drawLine(Color.White.copy(alpha = .95f * a), Offset(cx, judgeY - up), Offset(cx, judgeY + down), 2.4f * bench, cap = StrokeCap.Round)
                        }
                    }
                    // ── 碎裂：音符本体散掉（老板挑的八款随机）──
                    stage.shatters.removeAll { t - it.at > 0.65f }
                    for (sh in stage.shatters) {
                        val k = (t - sh.at) / 0.6f
                        if (k < 0f || k > 1f || sh.lane / Rhythm.LANES != lineK) continue
                        val cx = laneW * (sh.lane % Rhythm.LANES) + laneW / 2f
                        val jy = judgeY * sh.yk
                        withTransform({ translate(cx, jy); scale(kx, ky, Offset.Zero); translate(-cx, -jy); scale(bench, bench, Offset.Zero) }) {   // 碎片也不压扁（绕音符中心抵消非等比）
                            with(sh.fx) { draw(Tile((cx - noteW / 2) / bench, (jy - noteH / 2) / bench, noteW / bench, noteH / bench, kindColor(sh.kind), sh.seed), k, Rng(sh.seed)) }
                        }
                    }
                }
            }

            }   // for lineK
            }   // 镜头
            // 闪屏（癫狂难度）：减弱动效时不闪
            if (motion && flashOn && fx.flash > 0f) drawRect(Color.White.copy(alpha = fx.flash * 0.85f), Offset.Zero, Size(W, H))
            // 无线时刻：四边一圈呼吸柔光（不转）
            if (stage.freeLive(t)) {
                val br = .10f + .06f * kotlin.math.sin(t * 6f)
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.White.copy(alpha = br)), Offset(W / 2f, H / 2f), maxOf(W, H) * 0.75f), Offset.Zero, Size(W, H))
            }
        }

        // ── HUD：进度条在顶边；退出左上；连击正中（评价词那套字）；分数右上；曲名 / 难度下两角 ──
        Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopStart)) {
            val k = (now / song.seconds.toFloat()).coerceIn(0f, 1f)
            drawRect(Color.White.copy(alpha = .18f), Offset(0f, 0f), Size(size.width, size.height))
            drawRect(Color.White.copy(alpha = .7f), Offset(0f, 0f), Size(size.width * k, size.height))
        }
        TextButton(onQuit, Modifier.align(Alignment.TopStart).padding(4.dp, 6.dp)) {
            Text(t("退出"), color = ink.copy(alpha = .7f), style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (combo >= 3) {
                Words.Bold(
                    "$combo", MaterialTheme.typography.displayMedium.fontSize,
                    Modifier.graphicsLayer {
                        val k = if (!motion) 1f else { val age = now - live.lastJudgeAt; if (age in 0f..0.10f) 1f + 0.06f * (1f - age / 0.10f) else 1f }
                        scaleX = k; scaleY = k
                    },
                )
                Text("COMBO", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.18.em), color = ink.copy(alpha = .5f))
                val mult = Rhythm.multAt(combo)
                if (mult > 1f) Words.Bold("×%.1f".format(mult), MaterialTheme.typography.titleMedium.fontSize, fill = Words.YELLOW)
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MeAvatar(30.dp)                                        // 老板 09-07：得分左边放自己的头像，跟得分差不多大
            Text("%07d".format(score), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light), color = ink.copy(alpha = .9f))
        }
        // 左下角英文名（老板 09-06：「左下角用英文，yxi…dancing」）；曲名挪到右下跟难度一起
        Text("Yxi Dancing Beat", Modifier.align(Alignment.BottomStart).padding(14.dp, 10.dp), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Light), color = ink.copy(alpha = .6f))
        Text(t(chart.zh) + " · " + t(Rhythm.diffName(difficulty)), Modifier.align(Alignment.BottomEnd).padding(14.dp, 10.dp), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Light), color = ink.copy(alpha = .6f))
        // Miss：红字一闪（每一下都弹字太吵，只弹漏）
        Box(Modifier.align(Alignment.Center).graphicsLayer {
            val age = now - missAt
            alpha = if (age < 0f || age > 0.45f) 0f else 1f - (age / 0.45f) * (age / 0.45f)
        }) { Words.Bold("Miss", MaterialTheme.typography.headlineMedium.fontSize, fill = Words.MISS, alpha = .85f) }
        // 无线时刻的字
        if (freeShow) Text(
            t("无线 · 点哪都算"), Modifier.align(Alignment.TopCenter).padding(top = if (combo >= 3) 96.dp else 60.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.em), color = ink.copy(alpha = .6f),
        )
        // 跳档评价词 / Free：老板的参考图那套字
        praise?.let { (w, at) -> PraiseOverlay(w, at, motion, Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(140.dp)) }
        LaunchedEffect(praise) { if (praise != null) { kotlinx.coroutines.delay(1200); praise = null } }
        if (countdown > 0) Words.Bold("$countdown", MaterialTheme.typography.displayLarge.fontSize, Modifier.align(Alignment.Center))
    }
}

/** 长按期间的持续特效（规格 §4.6）：呼吸光 · 涟漪 · 上飘光屑 · 体内光带 · 每 0.22s 复发小号爆点 */
private fun DrawScope.drawHoldFx(
    n: Rhythm.Note, ht: Float, cx: Float, judgeY: Float, tailY: Float, bottom: Float, bw: Float, h: Float, c: Color,
    stage: StageState, t: Float, fxScale: Float,
) {
    val br = .38f + .14f * kotlin.math.sin(ht * 25f)
    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = br.coerceIn(0f, 1f)), c.copy(alpha = 0f)), Offset(cx, judgeY), h * 3.2f), h * 3.2f, Offset(cx, judgeY))
    val rk = (ht % 0.35f) / 0.35f
    drawCircle(c.copy(alpha = .55f * (1f - rk)), h * 1.2f + rk * h * 4.5f, Offset(cx, judgeY), style = Stroke(1.4f * (size.height / 580f)))
    for (i in 0 until 8) {
        val ph = (ht * 1.4f + i * 0.137f) % 1f
        val x = cx + kotlin.math.sin(i * 7.3f + ht * 3f) * bw * 0.38f
        val y = judgeY - ph * minOf(judgeY - tailY, h * 12f)
        if (y < tailY) continue
        val sz = (1.5f + (i % 3)) * (size.height / 580f)
        drawRect(Color.White.copy(alpha = .75f * (1f - ph)), Offset(x - sz / 2, y - sz / 2), Size(sz, sz))
    }
    val fk = (ht * 1.1f) % 1f; val fy = bottom - fk * (bottom - tailY); val bh = h * 1.6f
    val top = maxOf(tailY, fy - bh)
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .28f * (1f - fk * 0.6f)), Color.Transparent), startY = fy + bh, endY = fy - bh),
        Offset(cx - bw / 2, top), Size(bw, minOf(bh * 2, bottom - top).coerceAtLeast(0f)),
    )
    if (n.lastPulse < 0f || ht - n.lastPulse >= 0.22f) {
        n.lastPulse = ht
        stage.bursts += StageState.Burst(t, n.line * Rhythm.LANES + n.lane, n.kind, false, stage.rnd.nextFloat(), stage.pickHit(), 0.6f)
        if (stage.bursts.size > 40) stage.bursts.removeAt(0)
    }
}

/** 薄片音符：同色光晕 → 本色圆角薄片 → 白芯 → 两头 ‹ › 尖角；trace 多三道抓握纹（规格 §4.2） */
private fun DrawScope.drawSliver(cx: Float, y: Float, w: Float, h: Float, c: Color, grip: Boolean, bench: Float) {
    drawRoundRect(c.copy(alpha = .35f), Offset(cx - w / 2 - h * .4f, y - h * .9f), Size(w + h * .8f, h * 1.8f), CornerRadius(h))
    drawRoundRect(c, Offset(cx - w / 2, y - h / 2), Size(w, h), CornerRadius(h / 2))
    drawRoundRect(Color.White.copy(alpha = .85f), Offset(cx - w / 2 + h * .35f, y - h * .22f), Size(w - h * .7f, h * .44f), CornerRadius(h * .22f))
    val sw = 1.6f * bench
    fun cap(x: Float, dir: Float) {
        drawLine(Color.White.copy(alpha = .95f), Offset(x + dir * h * .55f, y - h * .5f), Offset(x, y), sw, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = .95f), Offset(x, y), Offset(x + dir * h * .55f, y + h * .5f), sw, cap = StrokeCap.Round)
    }
    cap(cx - w / 2 - h * .35f, 1f); cap(cx + w / 2 + h * .35f, -1f)
    if (grip) for (g in -1..1) drawRect(Color.White.copy(alpha = .7f), Offset(cx + g * h * 1.2f - 0.8f * bench, y - h * .3f), Size(1.6f * bench, h * .6f))
}

/** 在一层里画、整层按 [alpha] 合成 —— 给特效套统一的淡出包络（Compose 没有 globalAlpha） */
private inline fun DrawScope.withAlphaLayer(alpha: Float, topLeft: Offset, size: Size, block: DrawScope.() -> Unit) {
    if (alpha >= 0.999f) { block(); return }
    val paint = androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha.coerceIn(0f, 1f) }
    val canvas = drawContext.canvas
    canvas.saveLayer(androidx.compose.ui.geometry.Rect(topLeft, size), paint)
    block()
    canvas.restore()
}

private fun mix(a: Color, b: Color, f: Float) = Color(
    a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f,
)

/** 评价词的生命：1.2 秒，k 用帧时钟推，只重组这一个小组件 */
/**
 * 判定线此刻在屏幕上的姿态：(角度, 中心 x, 中心 y)。画的一侧和手指反变换的一侧**必须**共用这一份（规格 §5）。
 * = 谱面编舞 poseAt + 试验台那两样「活气」（老板 09-06 定：谱面摆动 1.7 —— 慢摆 sin(0.45t)·2.2°、上下呼吸 sin(0.3t)·2% 屏高）+ 玩家甩出来的 tilt。
 * 角度不限幅：线想怎么转就怎么转。
 */
private class LinePose(val deg: Float, val cx: Float, val cy: Float, val sLane: Float, val sTravel: Float)

/** 抖动位移（像素）：两个不成整数倍的正弦相乘当噪声，按时间算；减弱动效时为 0 */
private fun shakeAt(t: Float, amp: Float, h: Float, motion: Boolean): Pair<Float, Float> {
    if (amp <= 0f || !motion) return 0f to 0f
    val a = amp * h
    return (sin(t * 37f) * kotlin.math.cos(t * 53f) * a) to (sin(t * 41f + 1.3f) * kotlin.math.cos(t * 59f) * a)
}

private fun linePose(chart: Rhythm.Chart, line: Int, live: Live, t: Float, lineScale: Float, w: Float, h: Float): LinePose {
    val pose = chart.poseAt(t, line)
    val sway = 1.7f * lineScale
    val ph = t + line * 1.7f                                  // 多线各自错开相位，不然平行的两条同步摆像一块板（审查建议）
    val deg = pose.deg * lineScale + sin(ph * 0.45f) * 2.2f * sway + live.tiltAt(t)
    val cx = w / 2f + pose.dx * lineScale * w
    val cy = h * 0.78f + pose.dy * lineScale * h + sin(ph * 0.3f) * h * 0.02f * sway
    // 线立起来（±90°）时：四条轨要压进屏高（不然外侧两轨在屏幕外够不着），飞行距离拉到半屏宽（不然音符从屏幕中间凭空冒出来）。
    // 按 |sin| 平滑过渡，0° / 180° 时正好是 1。横屏 w>h 才需要。
    val rad = deg * (Math.PI / 180f).toFloat()
    val k = abs(sin(rad)); val kc = abs(kotlin.math.cos(rad))
    var sLane = if (w > h) 1f + (h / w - 1f) * k else 1f
    val sTravel = if (w > h) 1f + (w / 2f / (h * 0.78f) - 1f) * k else 1f
    // ⚠️ 整条线（12 条轨的判定点）必须留在屏幕里，不然那几块根本点不到（老板 09-07 报的：翻转 / 大角度时方块在屏幕外）。
    //    ① 线太长放不下就整体缩（sLane）；② 端点出界就把线心推回来。都是 deg 的连续函数，线动起来不会跳。
    val mW = w * 0.05f; val mH = h * 0.08f
    sLane = minOf(sLane, (h - 2f * mH) / (w * k + 1e-3f), (w - 2f * mW) / (w * kc + 1e-3f))
    val hx = abs(w / 2f * sLane * kotlin.math.cos(rad)); val hy = abs(w / 2f * sLane * sin(rad))
    var cxx = cx; var cyy = cy
    if (cxx - hx < mW) cxx += mW - (cxx - hx) else if (cxx + hx > w - mW) cxx -= (cxx + hx) - (w - mW)
    if (cyy - hy < mH) cyy += mH - (cyy - hy) else if (cyy + hy > h - mH) cyy -= (cyy + hy) - (h - mH)
    return LinePose(deg, cxx, cyy, sLane, sTravel)
}

@Composable
private fun PraiseOverlay(word: String, at: Long, motion: Boolean, modifier: Modifier = Modifier) {
    var k by remember(at) { mutableFloatStateOf(if (motion) 0f else 0.3f) }
    LaunchedEffect(at) {
        if (!motion) return@LaunchedEffect
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            k = ((System.nanoTime() - t0) / 1e9f / 1.2f)
            if (k >= 1f) { k = 1f; break }
        }
    }
    Words.Praise(word, k, modifier)
}

/**
 * 这一下打在哪条轨上：找这条轨里**最早一个还没判、在判定窗口内、且符合 [want] 的**音符。
 * [want] 就是"这一下是什么打法"：按下只吃 tick / slide 的头，横划只吃 swipe，拖过去只吃 trace。
 * 所以对着一个 swipe 音符猛点是打不中的 —— 它要求的就是划。返回是否打中。
 */
private fun hitLane(live: Live, lane: Int, now: Float, offset: Float, anyLane: Boolean = false, want: (Rhythm.Note) -> Boolean): Boolean {
    if (now < 0f) return false
    // 无线时刻（规格 §1.6）：点哪都算 —— 四条轨都扫，吃最早那个
    if (anyLane) { for (l in 0 until live.laneCount) if (hitLane(live, l, now, offset, false, want)) return true; return false }
    val lanes = live.byLane[lane]
    val head = now + offset / 1000f
    // ⚠️ 从游标往后扫，但**不写回 next[lane]**：slide 的头判过了、尾巴还没判，
    //    写回去就等于跳过这个音符，它的尾巴永远轮不到判 —— 一局就少一个判定，
    //    服务端「判定数对不上」直接把这局打回来（实测 77 ≠ 78）。next[lane] 归 judgeMisses 管。
    var i = live.next[lane]
    while (i < lanes.size) {
        val n = lanes[i]
        val err = (head - n.t) * 1000f
        val win = Rhythm.windowMs(n.kind)                 // swipe 宽一档（规格 §1.2）
        if (err < -win) return false                      // 后面的都还太早
        if (n.judged == null && err <= win && want(n)) {
            val j = if (kotlin.math.abs(err) <= Rhythm.PERFECT_MS) Rhythm.Judge.PERFECT else Rhythm.Judge.GOOD
            if (j == Rhythm.Judge.MISS) return false      // 太晚的交给 judgeMisses 收
            n.judged = j
            live.errs += err
            live.hit(j, now, lane, n.kind)
            android.util.Log.d("YxiRhythm", "hit $j ${n.kind} lane=$lane err=${err.toInt()}ms t=${n.t}")
            return true
        }
        i++
    }
    return false
}

/**
 * 手指落在 [lane]：这条轨和左右相邻各一条里，挑**离得最近（|err| 最小）**的那个音符判。
 * 轨从 4 条变 12 条（老板 09-07）后每条只有 8% 屏宽，不放宽到 ±1 就太考验准头；放宽后同时出现的音符按谱面规则至少隔 2 条，不会抢。
 */
private fun hitNear(live: Live, lane: Int, now: Float, offset: Float, anyLane: Boolean, want: (Rhythm.Note) -> Boolean): Boolean {
    if (anyLane) return hitLane(live, lane, now, offset, true, want)
    val head = now + offset / 1000f
    var bestLane = -1; var bestErr = Float.MAX_VALUE
    val b = lane / Rhythm.LANES * Rhythm.LANES                                  // 邻轨不跨判定线
    for (l in maxOf(b, lane - 1)..minOf(b + Rhythm.LANES - 1, lane + 1)) {
        val lanes = live.byLane[l]; var i = live.next[l]
        while (i < lanes.size) {
            val n = lanes[i]; val err = (head - n.t) * 1000f
            if (err < -Rhythm.windowMs(n.kind)) break
            if (n.judged == null && err <= Rhythm.windowMs(n.kind) && want(n)) {
                if (kotlin.math.abs(err) < bestErr) { bestErr = kotlin.math.abs(err); bestLane = l }
                break
            }
            i++
        }
    }
    return bestLane >= 0 && hitLane(live, bestLane, now, offset, false, want)
}

/** 过了窗口还没判的算 Miss；长按到点了看手指还在不在 */
private fun judgeMisses(live: Live, now: Float, offset: Float, changed: () -> Unit) {
    val head = now + offset / 1000f
    var dirty = false
    live.byLane.forEachIndexed { lane, lanes ->
        var i = live.next[lane]
        while (i < lanes.size) {
            val n = lanes[i]
            if (n.judged == null && (head - n.t) * 1000f > Rhythm.windowMs(n.kind)) {
                n.judged = Rhythm.Judge.MISS; live.hit(Rhythm.Judge.MISS, now, lane, n.kind); dirty = true
                android.util.Log.d("YxiRhythm", "miss ${n.kind} lane=$lane t=${n.t} now=$head")
            }
            if (n.judged == null) break
            if (n.hold && !n.tailDone && head >= n.t + n.dur) {
                n.tailDone = true
                live.hit(
                    if ((live.heldNear(lane) || live.autoHold) && n.judged != Rhythm.Judge.MISS) Rhythm.Judge.PERFECT else Rhythm.Judge.MISS,
                    now, lane, n.kind,
                )
                dirty = true
            }
            if (!n.hold || n.tailDone) i++ else break
        }
        live.next[lane] = i
    }
    if (dirty) changed()
}

/** 边打边显示的分数。没配档位时 [Live.live] 跟老公式等价，配了就带上倍率 —— 两种情况都跟服务端一致 */
private fun Live.currentScore() =
    // ⚠️ 四舍五入，**不是截断**：服务端是 round，客户端要是 toInt() 截断，
    //    同一局能差 1 分 —— 游戏里显示 132050、结算页 132051，玩家看得见。
    (live + maxCombo.toFloat() / chart.units.coerceAtLeast(1) * Rhythm.rules.comboBonus).roundToInt()

// ── 结算 ────────────────────────────────────────────────────────────────────
@Composable
private fun ResultCard(
    chartId: String, local: Rhythm.Result, elapsedMs: Int, hits: String,
    onAgain: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, auto: Boolean = false,
) {
    val ctx = LocalContext.current
    var off by remember { mutableIntStateOf(Rhythm.offsetMs(ctx)) }
    var sent by remember { mutableStateOf<Rhythm.Submitted?>(null) }
    var sending by remember { mutableStateOf(true) }
    // ⚠️ 分数以服务端为准（客户端只报判定计数）。没连上就照实说，别装作发过奖了。
    LaunchedEffect(chartId) {
        if (auto) { sending = false; return@LaunchedEffect }         // 演示：不交、不存最好成绩
        sent = Rhythm.submit(ctx, chartId, local.perfect, local.good, local.miss, local.maxCombo, elapsedMs, hits)
        sent?.result?.let { Rhythm.saveBest(ctx, chartId, it.copy(medianErrMs = local.medianErrMs)) }
        sending = false
    }
    val r = sent?.result?.copy(
        perfect = local.perfect, good = local.good, miss = local.miss, medianErrMs = local.medianErrMs,
    ) ?: local

    // 结算页跟着谱面一起横着（打完一局手机还横在手里）。底色沿用舞台的深色，
    // 从游戏切过来不会闪一下白。⚠️ 所以这一页也**不能用主题色 getter**。
    val ink = Color(0xFFE8EDF5)
    val bg = Color(0xFF0E1117)
    val accent = Color(0xFFFFB787)
    val amber = Color(0xFFFFC46B)
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { shown = true }
    val pop by animateFloatAsState(if (shown) 1f else 0.7f, tween(if (motion) 260 else 0), label = "rank")

    Row(
        modifier.fillMaxSize().background(bg).padding(32.dp, 20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：评级 + 曲名。评级是这一页的主角，占一半高度
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                r.rank,
                Modifier.graphicsLayer {
                    scaleX = pop; scaleY = pop; alpha = ((pop - 0.7f) / 0.3f).coerceIn(0f, 1f)
                },
                style = MaterialTheme.typography.displayLarge, color = accent, fontWeight = FontWeight.Bold,
            )
            Text(
                t(chartId.substringBefore('_').let { id -> Rhythm.SONGS.first { it.id == id }.zh }) +
                    " · " + t(Rhythm.diffName(chartId.substringAfterLast('_'))),
                style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .6f),
            )
        }
        // 右：分数 · 准度 · 四个计数 · 上传状态 · 校准提示 · 两个按钮
        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "%07d".format(r.score),
                style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, color = ink,
            )
            Text(t("准度 %.1f%%").format(r.acc * 100), style = MaterialTheme.typography.titleMedium, color = amber)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat(t("完美"), r.perfect, ink); Stat(t("不错"), r.good, ink)
                Stat(t("漏了"), r.miss, ink); Stat(t("连击"), r.maxCombo, ink)
            }
            val up = sent
            Text(
                when {
                    auto -> t("演示 · 不计成绩")
                    sending -> t("正在交成绩…")
                    up == null -> t("没连上服务器 —— 成绩只留在这台手机上，也没发奖励。")
                    up.error != null -> up.error
                    up.granted.isEmpty() -> t("成绩已记到账号上。")
                    else -> t("拿到了：%s").format(up.granted.joinToString("、"))
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (up?.granted?.isNotEmpty() == true) amber else ink.copy(alpha = .55f),
            )
            // 判定偏差大到该校准了就直说，别让人以为是自己手残
            if (abs(r.medianErrMs) >= 30) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t(if (r.medianErrMs > 0) "这局你平均按晚了 %d 毫秒" else "这局你平均按早了 %d 毫秒")
                        .format(abs(r.medianErrMs)),
                    style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .75f),
                )
                TextButton({ off -= r.medianErrMs; Rhythm.setOffsetMs(ctx, off) }) {
                    Text(t("按这个校准判定"), color = accent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // 这一页是固定深色，按钮也别用主题主色（浅色皮肤下它是 Google 蓝，在这儿跳戏）
                Button(
                    onAgain,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = accent, contentColor = Color(0xFF2A1C0E),
                    ),
                ) { Text(t("再来一次")) }
                TextButton(onBack) { Text(t("换一首"), color = ink.copy(alpha = .7f)) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, v: Int, ink: Color) {
    // 大数字压小标签（标签约四成大小）—— 参考里那套两层排布确实好读，借这个，不借它的斜切
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$v", style = MaterialTheme.typography.titleLarge, color = ink)
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .5f))
    }
}


/**
 * 命中音效（音符碎裂声）。用 [SoundPool] 而不是 MediaPlayer：一局要响一两百次，SoundPool 是为这种短音设计的，
 * 延迟低、能叠着播。三个样本按音块种类分，靠音量和速率分出「完美」和「不错」两种手感。
 *
 * ⚠️ 加载是异步的：刚进页面的头几十毫秒可能还没准备好，那就先不响，别为此卡住开局。
 */
private class RhythmSfx(ctx: android.content.Context) {
    private val pool = SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
    // 命中音 = 音符碎裂的声音，四种音块一块一种（老板 2026-09-07：原来的那声 + 从 20 款里挑的三款，刚好四种）：
    //   tick → 原来的合成「嗒」(yx_tick) · swipe → 光尘 · trace → 风铃散 · slide → 樱瓣。
    // 源文件 design/sfx/shatter/magic_{1,3,4}.wav，另外 17 款在同目录，换音只改这张表。
    private val ids = HashMap<Rhythm.Kind, Int>()
    private val rate = mapOf(Rhythm.Kind.TICK to 1.0f, Rhythm.Kind.SWIPE to 1.0f, Rhythm.Kind.TRACE to 1.0f, Rhythm.Kind.SLIDE to 1.0f)
    private var ready = false

    init {
        pool.setOnLoadCompleteListener { _, _, status -> if (status == 0) ready = true }
        runCatching {
            ids[Rhythm.Kind.TICK] = pool.load(ctx, app.yxi.R.raw.yx_tick, 1)
            ids[Rhythm.Kind.SWIPE] = pool.load(ctx, app.yxi.R.raw.yx_sh_dust, 1)
            ids[Rhythm.Kind.TRACE] = pool.load(ctx, app.yxi.R.raw.yx_sh_chime, 1)
            ids[Rhythm.Kind.SLIDE] = pool.load(ctx, app.yxi.R.raw.yx_sh_petal, 1)
        }
    }

    fun play(kind: Rhythm.Kind, j: Rhythm.Judge, vol: Float) {
        if (!ready || vol <= 0f) return
        val id = ids[kind] ?: return
        val r = rate[kind] ?: 1f
        when (j) {
            Rhythm.Judge.PERFECT -> pool.play(id, vol, vol, 1, 0, r)
            // 不错：闷一点、低一点，一耳朵听得出差别（还是同一个音，不另做一套文件）
            Rhythm.Judge.GOOD -> pool.play(id, vol * .55f, vol * .55f, 1, 0, r * 0.92f)
            Rhythm.Judge.MISS -> Unit                                     // 漏了不响：安静本身就是反馈
        }
    }

    fun release() = pool.release()
}

private fun android.view.View.hapticTick(level: Int, perfect: Boolean) {
    val c = when (level) {
        1 -> HapticFeedbackConstants.CLOCK_TICK
        2 -> if (perfect) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK
        3 -> if (perfect) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CONTEXT_CLICK
        else -> return
    }
    performHapticFeedback(c)
}


/**
 * 手感面板（老板 2026-09-06：「震动什么的这些你都让我自己调节，我试试那个最舒服就直接可以上线」）。
 *
 * 每改一格**当场就能试**：点「试一下」会照当前这组设置放一次命中 —— 声音、震动、爆点一起来，
 * 不用为了试一个数去打一整首。改完立刻存本地，下一局就是新的手感。
 *
 * ⚠️ **判定窗口不在这儿**（完美 ±80ms / 不错 ±160ms 写死）：那不是手感是难度，
 *    放宽了等于自己给自己发 S，而服务端算分用的就是这套判定计数 —— 那就成作弊了。
 */
@Composable
private fun FeelPanel() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val sfx = remember { RhythmSfx(ctx) }
    DisposableEffect(sfx) { onDispose { sfx.release() } }

    var approach by remember { mutableFloatStateOf(Rhythm.approach(ctx)) }
    var vol by remember { mutableFloatStateOf(Rhythm.soundVol(ctx)) }
    var hap by remember { mutableIntStateOf(if (Rhythm.hapticOn(ctx)) Rhythm.haptic(ctx) else 0) }
    var fx by remember { mutableFloatStateOf(Rhythm.fxScale(ctx)) }
    var line by remember { mutableFloatStateOf(Rhythm.lineScale(ctx)) }
    var off by remember { mutableIntStateOf(Rhythm.offsetMs(ctx)) }
    var demoAt by remember { mutableLongStateOf(0L) }

    fun tryIt() {
        demoAt = System.currentTimeMillis()
        sfx.play(Rhythm.Kind.TICK, Rhythm.Judge.PERFECT, vol)
        view.hapticTick(hap, true)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(14.dp, 10.dp),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 试一下：照当前这组放一次命中（爆点画在这块小舞台上）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.weight(1f).height(74.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0E1117)).clickable { tryIt() },
                ) {
                    val burst = Color(0xFFFFB787)
                    var t by remember { mutableFloatStateOf(9f) }
                    LaunchedEffect(demoAt) {
                        if (demoAt == 0L) return@LaunchedEffect
                        val t0 = System.nanoTime()
                        while (true) {
                            withFrameNanos { }
                            t = (System.nanoTime() - t0) / 1e9f
                            if (t > 0.45f) break
                        }
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(Color.White.copy(alpha = .85f), Offset(0f, size.height * .72f), Offset(size.width, size.height * .72f), 2.2f * density)
                        // 试一下：用老板挑的第一款（快门）放一次，尺度跟正式局一致
                        if (fx > 0f && t <= 0.6f) {
                            val bench = size.height / 120f
                            withTransform({ translate(size.width / 2f, size.height * .72f); scale(bench, bench, Offset.Zero) }) {
                                with(HitFxs.all[0].fx) { draw(Hit(13.dp.toPx() * fx / bench, burst, true, 9f), t / 0.6f, Rng(0.37f)) }
                            }
                        }
                    }
                    Text(
                        t("试一下"), Modifier.align(Alignment.TopStart).padding(10.dp, 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = Color(0xFFE8EDF5).copy(alpha = .7f),
                    )
                }
            }
            var flash by remember { mutableStateOf(Rhythm.flashOn(ctx)) }
            Feel(t("闪屏 / 抖动"), if (flash) t("开") else t("关")) { flash = !flash; Rhythm.setFlashOn(ctx, flash) }   // 癫狂难度专用，光敏的人关掉
            Feel(t("音符下落"), when { approach <= 1.2f -> t("快"); approach >= 2.0f -> t("慢"); else -> t("适中") }) {
                approach = when { approach <= 1.2f -> 1.55f; approach <= 1.7f -> 2.2f; else -> 1.1f }
                Rhythm.setApproach(ctx, approach)
            }
            Feel(t("打击音"), lvName(vol)) {
                vol = if (vol >= .9f) 0f else (vol + .3f).coerceAtMost(.9f)
                Rhythm.setSoundVol(ctx, vol); Rhythm.setSoundOn(ctx, vol > 0f); tryIt()
            }
            Feel(t("震动"), listOf(t("关"), t("轻"), t("中"), t("重"))[hap]) {
                hap = (hap + 1) % 4
                Rhythm.setHaptic(ctx, hap); Rhythm.setHapticOn(ctx, hap > 0); tryIt()
            }
            Feel(t("命中特效"), lvName(fx / 1.6f)) {
                fx = if (fx >= 1.5f) 0f else (fx + .5f).coerceAtMost(1.6f)
                Rhythm.setFxScale(ctx, fx); tryIt()
            }
            Feel(t("判定线晃动"), lvName(line / 1.5f)) {
                line = if (line >= 1.4f) 0f else (line + .5f).coerceAtMost(1.5f)
                Rhythm.setLineScale(ctx, line)
            }
            Feel(t("判定偏移"), t("%d ms").format(off)) {
                off = if (off >= 60) -60 else off + 20
                Rhythm.setOffsetMs(ctx, off)
            }
            Text(
                t("打完一局在结算页可以按实际偏差自动校准。判定宽严不给调 —— 那是难度，不是手感。"),
                style = MaterialTheme.typography.labelSmall, color = Muted,
            )
        }
    }
}

@Composable
private fun lvName(k: Float): String = when {
    k <= 0.01f -> t("关"); k < 0.45f -> t("轻"); k < 0.8f -> t("中"); else -> t("重")
}

/** 手感面板里的一行：左边名字、右边当前档位，点一下换下一档 */
@Composable
private fun Feel(name: String, value: String, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onNext() }.padding(4.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.labelLarge, color = Copper)
    }
}

/**
 * 连击到一定数就跳出来的**夸奖词**（老板 2026-09-06 给了参考图 `design/judge-words-ref.png`：
 * 「你看看这个字体…连击了就可以触发这些提示」）。
 *
 * 那张参考图是**老板自己用 AI 生成的**（cc-Yxi 2026-09-06 核实），不是从别的音游扒的 ——
 * 所以这套风格可以直接照着做，不用像音乐和 Phigros 素材那样绕开。
 * 实现上仍然不带任何字形文件：系统字的最重字重 + 斜体，装饰（斜切色块、细斜线、四角星、圆点）
 * 全是这儿画出来的几何体 —— 这是工程选择（省一个字体文件），不是版权限制。
 *
 * ⚠️ 参考图是**浅底 + 白填充 + 黑描边**；我们的舞台是**固定深色**，照搬会糊成一团 ——
 *    改成亮色填充 + 深描边 + 一层外发光。抄的是结构不是配色。
 *
 * 触发点跟着**服务端下发的连击档位**走（老板 2026-09-06 定稿：×8 Great 1.2 · ×15 Excellent 1.5 ·
 * ×30 Amazing 2.0；Perfect 留给单音符的完美判定；断连击直接掉回 1.0）。
 * ⚠️ 门槛和词都不写死在客户端 —— 老板要调，服务端改一行 `comboTiers` 配置就生效，不用发版。
 */
