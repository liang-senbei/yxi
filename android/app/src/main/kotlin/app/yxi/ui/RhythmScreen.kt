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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.roundToInt

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
        r != null -> ResultCard(r.chartId, r.result, r.elapsedMs, r.hits, onAgain = {
            val song = Rhythm.SONGS.first { it.id == r.chartId.substringBefore('_') }
            result = null; playing = song to r.chartId.substringAfter('_')
        }, onBack = { result = null; synced++ })                     // 结算页整屏通铺：不要 Scaffold 的内边距

        p != null -> GameBoard(p.first, p.second, onDone = { id, res, ms, hits ->
            Rhythm.saveBest(ctx, id, res); playing = null; result = Done(id, res, ms, hits)
        }, onQuit = { playing = null })                              // 打谱面同理，整屏通铺

        else -> SongList(synced, onPick = { s, d -> playing = s to d }, modifier = modifier)
    }
}

/** 一局打完的结果：谱面 id · 本地算的成绩 · 用时 · 判定序列（上报给服务端重算） */
private data class Done(val chartId: String, val result: Rhythm.Result, val elapsedMs: Int, val hits: String)

// ── 选曲 ────────────────────────────────────────────────────────────────────
@Composable
private fun SongList(synced: Int, onPick: (Rhythm.Song, String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(t("云曦节拍"), Modifier.padding(20.dp, 18.dp, 20.dp, 4.dp), style = MaterialTheme.typography.headlineMedium)
        Text(
            t("跟着拍子点四条轨。曲子是我们自己写的。"),
            Modifier.padding(20.dp, 0.dp, 20.dp, 14.dp),
            style = MaterialTheme.typography.bodyMedium, color = Muted,
        )
        Rhythm.SONGS.forEach { s ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp),
            ) {
                Column(Modifier.padding(16.dp, 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(s.zh), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${s.bpm} BPM · ${s.seconds}s", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Rhythm.DIFFS.forEach { d ->
                            val best = remember(synced) { Rhythm.best(ctx, "${s.id}_$d") }
                            Surface(
                                color = if (d == "hard") Copper.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onPick(s, d) },
                            ) {
                                Column(Modifier.padding(14.dp, 10.dp)) {
                                    Text(t(if (d == "hard") "认真" else "轻松"), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        best?.let { "${it.rank} · ${it.score}" } ?: t("还没打过"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (best == null) Muted else Amber,
                                    )
                                }
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
private class Live(val chart: Rhythm.Chart) {
    var perfect = 0; var good = 0; var miss = 0
    var combo = 0; var maxCombo = 0
    /** 判定序列（P/G/M，按发生顺序）——上报给服务端算分用 */
    val seq = StringBuilder()
    /** 本地即时分数：按连击档位边打边加。服务端会用同一套规则重算，结算页以它为准 */
    var live = 0.0
    val errs = ArrayList<Float>()
    /** 每条轨下一个还没判的音符下标，省得每帧从头扫 */
    val next = IntArray(4)
    val held = BooleanArray(4)
    val flash = FloatArray(4) { -9f }  // 每条轨最后一次判定的时刻（秒）
    val flashJudge = arrayOfNulls<Rhythm.Judge>(4)
    var lastJudge: Rhythm.Judge? = null
    var lastJudgeAt = 0f
    /** 判定发生时喊一声：音效 + 震动挂在这儿，绘制不管这些 */
    var onJudge: ((Rhythm.Judge, Int) -> Unit)? = null

    // ── 玩家造成的校准线倾斜（trace / swipe 打中时把线往那个方向带一下）──
    private var tiltDeg = 0f
    private var tiltAt = -9f

    /**
     * 打中一个 trace / swipe：把倾斜**朝这次的方向拉一把**（不是累加）。
     * 方向相反的两下挨着中，自然互相抵消，不会转飞。
     */
    fun tilt(now: Float, dir: Int) {
        tiltDeg = (tiltAt(now) * 0.55f + dir * 4.5f).coerceIn(-6f, 6f)
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
        if (dt < 0f || dt > 2f) return 0f
        return tiltDeg * kotlin.math.exp(-dt / 0.15f)
    }

    val byLane: List<List<Rhythm.Note>> = (0..3).map { l -> chart.notes.filter { it.lane == l } }

    fun hit(j: Rhythm.Judge, at: Float, lane: Int = -1) {
        if (lane in 0..3) { flash[lane] = at; flashJudge[lane] = j }
        seq.append(when (j) { Rhythm.Judge.PERFECT -> 'P'; Rhythm.Judge.GOOD -> 'G'; else -> 'M' })
        onJudge?.invoke(j, lane)
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
            Rhythm.score(chart.units, perfect, good, maxCombo), acc, Rhythm.rank(acc),
            perfect, good, miss, maxCombo, med,
        )
    }
}

@Composable
private fun GameBoard(
    song: Rhythm.Song,
    difficulty: String,
    onDone: (String, Rhythm.Result, Int, String) -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val chart = remember(song, difficulty) { Rhythm.load(ctx, song.id, difficulty) }
    val live = remember(chart) { Live(chart) }
    val offset = remember { Rhythm.offsetMs(ctx).toFloat() }
    var now by remember { mutableFloatStateOf(-3f) }        // 秒；负数 = 倒计时
    val startedAt = remember { System.currentTimeMillis() }
    var combo by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(3) }
    var judgeShow by remember { mutableStateOf<Rhythm.Judge?>(null) }
    var praise by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var score by remember { mutableIntStateOf(0) }
    // ⚠️ 舞台**固定深色**，不跟浅色/深色主题走：音游得让音符和特效跳出来，
    //    浅色底上一整片发白、命中的光一点都看不见。这是这一页的例外，别推广到别处。
    val ink = Color(0xFFE8EDF5)
    val laneBg = Color(0xFF0E1117)
    val stageTop = Color(0xFF141A24)
    // 四种音块的颜色（老板 2026-09-06 定：淡橙 / 浅蓝 / 樱粉 / 浅绿）。
    // ⚠️ 全部写死常量 —— 舞台是固定深色，用主题色 getter 会跟着皮肤变（Copper 那条坑）。
    // ⚠️ **颜色之外还要靠形状分得出**：色盲、强光下只看颜色是不够的，所以
    //    slide 带尾轨、trace 带抓握纹和箭头、swipe 带方向尖角。
    val kindColor = { k: Rhythm.Kind ->
        when (k) {
            Rhythm.Kind.TICK -> Color(0xFFFFC08A)        // 淡橙
            Rhythm.Kind.SLIDE -> Color(0xFF8AD4F5)       // 浅蓝
            Rhythm.Kind.TRACE -> Color(0xFFF5A8C8)       // 樱粉
            Rhythm.Kind.SWIPE -> Color(0xFF9EE6A8)       // 浅绿
        }
    }
    val noteColor = Color(0xFF5CC8F5)                    // 轨道渐层还用冷青
    // ⚠️ **固定深色的舞台上，一律不用主题色 getter**（Copper / Amber / Muted 都会跟着皮肤变）。
    //    `Copper` 是**槽位名 = 主操作色**，不是字面的铜色：深色皮肤里它是 #FFB787，
    //    浅色皮肤里故意是 Google 蓝 #0B57D0（Palette.kt:87 有注释）—— 那不是 bug，别去"修"它，
    //    全 app 两百多处靠这个名字换风格。这块舞台不跟皮肤走，所以颜色自己写死。
    //    （结算页不是固定深色、跟着主题走，那儿用 Copper 当主操作色是对的。）
    val judgeColor = Color(0xFFFFB787)                   // 完美 / 判定线：暖铜
    val goodColor = Color(0xFFFFC46B)                    // 不错：琥珀
    val rimColor = Color(0xFFE0B378)                     // 音符的暖描边，跟命中光同一家
    val missColor = Color(0xFFE5484D)
    // 关了系统动画的人：**音符照落**（那是玩法本身，停了就没法玩了），
    // 但轨道的击中余光这类纯装饰不画。STYLE.md 那条管的是装饰，不是内容。
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }

    // 每条轨最后一次"手指按下"的时刻。
    // ⚠️ 写在指针回调、读在 Canvas 的绘制 lambda —— **两边都在主线程**（Compose 的指针输入和绘制
    //    都跑在 UI 线程），所以裸数组就够，不需要 @Volatile / snapshotFlow。
    //    改成 Compose 状态反而更糟：它每帧都变，读在组合期就是每帧重组整页（见 TROUBLESHOOTING #13）。
    val press = remember { FloatArray(4) { -9f } }
    val view = LocalView.current
    val sfx = remember { RhythmSfx(ctx) }
    DisposableEffect(sfx) { onDispose { sfx.release() } }
    // ⚠️ **不要 remember**：在结算页点「再来一次」会用同样的 song/difficulty 重进，
    //    remember 会把上一局的开关值留着 —— 玩家刚在选曲页关掉音效，再来一局还在响。
    val soundVol = if (Rhythm.soundOn(ctx)) Rhythm.soundVol(ctx) else 0f
    val hapticLv = if (Rhythm.hapticOn(ctx)) Rhythm.haptic(ctx) else 0
    val fxScale = Rhythm.fxScale(ctx)
    val lineScale = Rhythm.lineScale(ctx)
    val approach = Rhythm.approach(ctx)
    // 打击反馈：判定发生的那一瞬，声音和震动一起来 —— 这两样是"打击感"的主体，画面只是补
    DisposableEffect(live, soundVol, hapticLv) {
        live.onJudge = { j, _ ->
            judgeShow = j
            praiseFor(live.combo)?.let { praise = it to System.currentTimeMillis() }
            sfx.play(j, soundVol)
            if (j != Rhythm.Judge.MISS) view.hapticTick(hapticLv, j == Rhythm.Judge.PERFECT)
        }
        onDispose { live.onJudge = null }
    }

    LaunchedEffect(judgeShow, live.lastJudgeAt) {
        if (judgeShow != null) { kotlinx.coroutines.delay(350); judgeShow = null }
    }

    val mp = remember { runCatching { MediaPlayer.create(ctx, song.raw) }.getOrNull() }
    DisposableEffect(mp) { onDispose { mp?.let { runCatching { it.stop() }; it.release() } } }
    // 切后台（按 Home / 来电 / 锁屏）就停掉这局：不然音乐会在后台接着放，
    // 而且回来时曲子已经跑远了，判定全是漏 —— 与其让人回来面对一屏 Miss，不如明说这局不算。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mp) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) { mp?.let { runCatching { it.pause() } }; onQuit() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (mp == null) {                                   // 解码失败：如实说，别放一个没有声音的空谱
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("这首曲子放不出来，先换一首。"), style = MaterialTheme.typography.bodyLarge)
            TextButton(onQuit) { Text(t("返回")) }
        }
        return
    }

    // 音频时钟：每帧读播放位置，两次读数之间用单调时钟补插值（MediaPlayer 的位置是跳着走的）
    LaunchedEffect(chart) {
        var base = 0; var baseAt = 0L
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            val wall = (System.nanoTime() - t0) / 1e9f
            if (wall < 3f) {                                 // 三秒倒计时
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
            now = if (abs(pred - now) > .25f) pred else maxOf(now, pred)   // 只前进，别抖回去
            judgeMisses(live, now, offset) { combo = live.combo; score = live.currentScore() }
            if (now > (chart.notes.lastOrNull()?.t ?: 0f) + 2.5f || (base > 0 && !mp.isPlaying)) {
                onDone(chart.id, live.result(), (System.currentTimeMillis() - startedAt).toInt(), live.seq.toString())
                return@LaunchedEffect
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(laneBg).pointerInput(chart) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 场地会转，所以先把手指的位置**转回线的坐标系**再判在哪条轨，
                // 不然线一斜，看着按在这条轨、算出来是隔壁那条。
                fun laneOf(pos: Offset): Int {
                    val (deg, dy) = chart.poseAt(now)
                    val pivotX = size.width / 2f
                    val pivotY = size.height * (0.80f + dy)
                    val rad = -(deg + live.tiltAt(now)) * (Math.PI / 180f).toFloat()
                    val ox = pos.x - pivotX
                    val oy = pos.y - pivotY
                    val laneX = pivotX + ox * kotlin.math.cos(rad) - oy * kotlin.math.sin(rad)
                    return ((laneX / size.width) * 4).toInt().coerceIn(0, 3)
                }
                var lane = laneOf(down.position)
                live.held[lane] = true
                press[lane] = now                     // 按下去就先亮一下（不管有没有打中），手感的一半在这
                // 按下只吃 tick 和 slide 的头；swipe 要滑、trace 要拖，各走各的
                hitLane(live, lane, now, offset) { it.kind == Rhythm.Kind.TICK || it.kind == Rhythm.Kind.SLIDE }
                    .also { if (it) { combo = live.combo; score = live.currentScore() } }
                val startX = down.position.x
                var swiped = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val dx = ch.position.x - startX
                    // 横着划够一段 = swipe（只认一次，别一路划一路判）
                    if (!swiped && kotlin.math.abs(dx) > size.width * 0.055f) {
                        swiped = true
                        val dir = if (dx > 0) 1 else -1
                        if (hitLane(live, lane, now, offset) {
                                it.kind == Rhythm.Kind.SWIPE && (it.dir == 0 || it.dir == dir)
                            }
                        ) { combo = live.combo; score = live.currentScore(); live.tilt(now, dir) }
                    }
                    // 手指挪到另一条轨 = trace 到位
                    val cur = laneOf(ch.position)
                    if (cur != lane) {
                        val from = lane
                        if (hitLane(live, from, now, offset) {
                                it.kind == Rhythm.Kind.TRACE && (from + it.dir).coerceIn(0, 3) == cur
                            }
                        ) { combo = live.combo; score = live.currentScore(); live.tilt(now, if (cur > from) 1 else -1) }
                        live.held[from] = false
                        lane = cur
                        live.held[lane] = true
                        press[lane] = now
                    }
                }
                live.held[lane] = false
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val laneW = size.width / 4f
            val (poseDeg0, poseDy0) = chart.poseAt(now)
            // 谱面写的 + 玩家 trace/swipe 造成的，相加再封顶（倾斜只进画面和手指坐标反变换，不进时间判定）
            val poseDeg = (poseDeg0 * lineScale + live.tiltAt(now)).coerceIn(-9f, 9f)
            val poseDy = poseDy0 * lineScale
            val judgeY = size.height * (0.80f + poseDy)
            // ⚠️ 整块场地（轨道 · 音符 · 判定线 · 爆点）**一起**绕线中心转：
            //    音符垂直于线、跟着线走，这就是"音符活在线的坐标系里"。
            //    线动**不改变任何音符的到达时刻** —— 判定完全不受影响，服务端也不用改。
            val noteH = (size.height * 0.026f).coerceIn(14f, 30f)   // 横版屏幕矮，音符按高度算，别用固定像素

            // 底色**不跟着转**（转了四角会露出底下的浅色页面），先铺满整屏
            drawRect(Brush.verticalGradient(listOf(stageTop, laneBg)), Offset(0f, 0f), Size(size.width, size.height))
            rotate(poseDeg, Offset(size.width / 2f, judgeY)) {
            // 场地画得比屏幕宽一截：转起来两头才不会空出来
            val over = size.height * 0.12f
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.7f to noteColor.copy(alpha = .05f), 1f to noteColor.copy(alpha = .14f),
                ),
                Offset(-over, -over), Size(size.width + over * 2, judgeY + over),
            )
            for (i in 1..3) drawLine(ink.copy(alpha = .08f), Offset(laneW * i, -over), Offset(laneW * i, judgeY), 2f)

            // ── 手指按下：这条轨亮一下。不管有没有打中都亮 —— 它反馈的是"你按了"，不是"你对了" ──
            if (motion) for (l in 0..3) {
                val age = now - press[l]
                if (age in 0f..0.13f) drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, noteColor.copy(alpha = .13f * (1 - age / 0.13f))),
                    ),
                    Offset(laneW * l, judgeY * 0.45f), Size(laneW, judgeY * 0.55f),
                )
            }

            // ── 判定线：底光 + 线 + 亮芯，跟输入框那颗玻璃药丸一个语言 ──
            val lineGlow = live.flash.indices.maxOf { l ->
                val a = now - live.flash[l]
                if (a in 0f..0.16f) 1f - a / 0.16f else 0f
            }.takeIf { motion } ?: 0f
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, judgeColor.copy(alpha = .10f + .18f * lineGlow))),
                Offset(-over, judgeY - 26f - 16f * lineGlow), Size(size.width + over * 2, 26f + 16f * lineGlow),
            )
            // 线画到屏幕外一截，转起来才不会露出端点
            drawLine(judgeColor.copy(alpha = .9f), Offset(-over, judgeY), Offset(size.width + over, judgeY), 5f + 2f * lineGlow)
            drawLine(
                Color.White.copy(alpha = .45f + .35f * lineGlow),
                Offset(-over, judgeY - 1.5f), Offset(size.width + over, judgeY - 1.5f), 1.5f,
            )

            // ── 命中：分层的爆点（见 hitBurst）。漏了不放爆点，只在线上留一小片暗红 ──
            if (motion) for (l in 0..3) {
                val age = now - live.flash[l]
                val j = live.flashJudge[l] ?: continue
                if (age < 0f || age > 0.38f) continue
                val cx = laneW * l + laneW / 2f
                if (j == Rhythm.Judge.MISS) {
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, missColor.copy(alpha = .16f * (1 - age / 0.38f)))),
                        Offset(laneW * l, judgeY - 70f), Size(laneW, 70f),
                    )
                } else {
                    if (fxScale > 0f) {
                        hitBurst(cx, judgeY, age, if (j == Rhythm.Judge.PERFECT) judgeColor else goodColor, noteH * 1.1f * fxScale)
                        // 校准瞬间的竖线：**垂直于判定线**，带 ±10° 的随机偏转，很快淡出。
                        // 随机数按轨号定死（不是每帧摇），不然它会在 0.2 秒里疯狂抖。
                        val k = (age / 0.2f).coerceIn(0f, 1f)
                        if (k < 1f) {
                            val jitter = ((l * 37 % 21) - 10).toFloat()      // -10..+10 度，按轨固定
                            val len = noteH * (3.2f + 2.4f * k) * fxScale
                            rotate(jitter, Offset(cx, judgeY)) {
                                drawLine(
                                    Color.White.copy(alpha = .8f * (1f - k)),
                                    Offset(cx, judgeY - len), Offset(cx, judgeY + len * .35f), 3f,
                                )
                            }
                        }
                    }
                }
            }

            // ── 音符：圆角条 + 顶部高光，跟气泡/药丸同一套玻璃语言，不是一块死色 ──
            val head = now + offset / 1000f
            chart.notes.forEach { n ->
                val dt = n.t - head
                if (dt > approach || n.judged != null && !n.hold) return@forEach
                if (n.judged != null && n.tailDone) return@forEach
                if (dt < -0.4f && !n.hold) return@forEach
                val y = judgeY * (1f - dt / approach)
                // 音符比轨道窄、居中：横版一条轨 600px 宽，照轨宽画就是一根大棍子。
                // 判定还是按整条轨算（点哪儿都算），只是画得秀气些 —— 手感不变，好看。
                val w = minOf(laneW * 0.72f, size.width * 0.10f)
                val x = laneW * n.lane + (laneW - w) / 2f
                if (n.hold) {
                    // 长按的条：**按到判定线为止**。头过了线就从下往上一点点被"吃掉"，
                    // 而不是继续往下画 —— 画到线以下等于告诉玩家"这段还要按"，其实早过去了。
                    val tailY = (judgeY * (1f - (n.t + n.dur - head) / approach)).coerceAtLeast(0f)
                    val bottom = minOf(y, judgeY)
                    if (bottom > tailY) {
                        val holding = n.judged != null && n.judged != Rhythm.Judge.MISS && live.held[n.lane]
                        drawRoundRect(
                            kindColor(n.kind).copy(alpha = if (n.judged == Rhythm.Judge.MISS) .18f else if (holding) .62f else .42f),
                            Offset(x, tailY), Size(w, bottom - tailY), CornerRadius(noteH / 2),
                        )
                        drawRoundRect(                                   // 左侧一道细高光，让它像有厚度
                            Color.White.copy(alpha = .18f),
                            Offset(x + w * .12f, tailY), Size(w * .10f, bottom - tailY), CornerRadius(noteH / 3),
                        )
                    }
                }
                if (n.judged == null && y >= -20f && y <= judgeY + noteH) {
                    val h = noteH
                    val c = kindColor(n.kind)
                    drawRoundRect(rimColor, Offset(x - 3f, y - h / 2 - 3f), Size(w + 6f, h + 6f), CornerRadius(h))
                    drawRoundRect(c, Offset(x, y - h / 2), Size(w, h), CornerRadius(h / 2))
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = .45f), Color.Transparent)),
                        Offset(x, y - h / 2), Size(w, h * .5f), CornerRadius(h / 2),
                    )
                    // 形状特征：光靠颜色分不出来（色盲、强光），所以每种再给一个记号
                    when (n.kind) {
                        Rhythm.Kind.TRACE -> {
                            // 抓握纹（三道竖线）+ 指向目标轨的箭头
                            for (g in -1..1) drawRect(
                                Color.White.copy(alpha = .55f),
                                Offset(x + w / 2 + g * h * .45f - 1.5f, y - h * .22f), Size(3f, h * .44f),
                            )
                            val d = if (n.dir > 0) 1f else -1f
                            val ax = if (n.dir > 0) x + w + h * .5f else x - h * .5f
                            val p = Path()
                            p.moveTo(ax + d * h * .42f, y)
                            p.lineTo(ax - d * h * .18f, y - h * .34f)
                            p.lineTo(ax - d * h * .18f, y + h * .34f)
                            p.close()
                            drawPath(p, c.copy(alpha = .9f))
                        }
                        Rhythm.Kind.SWIPE -> {
                            // 方向尖角：往要滑的那边支出一个三角
                            val sgn = if (n.dir >= 0) 1f else -1f
                            val ax = if (n.dir >= 0) x + w else x
                            val p = Path()
                            p.moveTo(ax + sgn * h * .75f, y)
                            p.lineTo(ax, y - h * .55f)
                            p.lineTo(ax, y + h * .55f)
                            p.close()
                            drawPath(p, c)
                        }
                        else -> Unit
                    }
                }
            }
            }
        }

        // ── HUD（横版：退出在左上、连击在正中、分数在右上、曲名和难度在下两角）──
        // ⚠️ 这里**不读 now**：now 每帧都变，在组合里读一次就是每帧重组整页。
        //    要跟着帧走的（连击弹一下、进度条）放进 graphicsLayer / Canvas 的 lambda，那是绘制阶段。
        Canvas(Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopStart)) {
            // 进度条：真的是这首歌播到哪了（不是装饰）
            val k = (now / song.seconds.toFloat()).coerceIn(0f, 1f)
            drawRect(judgeColor.copy(alpha = .55f), Offset(0f, 0f), Size(size.width * k, size.height))
        }
        TextButton(onQuit, Modifier.align(Alignment.TopStart).padding(4.dp, 6.dp)) {
            Text(t("退出"), color = ink.copy(alpha = .7f), style = MaterialTheme.typography.labelLarge)
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (combo >= 3) {
                Text(
                    "$combo",
                    Modifier.graphicsLayer {
                        val k = if (!motion) 1f else {
                            val age = now - live.lastJudgeAt
                            if (age in 0f..0.12f) 1f + 0.10f * (1f - age / 0.12f) else 1f
                        }
                        scaleX = k; scaleY = k
                    },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Amber,
                )
                val mult = Rhythm.multAt(combo)
                Text(
                    if (mult > 1f) t("连击 ×%.1f").format(mult) else t("连击"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mult > 1f) goodColor else ink.copy(alpha = .55f),
                )
            }
        }
        Text(
            "%07d".format(score),
            Modifier.align(Alignment.TopEnd).padding(14.dp, 10.dp),
            style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = ink,
        )
        Text(
            t(chart.zh),
            Modifier.align(Alignment.BottomStart).padding(14.dp, 10.dp),
            style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .6f),
        )
        Text(
            t(if (difficulty == "hard") "认真" else "轻松"),
            Modifier.align(Alignment.BottomEnd).padding(14.dp, 10.dp),
            style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .6f),
        )
        judgeShow?.let { j ->
            Text(
                t(when (j) { Rhythm.Judge.PERFECT -> "完美"; Rhythm.Judge.GOOD -> "不错"; else -> "漏了" }),
                Modifier.align(Alignment.Center).graphicsLayer {
                    val age = (now - live.lastJudgeAt).coerceIn(0f, 0.35f)
                    alpha = if (!motion) 1f else 1f - (age / 0.35f) * (age / 0.35f)
                    val k = if (!motion) 1f else 1f + 0.06f * (1f - age / 0.35f)
                    scaleX = k; scaleY = k
                },
                style = MaterialTheme.typography.titleLarge,
                color = when (j) { Rhythm.Judge.PERFECT -> judgeColor; Rhythm.Judge.GOOD -> goodColor; else -> ink.copy(alpha = .5f) },
            )
        }
        // 连击到点跳一次夸奖词（25 / 50 / 100 / 之后每 100）
        praise?.let { (w, at) ->
            PraiseWord(w, at, motion, Modifier.align(Alignment.Center).fillMaxWidth(0.7f).height(120.dp))
        }
        LaunchedEffect(praise) { if (praise != null) { kotlinx.coroutines.delay(950); praise = null } }
        if (countdown > 0) Text(
            "$countdown",
            Modifier.align(Alignment.Center),
            // 舞台是固定深色 → 用舞台自己的暖色常量，不用 Copper（见上面那段注释）
            style = MaterialTheme.typography.displayLarge, color = judgeColor,
        )
    }
}

