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
fun ActivityScreen(modifier: Modifier = Modifier) {
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
            s == null -> Hint(
                t("活动还没开通。开了之后签到、限时活动都在这儿 —— 签到攒的「曦光」拿去祈愿。"),
            )
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
                                t("明天签到给：%s").format(it),
                                style = MaterialTheme.typography.labelMedium, color = Amber,
                            )
                        }
                    }
                }
                Hint(t("更多活动还没上。有了会摆在这儿。"))
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
