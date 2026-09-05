package app.yxi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.yxi.agent.Abyss
import app.yxi.agent.Tz
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 深渊 —— 星穹铁道「忘却之庭 · 混沌回忆」的大幅简化版（PRD：`Yxi_Entertainment/design/abyss-prd.md`）。
 *
 * 页面分三块：本期卡（塔名 · 距重置 · 紊流 · 星数进度）→ 12 层的列表 → 点一层进 [FloorSheet] 配队、挑战、看结果。
 * **服务端是唯一真相**（星数 / 发奖 / 归属），这页只画状态；配队时的星数标「预计」，结果页显示服务端回的。
 * 过程动效借 [DropStage] 的颜色语言：3 星金 / 2 星紫 / 1 星蓝，0 星不放特效；落定那一帧只有一行字（STYLE.md）。
 */
@Composable
fun AbyssScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf<Abyss.State?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var openFloor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(reload) {
        loading = true; failed = false
        val r = Abyss.state(ctx)
        if (r == null) {
            failed = true
            // 已经有一份在显示了，刷新失败也要说出来（STYLE.md：一切失败都要说出来），别让人以为这就是最新的
            if (st != null) android.widget.Toast.makeText(ctx, t("刷新失败 —— 显示的是上次的数据"), android.widget.Toast.LENGTH_SHORT).show()
        } else st = r
        loading = false
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("深渊"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )
        val s = st
        when {
            loading && s == null -> Hint(t("正在取…"))
            // ⚠️ 拿不到就是拿不到（没登录 / 网络 / 接口没上线），别说「还没开」—— 那是另一件事，服务端没有这个状态
            s == null -> Hint(t("取不到 —— 网络不通，或者登录过期了。"))
            else -> {
                SeasonCard(s)
                if (s.roster.isEmpty()) Hint(t("还没有角色 —— 先去祈愿抽一位。旅人自己也能打前几层。"))
                s.floors.forEach { f -> FloorRow(f) { openFloor = f.n } }
            }
        }
    }

    val s = st
    if (s != null) openFloor?.let { n ->
        s.floors.firstOrNull { it.n == n }?.let { f ->
            FloorSheet(f, s, onClose = { openFloor = null }, onSettled = { openFloor = null; reload++ })
        }
    }
}

/** 距重置：整天以上说天，不到一天说小时；已过期给空串 */
internal fun untilReset(endsAt: String): String {
    val end = Tz.parse(endsAt) ?: return ""
    val sec = java.time.Duration.between(java.time.Instant.now(), end).seconds
    return when {
        sec <= 0 -> ""
        sec >= 86400 -> t("距重置 %d 天").format(sec / 86400)
        else -> t("距重置 %d 小时").format(maxOf(1, sec / 3600))
    }
}