/**
 * 这一下打在哪条轨上：找这条轨里**最早一个还没判、在判定窗口内、且符合 [want] 的**音符。
 * [want] 就是"这一下是什么打法"：按下只吃 tick / slide 的头，横划只吃 swipe，拖过去只吃 trace。
 * 所以对着一个 swipe 音符猛点是打不中的 —— 它要求的就是划。返回是否打中。
 */
private fun hitLane(live: Live, lane: Int, now: Float, offset: Float, want: (Rhythm.Note) -> Boolean): Boolean {
    if (now < 0f) return false
    val lanes = live.byLane[lane]
    val head = now + offset / 1000f
    // ⚠️ 从游标往后扫，但**不写回 next[lane]**：slide 的头判过了、尾巴还没判，
    //    写回去就等于跳过这个音符，它的尾巴永远轮不到判 —— 一局就少一个判定，
    //    服务端「判定数对不上」直接把这局打回来（实测 77 ≠ 78）。next[lane] 归 judgeMisses 管。
    var i = live.next[lane]
    while (i < lanes.size) {
        val n = lanes[i]
        val err = (head - n.t) * 1000f
        if (err < -Rhythm.GOOD_MS) return false           // 后面的都还太早
        if (n.judged == null && err <= Rhythm.GOOD_MS && want(n)) {
            val j = Rhythm.judge(err)
            if (j == Rhythm.Judge.MISS) return false      // 太晚的交给 judgeMisses 收
            n.judged = j
            live.errs += err
            live.hit(j, now, lane)
            return true
        }
        i++
    }
    return false
}

