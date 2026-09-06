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
    var result by remember { mutableStateOf<Triple<String, Rhythm.Result, Int>?>(null) }
    var synced by remember { mutableIntStateOf(0) }
    // 规则和最好成绩以服务端为准（分数不是客户端说了算）；拿不到就用本地那份，照样能玩
    LaunchedEffect(Unit) { Rhythm.sync(ctx); synced++ }

    BackHandler(enabled = playing != null || result != null) { playing = null; result = null }

    val r = result
    val p = playing
    when {
        r != null -> ResultCard(r.first, r.second, r.third, onAgain = {
            val song = Rhythm.SONGS.first { it.id == r.first.substringBefore('_') }
            result = null; playing = song to r.first.substringAfter('_')
        }, onBack = { result = null; synced++ }, modifier = modifier)

        p != null -> GameBoard(p.first, p.second, onDone = { id, res, ms ->
            Rhythm.saveBest(ctx, id, res); playing = null; result = Triple(id, res, ms)
        }, onQuit = { playing = null }, modifier = modifier)

        else -> SongList(synced, onPick = { s, d -> playing = s to d }, modifier = modifier)
    }
}

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
        // 音效 / 震动开关：有人在安静的地方玩，得给关掉的路
        var sound by remember { mutableStateOf(Rhythm.soundOn(ctx)) }
        var haptic by remember { mutableStateOf(Rhythm.hapticOn(ctx)) }
        Row(Modifier.padding(20.dp, 10.dp, 20.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Toggle(t("打击音效"), sound) { sound = !sound; Rhythm.setSoundOn(ctx, sound) }
            Toggle(t("震动"), haptic) { haptic = !haptic; Rhythm.setHapticOn(ctx, haptic) }
        }
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

    val byLane: List<List<Rhythm.Note>> = (0..3).map { l -> chart.notes.filter { it.lane == l } }

    fun hit(j: Rhythm.Judge, at: Float, lane: Int = -1) {
        if (lane in 0..3) { flash[lane] = at; flashJudge[lane] = j }
        onJudge?.invoke(j, lane)
        when (j) {
            Rhythm.Judge.PERFECT -> { perfect++; combo++ }
            Rhythm.Judge.GOOD -> { good++; combo++ }
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
    onDone: (String, Rhythm.Result, Int) -> Unit,
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
    var score by remember { mutableIntStateOf(0) }
    // ⚠️ 舞台**固定深色**，不跟浅色/深色主题走：音游得让音符和特效跳出来，
    //    浅色底上一整片发白、命中的光一点都看不见。这是这一页的例外，别推广到别处。
    val ink = Color(0xFFE8EDF5)
    val laneBg = Color(0xFF0E1117)
    val stageTop = Color(0xFF141A24)
    val noteColor = Color(0xFF5CC8F5)                    // 冷青：在深色底上最跳，跟铜色的命中光是补色关系
    // ⚠️ 舞台是固定深色，配色也固定取**深色皮肤那一套暖色**：浅色皮肤里的 `Copper` 其实是
    //    Google 蓝（Palette.kt:87），在深色舞台上会跟青色音符糊成一片，命中光也不像"打中了"。
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
    // ── 横屏（老板 2026-09-06 看了 Phigros 之后定的：音游要横版）──
    // 整个 App 是竖屏的，只有这一页转过去。三条前提：
    // ① 还原写在 onDispose 里 —— 页面**怎么离开都还原**（返回键、手势、切后台、被杀），
    //    不能只挂在「退出」按钮上；还原成 UNSPECIFIED 而不是硬写 PORTRAIT，免得跟以后的横屏页打架。
    // ② **不要**去 Manifest 给 Activity 加 screenOrientation —— 那会影响所有页面。
    // ③ ⚠️ **依赖 MainActivity 的 `android:configChanges="orientation|screenSize|…"`**：
    //    有它转屏才不重建 Activity，正在打的这一局才不会被清掉。这一页 rememberSaveable 用量是 0，
    //    哪天有人删了那行 configChanges，转屏的一瞬间整局游戏会丢干净。删之前先来改这里。
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull() }
    DisposableEffect(activity) {
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = old ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // 状态栏收起来（横屏玩的时候顶上那条信号电量很碍眼）；跟朝向一样，离开就还原
    DisposableEffect(activity) {
        val w = activity?.window
        val c = w?.let { androidx.core.view.WindowInsetsControllerCompat(it, it.decorView) }
        c?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { c?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
    }

    val press = remember { FloatArray(4) { -9f } }
    val view = LocalView.current
    val sfx = remember { RhythmSfx(ctx) }
    DisposableEffect(sfx) { onDispose { sfx.release() } }
    // ⚠️ **不要 remember**：在结算页点「再来一次」会用同样的 song/difficulty 重进，
    //    remember 会把上一局的开关值留着 —— 玩家刚在选曲页关掉音效，再来一局还在响。
    val soundOn = Rhythm.soundOn(ctx)
    val hapticOn = Rhythm.hapticOn(ctx)
    // 打击反馈：判定发生的那一瞬，声音和震动一起来 —— 这两样是"打击感"的主体，画面只是补
    DisposableEffect(live, soundOn, hapticOn) {
        live.onJudge = { j, _ ->
            judgeShow = j
            if (soundOn) sfx.play(j)
            if (hapticOn) when (j) {
                // 完美清脆一点、不错闷一点；漏了不震 —— 漏的时候再震一下是惩罚，不是反馈
                Rhythm.Judge.PERFECT -> view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                Rhythm.Judge.GOOD -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                Rhythm.Judge.MISS -> Unit
            }
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
                onDone(chart.id, live.result(), (System.currentTimeMillis() - startedAt).toInt()); return@LaunchedEffect
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(laneBg).pointerInput(chart) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val lane = ((down.position.x / size.width) * 4).toInt().coerceIn(0, 3)
                live.held[lane] = true
                press[lane] = now                     // 按下去就先亮一下（不管有没有打中），手感的一半在这
                tapLane(live, lane, now, offset) { combo = live.combo; score = live.currentScore() }
                do {
                    val ev = awaitPointerEvent()
                    val up = ev.changes.all { !it.pressed }
                    if (up) live.held[lane] = false
                } while (!up)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val laneW = size.width / 4f
            val judgeY = size.height * 0.80f
            val noteH = (size.height * 0.026f).coerceIn(14f, 30f)   // 横版屏幕矮，音符按高度算，别用固定像素

            // ── 轨道：越靠近判定线越亮一点（静态渐层，减弱动效也留着）──
            drawRect(Brush.verticalGradient(listOf(stageTop, laneBg)), Offset(0f, 0f), Size(size.width, size.height))
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.7f to noteColor.copy(alpha = .05f), 1f to noteColor.copy(alpha = .14f),
                ),
                Offset(0f, 0f), Size(size.width, judgeY),
            )
            for (i in 1..3) drawLine(ink.copy(alpha = .08f), Offset(laneW * i, 0f), Offset(laneW * i, judgeY), 2f)

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
                Offset(0f, judgeY - 26f - 16f * lineGlow), Size(size.width, 26f + 16f * lineGlow),
            )
            drawLine(judgeColor.copy(alpha = .85f), Offset(0f, judgeY), Offset(size.width, judgeY), 5f + 2f * lineGlow)
            drawLine(Color.White.copy(alpha = .35f + .35f * lineGlow), Offset(0f, judgeY - 1.5f), Offset(size.width, judgeY - 1.5f), 1.5f)

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
                    hitBurst(cx, judgeY, age, if (j == Rhythm.Judge.PERFECT) judgeColor else goodColor, noteH * 1.1f)
                }
            }

            // ── 音符：圆角条 + 顶部高光，跟气泡/药丸同一套玻璃语言，不是一块死色 ──
            val head = now + offset / 1000f
            chart.notes.forEach { n ->
                val dt = n.t - head
                if (dt > Rhythm.APPROACH || n.judged != null && !n.hold) return@forEach
                if (n.judged != null && n.tailDone) return@forEach
                if (dt < -0.4f && !n.hold) return@forEach
                val y = judgeY * (1f - dt / Rhythm.APPROACH)
                // 音符比轨道窄、居中：横版一条轨 600px 宽，照轨宽画就是一根大棍子。
                // 判定还是按整条轨算（点哪儿都算），只是画得秀气些 —— 手感不变，好看。
                val w = minOf(laneW * 0.72f, size.width * 0.10f)
                val x = laneW * n.lane + (laneW - w) / 2f
                if (n.hold) {
                    // 长按的条：**按到判定线为止**。头过了线就从下往上一点点被"吃掉"，
                    // 而不是继续往下画 —— 画到线以下等于告诉玩家"这段还要按"，其实早过去了。
                    val tailY = (judgeY * (1f - (n.t + n.dur - head) / Rhythm.APPROACH)).coerceAtLeast(0f)
                    val bottom = minOf(y, judgeY)
                    if (bottom > tailY) {
                        val holding = n.judged != null && n.judged != Rhythm.Judge.MISS && live.held[n.lane]
                        drawRoundRect(
                            noteColor.copy(alpha = if (n.judged == Rhythm.Judge.MISS) .18f else if (holding) .62f else .42f),
                            Offset(x, tailY), Size(w, bottom - tailY), CornerRadius(noteH / 2),
                        )
                        drawRoundRect(                                   // 左侧一道细高光，让它像有厚度
                            Color.White.copy(alpha = .18f),
                            Offset(x + w * .12f, tailY), Size(w * .10f, bottom - tailY), CornerRadius(noteH / 3),
                        )
                    }
                }
                if (n.judged == null && y >= -20f && y <= judgeY + noteH) {
                    // 胶囊 + 一圈描边 + 顶部高光：横版下判定线很长，音符得一眼看清在哪条轨
                    val h = noteH
                    drawRoundRect(rimColor, Offset(x - 3f, y - h / 2 - 3f), Size(w + 6f, h + 6f), CornerRadius(h))
                    drawRoundRect(noteColor, Offset(x, y - h / 2), Size(w, h), CornerRadius(h / 2))
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = .45f), Color.Transparent)),
                        Offset(x, y - h / 2), Size(w, h * .5f), CornerRadius(h / 2),
                    )
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
                Text(t("连击"), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .55f))
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
        if (countdown > 0) Text(
            "$countdown",
            Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.displayLarge, color = Copper,
        )
    }
}

