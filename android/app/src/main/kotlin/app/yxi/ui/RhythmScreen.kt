package app.yxi.ui

import android.media.MediaPlayer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
        val off = Rhythm.offsetMs(ctx)
        if (off != 0) Text(
            t("判定偏移 %d ms（自动校准过）").format(off),
            Modifier.padding(20.dp, 10.dp), style = MaterialTheme.typography.labelSmall, color = Muted,
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
    val flash = FloatArray(4)          // 轨道击中的余光（秒）
    var lastJudge: Rhythm.Judge? = null
    var lastJudgeAt = 0f

    val byLane: List<List<Rhythm.Note>> = (0..3).map { l -> chart.notes.filter { it.lane == l } }

    fun hit(j: Rhythm.Judge, at: Float) {
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
    var score by remember { mutableIntStateOf(0) }
    val ink = MaterialTheme.colorScheme.onSurface
    val laneBg = MaterialTheme.colorScheme.surfaceContainerLow
    val noteColor = MaterialTheme.colorScheme.primary
    val judgeColor = Copper                              // ⚠️ Copper/Muted 是 @Composable getter，Canvas 的 lambda 里取不到，先在这儿取出来
    // 关了系统动画的人：**音符照落**（那是玩法本身，停了就没法玩了），
    // 但轨道的击中余光这类纯装饰不画。STYLE.md 那条管的是装饰，不是内容。
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
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
            if (wall < 3f) { now = wall - 3f; continue }     // 三秒倒计时
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
                live.flash[lane] = now
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
            val judgeY = size.height * 0.82f
            // 轨道分隔
            for (i in 1..3) drawLine(ink.copy(alpha = .07f), Offset(laneW * i, 0f), Offset(laneW * i, size.height), 2f)
            // 击中余光
            if (motion) for (l in 0..3) {
                val age = now - live.flash[l]
                if (age in 0f..0.18f) drawRect(
                    noteColor.copy(alpha = .18f * (1 - age / 0.18f)),
                    Offset(laneW * l, 0f), Size(laneW, size.height),
                )
            }
            // 判定线
            drawLine(judgeColor, Offset(0f, judgeY), Offset(size.width, judgeY), 5f)
            // 音符：只画进入可视窗口的
            val head = now + offset / 1000f
            chart.notes.forEach { n ->
                val dt = n.t - head
                if (dt > Rhythm.APPROACH || n.judged != null && !n.hold) return@forEach
                if (n.judged != null && n.tailDone) return@forEach
                if (dt < -0.4f && !n.hold) return@forEach
                val y = judgeY * (1f - dt / Rhythm.APPROACH)
                val x = laneW * n.lane + laneW * 0.12f
                val w = laneW * 0.76f
                if (n.hold) {
                    val tailY = judgeY * (1f - (n.t + n.dur - head) / Rhythm.APPROACH)
                    drawRoundRect(
                        noteColor.copy(alpha = if (n.judged == Rhythm.Judge.MISS) .25f else .55f),
                        Offset(x, tailY.coerceAtLeast(0f)), Size(w, (y - tailY).coerceAtLeast(6f)),
                        CornerRadius(w / 3),
                    )
                }
                if (n.judged == null && y >= -20f) drawRoundRect(
                    noteColor, Offset(x, y - 9f), Size(w, 18f), CornerRadius(9f),
                )
            }
        }

        // HUD
        Column(Modifier.padding(18.dp)) {
            Text("$score", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            Text(t(chart.zh) + " · " + t(if (difficulty == "hard") "认真" else "轻松"),
                style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        if (combo >= 3) Text(
            "$combo",
            Modifier.align(Alignment.TopCenter).padding(top = 90.dp),
            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Amber,
        )
        live.lastJudge?.let { j ->
            if (now - live.lastJudgeAt < 0.35f) Text(
                t(when (j) { Rhythm.Judge.PERFECT -> "完美"; Rhythm.Judge.GOOD -> "不错"; else -> "漏了" }),
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
                color = when (j) { Rhythm.Judge.PERFECT -> Copper; Rhythm.Judge.GOOD -> Amber; else -> Muted },
            )
        }
        if (now < 0f) Text(
            "${(-now).toInt() + 1}",
            Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.displayLarge, color = Copper,
        )
        TextButton(onQuit, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text(t("退出")) }
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
    live.hit(j, now)
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
                n.judged = Rhythm.Judge.MISS; live.hit(Rhythm.Judge.MISS, now); dirty = true
            }
            if (n.judged == null) break
            if (n.hold && !n.tailDone && head >= n.t + n.dur) {
                n.tailDone = true
                live.hit(if (live.held[lane] && n.judged != Rhythm.Judge.MISS) Rhythm.Judge.PERFECT else Rhythm.Judge.MISS, now)
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
        Text(r.rank, style = MaterialTheme.typography.displayLarge, color = Copper, fontWeight = FontWeight.Bold)
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