/** 过了窗口还没判的算 Miss；长按到点了看手指还在不在 */
private fun judgeMisses(live: Live, now: Float, offset: Float, changed: () -> Unit) {
    val head = now + offset / 1000f
    var dirty = false
    live.byLane.forEachIndexed { lane, lanes ->
        var i = live.next[lane]
        while (i < lanes.size) {
            val n = lanes[i]
            if (n.judged == null && (head - n.t) * 1000f > Rhythm.GOOD_MS) {
                n.judged = Rhythm.Judge.MISS; live.hit(Rhythm.Judge.MISS, now, lane); dirty = true
            }
            if (n.judged == null) break
            if (n.hold && !n.tailDone && head >= n.t + n.dur) {
                n.tailDone = true
                live.hit(
                    if (live.held[lane] && n.judged != Rhythm.Judge.MISS) Rhythm.Judge.PERFECT else Rhythm.Judge.MISS,
                    now, lane,
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
    onAgain: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var off by remember { mutableIntStateOf(Rhythm.offsetMs(ctx)) }
    var sent by remember { mutableStateOf<Rhythm.Submitted?>(null) }
    var sending by remember { mutableStateOf(true) }
    // ⚠️ 分数以服务端为准（客户端只报判定计数）。没连上就照实说，别装作发过奖了。
    LaunchedEffect(chartId) {
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
                    " · " + t(if (chartId.endsWith("hard")) "认真" else "轻松"),
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
 * 打击音效。用 [SoundPool] 而不是 MediaPlayer：一局要响一两百次，SoundPool 是为这种短音设计的，
 * 延迟低、能叠着播。样本只有一个（`yx_tap`，我们自己合成的 55 毫秒「嗒」），
 * 靠音量和速率分出「完美」和「不错」两种手感 —— 两个文件没必要。
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
    private var id = 0
    private var ready = false

    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        id = runCatching { pool.load(ctx, app.yxi.R.raw.yx_tap, 1) }.getOrDefault(0)
    }

    fun play(j: Rhythm.Judge, vol: Float) {
        if (!ready || id == 0 || vol <= 0f) return
        when (j) {
            Rhythm.Judge.PERFECT -> pool.play(id, vol, vol, 1, 0, 1.0f)
            Rhythm.Judge.GOOD -> pool.play(id, vol * .55f, vol * .55f, 1, 0, 0.92f)  // 闷一点、低一点，一耳朵听得出差别
            Rhythm.Judge.MISS -> Unit                                                 // 漏了不响：安静本身就是反馈
        }
    }

    fun release() = pool.release()
}

/**
 * 命中的爆点。**结构**参考了下落式音游普遍的做法（老板 2026-09-06 让研究 Phigros 的视频，
 * 我逐帧拆了它的命中特效）：一个实心圆炸开 → 圆缩成小点，外面展开方框 + 45° 菱形 + 两段旋转的弧
 * → 几个小方块朝外上方飘散、减速。**配色和曲线是我们自己的**（铜 / 琥珀，不是他们的金色），
 * 素材一个没拿 —— 全是这儿画出来的几何图形。
 *
 * 时间轴（毫秒，从命中那一刻算）：
 *   0–70    实心圆 r: 1.0 → 0.22 个音符高
 *   40–380  方框 1.6 → 2.3，菱形 1.4 → 2.5（半透明填充），一起淡出
 *   60–380  两段对开的弧 r 0.8 → 1.6，转 55°，线宽 3 → 1
 *   0–380   6 个小方块，朝上方 ±70° 散开，距离按减速曲线走到 2.4 个音符高
 */
private fun DrawScope.hitBurst(cx: Float, cy: Float, age: Float, c: Color, unit: Float) {
    if (age < 0f || age > 0.43f) return
    fun ease(t: Float) = 1f - (1f - t) * (1f - t)             // 减速：前段快、后段慢
    fun life(inS: Float, outS: Float) = ((age - inS) / (outS - inS)).coerceIn(0f, 1f)

    // 实心圆：一上来就是最大，往里缩；**不淡出**，是被缩没的
    if (age < 0.33f) drawCircle(c.copy(alpha = .9f), unit * (1f - 0.82f * ease(life(0f, 0.33f))), Offset(cx, cy))

    // 菱形（45°，半透明填充）：**比方框大**，最早退场
    if (age in 0.017f..0.26f) {
        val k = life(0.017f, 0.26f)
        val d = unit * (1.65f + 0.6f * ease(k))
        rotate(45f, Offset(cx, cy)) {
            drawRect(c.copy(alpha = .33f * (1f - k)), Offset(cx - d, cy - d), Size(d * 2, d * 2))
        }
    }
    // 方框：扩得最急（150ms 就走完九成），也留得最久
    if (age > 0.033f) {
        val k = life(0.033f, 0.43f)
        val e = 1f - (1f - (age - 0.033f).coerceAtMost(0.12f) / 0.12f).let { it * it * it }
        val b = unit * (1.1f + 0.9f * e)
        drawRect(c.copy(alpha = .6f * (1f - k)), Offset(cx - b, cy - b), Size(b * 2, b * 2), style = Stroke(2.5f))
    }
    // 两段对开的弧：**几乎不转**（180ms 转 12°，还在减速）—— 转得明显就不像"打中"，像风车
    if (age in 0.033f..0.40f) {
        val k = life(0.033f, 0.40f)
        val r = unit * (1.5f + 0.5f * ease(k))
        rotate(12f * ease((age / 0.18f).coerceAtMost(1f)), Offset(cx, cy)) {
            for (start in listOf(25f, 205f)) drawArc(
                c.copy(alpha = .75f * (1f - k)), start, 90f, false,
                Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(4f - 2f * k),
            )
        }
    }
    // 小方块：**4 个**，边飞边**变大**再淡掉（不是缩小），方向大致四散
    if (age < 0.34f) {
        val k = life(0f, 0.34f)
        val out = ease(k) * unit * 4.5f
        for (i in 0 until 4) {
            val a = (-140f + i * 55f) * (Math.PI / 180f).toFloat()
            val sz = unit * (0.38f + 0.16f * k)
            drawRect(
                c.copy(alpha = .85f * (1f - k)),
                Offset(cx + kotlin.math.cos(a) * out - sz / 2, cy + kotlin.math.sin(a) * out - sz / 2),
                Size(sz, sz),
            )
        }
    }
}


/** 震动轻重：0 关 · 1 轻 · 2 中 · 3 重。走系统触感常量（不申请震动权限 → 系统里关了触感就自动不震）。 */
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
        sfx.play(Rhythm.Judge.PERFECT, vol)
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
                        drawLine(burst.copy(alpha = .85f), Offset(0f, size.height * .72f), Offset(size.width, size.height * .72f), 4f)
                        if (fx > 0f) hitBurst(size.width / 2f, size.height * .72f, t, burst, 13.dp.toPx() * fx)
                    }
                    Text(
                        t("试一下"), Modifier.align(Alignment.TopStart).padding(10.dp, 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = Color(0xFFE8EDF5).copy(alpha = .7f),
                    )
                }
            }
            Feel(t("音符下落"), when { approach <= 1.2f -> t("快"); approach >= 2.0f -> t("慢"); else -> t("适中") }) {
                approach = when { approach <= 1.2f -> 1.6f; approach <= 1.7f -> 2.2f; else -> 1.1f }
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
private fun praiseFor(combo: Int): String? = Rhythm.tierWord(combo)?.takeIf { it.isNotBlank() }

@Composable
private fun PraiseWord(word: String, at: Long, motion: Boolean, modifier: Modifier = Modifier) {
    var k by remember(at) { mutableFloatStateOf(if (motion) 0f else 1f) }
    LaunchedEffect(at) {
        if (!motion) return@LaunchedEffect
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            k = ((System.nanoTime() - t0) / 1e9f / 0.9f)
            if (k >= 1f) { k = 1f; break }
        }
    }
    val warm = Color(0xFFFFC46B)
    val hot = Color(0xFFFF8A5C)
    val cool = Color(0xFF7FD4F5)
    Box(modifier) {
        // 装饰：跟字同斜度的细斜线 + 斜切色块 + 音符/星点。按词长定死随机，别每帧抖。
        Canvas(Modifier.matchParentSize()) {
            val a = (1f - (k - 0.6f).coerceAtLeast(0f) / 0.4f).coerceIn(0f, 1f)
            val slant = -14f * (Math.PI / 180f).toFloat()
            val seed = word.length * 7
            for (i in 0 until 5) {
                val fx = ((seed + i * 53) % 100) / 100f
                val fy = ((seed + i * 31) % 100) / 100f
                val len = size.height * (0.5f + fx * 0.9f)
                val x0 = size.width * (0.05f + fx * 0.9f)
                val y0 = size.height * (0.1f + fy * 0.8f)
                drawLine(
                    (if (i % 2 == 0) warm else cool).copy(alpha = .5f * a),
                    Offset(x0, y0),
                    Offset(x0 + kotlin.math.sin(slant) * len, y0 - kotlin.math.cos(slant) * len * 0.35f),
                    1.6f,
                )
            }
            for (i in 0 until 4) {                                  // 四角星
                val fx = ((seed + i * 71) % 100) / 100f
                val fy = ((seed + i * 17) % 100) / 100f
                val cx = size.width * (0.08f + fx * 0.86f)
                val cy = size.height * (0.12f + fy * 0.76f)
                val r = size.height * (0.05f + 0.04f * fx) * (0.6f + 0.4f * k)
                val p = Path()
                p.moveTo(cx, cy - r); p.lineTo(cx + r * .28f, cy - r * .28f)
                p.lineTo(cx + r, cy); p.lineTo(cx + r * .28f, cy + r * .28f)
                p.lineTo(cx, cy + r); p.lineTo(cx - r * .28f, cy + r * .28f)
                p.lineTo(cx - r, cy); p.lineTo(cx - r * .28f, cy - r * .28f); p.close()
                drawPath(p, Color.White.copy(alpha = .75f * a))
            }
            for (i in 0 until 3) {                                  // 斜切色块
                val fx = ((seed + i * 43) % 100) / 100f
                val w = size.height * 0.16f
                val cx = size.width * (0.12f + fx * 0.7f)
                val cy = size.height * (0.35f + fx * 0.3f)
                val p = Path()
                p.moveTo(cx, cy - w); p.lineTo(cx + w * .7f, cy - w * .5f)
                p.lineTo(cx + w * .2f, cy + w); p.lineTo(cx - w * .5f, cy + w * .4f); p.close()
                drawPath(p, listOf(hot, warm, cool)[i].copy(alpha = .8f * a))
            }
        }
        // 字：亮色填充 + 深描边 + 外发光。斜体和最重字重都用系统字，不带任何字形文件。
        val style = MaterialTheme.typography.displaySmall.copy(
            fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        )
        val alpha = (1f - (k - 0.65f).coerceAtLeast(0f) / 0.35f).coerceIn(0f, 1f)
        val scale = if (!motion) 1f else 0.86f + 0.14f * (1f - (1f - k.coerceAtMost(0.3f) / 0.3f).let { it * it })
        Box(
            Modifier.align(Alignment.Center).graphicsLayer {
                scaleX = scale; scaleY = scale
                translationY = -size.height * 0.10f * k
                this.alpha = alpha
            },
        ) {
            Text(word, style = style.copy(color = Color(0xFF1B1206), drawStyle = Stroke(width = 9f)))
            Text(word, style = style.copy(color = warm.copy(alpha = .55f), drawStyle = Stroke(width = 5f)))
            Text(word, color = Color(0xFFFFF3E4), style = style)
        }
    }
}