/** 这一拍点在哪条轨上：找这条轨最早一个还没判、且在窗口里的音符 */
private fun tapLane(live: Live, lane: Int, now: Float, offset: Float, changed: () -> Unit) {
    if (now < 0f) return
    val lanes = live.byLane[lane]
    var i = live.next[lane]
    val head = now + offset / 1000f
    // ⚠️ 只往前找目标，**不要写回 next[lane]**：长按的头判过了、尾巴还没判，
    //    写回去就等于跳过这个音符，它的尾巴永远轮不到判 —— 一局就少一个判定，
    //    服务端「判定数对不上」直接把这局打回来（实测 77 ≠ 78）。next[lane] 归 judgeMisses 管。
    while (i < lanes.size && lanes[i].judged != null) i++
    if (i >= lanes.size) return
    val n = lanes[i]
    val err = (head - n.t) * 1000f
    if (err < -Rhythm.GOOD_MS) return                         // 太早，不算这一下（也不罚）
    val j = Rhythm.judge(err)
    if (j == Rhythm.Judge.MISS) return                        // 太晚的由 judgeMisses 收
    n.judged = j
    live.errs += err
    live.hit(j, now, lane)
    changed()
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

private fun Live.currentScore() = Rhythm.score(chart.units, perfect, good, maxCombo)

// ── 结算 ────────────────────────────────────────────────────────────────────
@Composable
private fun ResultCard(
    chartId: String, local: Rhythm.Result, elapsedMs: Int,
    onAgain: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var off by remember { mutableIntStateOf(Rhythm.offsetMs(ctx)) }
    var sent by remember { mutableStateOf<Rhythm.Submitted?>(null) }
    var sending by remember { mutableStateOf(true) }
    // ⚠️ 分数以服务端为准（客户端只报判定计数）。没连上就照实说，别装作发过奖了。
    LaunchedEffect(chartId) {
        sent = Rhythm.submit(ctx, chartId, local.perfect, local.good, local.miss, local.maxCombo, elapsedMs)
        sent?.result?.let { Rhythm.saveBest(ctx, chartId, it.copy(medianErrMs = local.medianErrMs)) }
        sending = false
    }
    val r = sent?.result?.copy(
        perfect = local.perfect, good = local.good, miss = local.miss, medianErrMs = local.medianErrMs,
    ) ?: local
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
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
        Text(
            r.rank, Modifier.graphicsLayer { scaleX = pop; scaleY = pop; alpha = ((pop - 0.7f) / 0.3f).coerceIn(0f, 1f) },
            style = MaterialTheme.typography.displayLarge, color = Copper, fontWeight = FontWeight.Bold,
        )
        Text("${r.score}", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        Text(t("准度 %.1f%%").format(r.acc * 100), style = MaterialTheme.typography.titleMedium, color = Amber)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat(t("完美"), r.perfect); Stat(t("不错"), r.good); Stat(t("漏了"), r.miss); Stat(t("连击"), r.maxCombo)
        }
        // 成绩上传的状态：发了什么、没发成为什么，都摆出来
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
            color = if (up?.granted?.isNotEmpty() == true) Amber else Muted,
        )
        // 判定偏差大到该校准了就直说，别让人以为是自己手残
        if (abs(r.medianErrMs) >= 30) Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp, 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t(if (r.medianErrMs > 0) "这局你平均按晚了 %d 毫秒" else "这局你平均按早了 %d 毫秒").format(abs(r.medianErrMs)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton({ off -= r.medianErrMs; Rhythm.setOffsetMs(ctx, off) }) { Text(t("按这个校准判定")) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onAgain) { Text(t("再来一次")) }
        TextButton(onBack) { Text(t("换一首")) }
    }
}