@Composable
private fun SeasonCard(s: Abyss.State) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Column(Modifier.padding(18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.season.name.ifBlank { t("本期") }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(untilReset(s.season.endsAt), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            s.season.turbulence?.let { tb ->
                Text(
                    t("本期紊流：%s").format(tb.text.ifBlank { tb.name.ifBlank { tb.traits.joinToString(" · ") } }),
                    style = MaterialTheme.typography.bodySmall, color = Amber,
                )
            }
            // 星数进度：每 rewardEvery 星一格，格子亮 = 那一档的奖已经发了
            val segs = if (s.rules.rewardEvery > 0) s.rules.maxStars / s.rules.rewardEvery else 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "★ ${s.totalStars} / ${s.rules.maxStars}",
                    style = MaterialTheme.typography.titleSmall, color = Copper,
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(segs) { i ->
                        val on = s.totalStars >= (i + 1) * s.rules.rewardEvery
                        Box(
                            Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (on) Copper else MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
            // 满星那档写实际配置的东西：素材没到时服务端放的是曦光占位，别把「限定装扮」写死（不骗人）
            val full = s.rules.fullReward
            val fullText = if (full.kind == "cosmetic") full.name.ifBlank { t("本期限定装扮") } else t("曦光 ×%d").format(full.amount)
            Text(
                t("每 %d 星 → 曦光 ×%d · 满 %d 星再加 %s · 自动到账")
                    .format(s.rules.rewardEvery, s.rules.rewardTickets, s.rules.maxStars, fullText),
                style = MaterialTheme.typography.labelSmall, color = Muted,
            )
        }
    }
}

/** 三颗星：拿到的金、没拿到的灰 */
@Composable
private fun Stars(n: Int, size: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(3) { i ->
            Text("★", style = size, color = if (i < n) Copper else MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun FloorRow(f: Abyss.Floor, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("第 %d 层").format(f.n), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Stars(f.myStars)
            }
            Text(
                t("上半 弱点 %s · 难度 %d").format(f.up.weak.joinToString(" · "), f.up.d) + "\n" +
                    t("下半 弱点 %s · 难度 %d").format(f.down.weak.joinToString(" · "), f.down.d),
                style = MaterialTheme.typography.labelSmall, color = Muted,
            )
        }
    }
}

/**
 * 一层：配队 → 挑战 → 结果。整屏 Dialog，返回键关。
 * 规则只有一条要在界面上说清：**每半最多 2 位，同一层里一个角色只能上一半** —— 另一半在用的压暗不可点。
 */
@Composable
private fun FloorSheet(f: Abyss.Floor, s: Abyss.State, onClose: () -> Unit, onSettled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val owned = s.roster.map { it.id }.toSet()
    val max = s.rules.teamSize
    var up by remember(f.n) { mutableStateOf(f.myUp.filter { it in owned }.take(max)) }
    var down by remember(f.n) { mutableStateOf(f.myDown.filter { it in owned && it !in up }.take(max)) }
    var busy by remember(f.n) { mutableStateOf(false) }
    var err by remember(f.n) { mutableStateOf<String?>(null) }
    var result by remember(f.n) { mutableStateOf<Abyss.Result?>(null) }

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp, 22.dp, 18.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("第 %d 层").format(f.n), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    Stars(f.myStars)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "✕", Modifier.clip(CircleShape).clickable(enabled = !busy) { onClose() }.padding(8.dp),
                        style = MaterialTheme.typography.titleMedium, color = Muted,
                    )
                }
                TeamPanel(t("上半"), f.up, s, team = up, other = down, onToggle = { id -> up = toggle(up, id, max) })
                TeamPanel(t("下半"), f.down, s, team = down, other = up, onToggle = { id -> down = toggle(down, id, max) })

                // 预计：公式和服务端一致，但**标「预计」**，结果以服务端回的为准
                val (es, ru, rd) = Abyss.estimate(f, up, down, s)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t("预计"), style = MaterialTheme.typography.labelLarge)
                        Stars(es)
                        Text(
                            t("上半 %.2f× · 下半 %.2f×").format(ru, rd), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = Muted, textAlign = TextAlign.End,
                        )
                    }
                }
                Text(t("每半最多 %d 位，同一层里一个角色只能上一半。旅人每半都在。").format(max), style = MaterialTheme.typography.labelSmall, color = Muted)

                Surface(
                    color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(100.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(100.dp)).clickable(enabled = !busy) {
                        busy = true; err = null
                        scope.launch {
                            when (val o = Abyss.challenge(ctx, f.n, up, down)) {
                                is Abyss.Outcome.Ok -> result = o.result
                                is Abyss.Outcome.Failure -> err = o.message
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(
                        if (busy) t("挑战中…") else t("挑战"), Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center,
                    )
                }
                err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    result?.let { r -> ResultStage(f, r, onClose = { result = null; onSettled() }) }
}

private fun toggle(team: List<String>, id: String, max: Int): List<String> =
    if (id in team) team - id else if (team.size < max) team + id else team

@Composable
private fun TeamPanel(title: String, half: Abyss.Half, s: Abyss.State, team: List<String>, other: List<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$title · " + t("弱点 %s · 难度 %d").format(half.weak.joinToString(" · "), half.d),
            style = MaterialTheme.typography.labelMedium, color = Muted,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 旅人：常驻、不可点
            Chip(label = t("旅人"), sub = "${s.travelerPower}", on = true, enabled = false, art = null) {}
            s.roster.forEach { r ->
                val c = CROWNS.firstOrNull { it.id == r.id }
                val inOther = r.id in other
                val selected = r.id in team
                val hit = r.traits.any { it in half.weak }
                Chip(
                    label = c?.name ?: r.name.ifBlank { r.id },
                    sub = (if (r.dup > 0) t("命座 %d").format(r.dup) + " · " else "") + "${r.power}" + (if (hit) " ✦" else ""),
                    on = selected,
                    enabled = !inOther && (selected || team.size < s.rules.teamSize),
                    art = c,
                    note = if (inOther) t("另一半在用") else null,
                ) { onToggle(r.id) }
            }
        }
    }
}

