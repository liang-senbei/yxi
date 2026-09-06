package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Abyss
import app.yxi.agent.Wish
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 活动中心 —— 放各种活动（老板 2026-09-04：「可以塞奇奇怪怪的活动，比如签到什么的」）。
 * 第一个活动是**签到**：签到攒「曦光」，曦光拿去[祈愿][WishScreen]。
 *
 * ⚠️ 接口还没上线时**整块说「还没开通」**，不画一个假的签到日历 ——
 * 画出来的连续天数是假的，用户会照着它以为自己签了。
 */
@Composable
fun ActivityScreen(onAbyss: () -> Unit = {}, onRhythm: () -> Unit = {}, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<Wish.CheckIn?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { st = Wish.checkIn(ctx); loading = false }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("活动中心"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )

        val s = st
        when {
            loading -> Hint(t("正在取…"))
            s == null -> {
                Hint(t("活动暂未开放，敬请期待。"))
                AbyssEntry(onAbyss)
                RhythmEntry(onRhythm)
            }
            else -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Column(Modifier.padding(18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("每日签到"), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    t("已经连着签了 %d 天").format(s.streak),
                                    style = MaterialTheme.typography.labelMedium, color = Muted,
                                )
                            }
                            Surface(
                                color = if (s.checkedInToday) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(100.dp),
                                modifier = Modifier.clip(RoundedCornerShape(100.dp))
                                    .clickable(enabled = !s.checkedInToday && !busy) {
                                        busy = true
                                        scope.launch { Wish.signToday(ctx)?.let { st = it }; busy = false }
                                    },
                            ) {
                                Text(
                                    when {
                                        busy -> t("签到中…")
                                        s.checkedInToday -> t("今天签过了")
                                        else -> t("签到")
                                    },
                                    Modifier.padding(20.dp, 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (s.checkedInToday) Muted else MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        // 最近这些天签没签 —— 一排点，签了的实心
                        if (s.calendar.isNotEmpty()) Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            s.calendar.takeLast(14).forEach { on ->
                                Box(
                                    Modifier.size(if (on) 12.dp else 10.dp).clip(CircleShape)
                                        .background(if (on) Copper else MaterialTheme.colorScheme.outlineVariant),
                                )
                            }
                        }
                        s.nextReward.takeIf { it.isNotBlank() }?.let {
                            Text(
                                t("明日签到奖励：%s").format(it),
                                style = MaterialTheme.typography.labelMedium, color = Amber,
                            )
                        }
                    }
                }
                AbyssEntry(onAbyss)
                RhythmEntry(onRhythm)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/**
 * 活动中心里的**深渊**入口卡：本期塔名 · 我的星数 · 距重置。
 * 本期还没打过（服务端 `played`）→ 图标右上角亮红点；打过一次就消，跨设备一致。
 * ⚠️ 接口拿不到时照样摆卡（是入口不是数据），副标题如实写「取不到」。
 */
@Composable
private fun AbyssEntry(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf<Abyss.State?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val r = Abyss.state(ctx); if (r == null) failed = true else st = r }
    val s = st
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).clickable { onOpen() },
    ) {
        Row(
            Modifier.padding(18.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                YxiIcon(Ico.Crown, size = 24.dp, tint = Color(0xFF7A69E8))
                if (s != null && !s.played) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-3).dp).size(8.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.error),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t("深渊"), style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        s != null -> t("%s · ★ %d / %d · %s").format(
                            s.season.name.ifBlank { t("本期") }, s.totalStars, s.rules.maxStars, untilReset(s.season.endsAt),
                        ).trimEnd(' ', '·')
                        failed -> t("取不到 —— 网络不通，或者登录过期了。")
                        else -> t("正在取…")
                    },
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * 活动中心里的**云曦节拍**（音游）入口卡。副标题是"打过几张谱"，不掺服务端数据，
 * 所以断网也照常摆得出来（成绩现在存本地，服务端契约见 logto_yxi/design/rhythm.md）。
 */
@Composable
private fun RhythmEntry(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val played = app.yxi.agent.Rhythm.SONGS.sumOf { s ->
        app.yxi.agent.Rhythm.DIFFS.count { app.yxi.agent.Rhythm.best(ctx, "${s.id}_$it") != null }
    }
    val total = app.yxi.agent.Rhythm.SONGS.size * app.yxi.agent.Rhythm.DIFFS.size
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).clickable { onOpen() },
    ) {
        Row(
            Modifier.padding(18.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                GlyphIcon(Glyph.Music, Color(0xFF3FA9A0), 24.dp)
                if (played == 0) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-3).dp).size(8.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.error),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t("云曦节拍"), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (played == 0) t("跟着拍子点四条轨。三首曲子，六张谱。")
                    else t("已打过 %d / %d 张谱").format(played, total),
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}