@Composable
private fun Stat(label: String, v: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$v", style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
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

    fun play(j: Rhythm.Judge) {
        if (!ready || id == 0) return
        when (j) {
            Rhythm.Judge.PERFECT -> pool.play(id, .9f, .9f, 1, 0, 1.0f)
            Rhythm.Judge.GOOD -> pool.play(id, .5f, .5f, 1, 0, 0.92f)   // 闷一点、低一点，一耳朵听得出差别
            Rhythm.Judge.MISS -> Unit                                    // 漏了不响：安静本身就是反馈
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
    val k = (age / 0.38f).coerceIn(0f, 1f)
    if (k >= 1f) return
    val fade = 1f - k * k                                   // 后段才明显淡出，前段保持亮

    // 实心圆：炸开的那一下，很快缩成一个点
    val ck = (age / 0.07f).coerceIn(0f, 1f)
    drawCircle(c.copy(alpha = .85f * fade), unit * (1.0f - 0.78f * ck), Offset(cx, cy))

    if (age > 0.04f) {
        // 方框（正着放）+ 菱形（转 45°，半透明填充）
        val bs = unit * (1.6f + 0.7f * k)
        drawRect(c.copy(alpha = .55f * fade), Offset(cx - bs, cy - bs), Size(bs * 2, bs * 2), style = Stroke(2f))
        val ds = unit * (1.4f + 1.1f * k)
        rotate(45f, Offset(cx, cy)) {
            drawRect(c.copy(alpha = .18f * fade), Offset(cx - ds, cy - ds), Size(ds * 2, ds * 2))
        }
    }
    if (age > 0.06f) {
        // 两段对开的弧，边转边扩
        val r = unit * (0.8f + 0.8f * k)
        val sw = 3f - 2f * k
        rotate(55f * k, Offset(cx, cy)) {
            for (start in listOf(20f, 200f)) drawArc(
                c.copy(alpha = .7f * fade), start, 110f, false,
                Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(sw),
            )
        }
    }
    // 小方块：朝上方散开，越飘越慢（减速用 1-(1-k)^2）
    val out = 1f - (1f - k) * (1f - k)
    for (i in 0 until 6) {
        val a = (-90f + (i - 2.5f) * 26f) * (Math.PI / 180f).toFloat()
        val d = unit * 2.4f * out * (0.7f + 0.1f * i)
        val sz = unit * (0.22f - 0.08f * k)
        drawRect(
            c.copy(alpha = .8f * fade),
            Offset(cx + kotlin.math.cos(a) * d - sz, cy + kotlin.math.sin(a) * d - sz),
            Size(sz * 2, sz * 2),
        )
    }
}