/** 一位角色的小卡：冠纹章 + 名字 + 命座/战力。✦ = 命中这半的弱点。 */
@Composable
private fun Chip(label: String, sub: String, on: Boolean, enabled: Boolean, art: Crown?, note: String? = null, onClick: () -> Unit) {
    Surface(
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled || on) 1f else 0.45f),
    ) {
        Row(Modifier.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (art != null) CrownArt(art, 26.dp, locked = !enabled && !on)
            else Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(note ?: sub, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

/**
 * 结果：过程动效（星数 → 颜色：3 金 / 2 紫 / 1 蓝，0 星不放）→ 落定帧一行字。
 * 点一下：动效没完就跳过，完了就关。
 */
@Composable
private fun ResultStage(f: Abyss.Floor, r: Abyss.Result, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val play = motion && r.stars > 0
    val clock = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) { if (play) clock.animateTo(1f, tween(DONE.toInt(), easing = LinearEasing)) }
    var skipped by remember { mutableStateOf(!play) }
    val ms = (if (skipped) 1f else clock.value) * DONE
    val tier = remember(r.stars) { Tier.of(when (r.stars) { 3 -> "金"; 2 -> "紫"; else -> "蓝" }) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xF20B0D12))
                .clickable { if (ms >= DONE * 0.95f) onClose() else skipped = true },
        ) {
            if (play) DropStage(tier, ms, reduced = !motion, modifier = Modifier.fillMaxSize())
            // 落定帧：只有字。动效走完之前压着不显示，免得字被光爆盖成一团
            if (ms >= DONE * 0.9f) Column(
                Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(t("第 %d 层").format(f.n), style = MaterialTheme.typography.titleMedium, color = Color(0xFFD6D0C8))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i ->
                        Text("★", style = MaterialTheme.typography.headlineLarge, color = if (i < r.stars) tier.hi else Color(0xFF3A3F4A))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    t("上半 %.2f× · 下半 %.2f×").format(r.up.ratio, r.down.ratio),
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB8B2AA),
                )
                if (r.stars == 0) Text(t("没打过 —— 换个角色配弱点，或者再抽几位。"), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A8580))
                if (r.best > r.stars) Text(t("该层最佳 ★ %d").format(r.best), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A8580))
                // 这次新跨过的档：一档一行，写清到账的是什么（曦光 / 装扮名）
                r.granted.forEach { g ->
                    g.items.forEach { it ->
                        Text(
                            when (it.kind) {
                                "tickets" -> t("累计 %d 星 · 曦光 ×%d 到账").format(g.threshold, it.amount)
                                "cosmetic" -> t("累计 %d 星 · %s 到账").format(g.threshold, it.name.ifBlank { t("本期限定装扮") })
                                else -> t("累计 %d 星").format(g.threshold)
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFD76A),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(t("点一下关闭"), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6E6A66))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
