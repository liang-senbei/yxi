package app.yxi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Day
import app.yxi.agent.Usage
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted

/**
 * 趋势 —— **每天烧了多少 token、花了多少钱、用的哪些模型**（用户 2026-09-04 要的）。
 *
 * 数据来自那台机器自己的 `ccusage daily --json`（[Usage.daily]），所以**天然按服务器分开**：
 * 你在别的机器上烧的不会算进来。
 *
 * ⚠️ **探不到 ccusage 就明说**，不画一张空图假装有数据 ——
 * 「显示一个假的比不显示危险得多」是这个 App 关于额度的第一条规矩（见 [Usage] 的注释）。
 */
@Composable
fun TrendScreen(ssh: SshSession?, modifier: Modifier = Modifier) {
    var days by remember(ssh) { mutableStateOf<List<Day>?>(null) }
    var loading by remember(ssh) { mutableStateOf(true) }
    LaunchedEffect(ssh) {
        loading = true
        days = ssh?.let { app.yxi.ssh.catching { Usage.daily(it) }.getOrNull() }
        loading = false
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("趋势"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )

        val list = days
        when {
            loading -> Hint(t("正在读这台机器的用量…"))
            list.isNullOrEmpty() -> Hint(
                t("这台机器上没有 ccusage —— 装上它才有按天的用量。"),
            )
            else -> {
                // 选中哪一天：默认最后一天（今天）
                var sel by remember(list.size) { mutableIntStateOf(list.lastIndex) }
                val pick = list.getOrNull(sel) ?: list.last()

                // ── 选中那天的大字
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Column(Modifier.padding(18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pick.date, style = MaterialTheme.typography.labelMedium, color = Muted)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                pick.tokenText,
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                                color = Copper,
                            )
                            Text(
                                "$" + "%.2f".format(pick.costUSD),
                                Modifier.padding(bottom = 4.dp),
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                color = Amber,
                            )
                        }
                        Text(t("token · 美元"), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }

                // ── 柱状图：一天一根，点一下选中
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Column(Modifier.padding(14.dp, 16.dp)) {
                        val maxT = list.maxOf { it.tokens }.coerceAtLeast(1L)
                        val bar = Copper
                        val dim = MaterialTheme.colorScheme.outlineVariant
                        Canvas(
                            Modifier.fillMaxWidth().height(170.dp)
                                .pointerInput(list.size) {
                                    detectTapGestures { p ->
                                        val w = size.width / list.size
                                        sel = (p.x / w).toInt().coerceIn(0, list.lastIndex)
                                    }
                                },
                        ) {
                            val w = size.width / list.size
                            list.forEachIndexed { i, d ->
                                val h = (d.tokens.toFloat() / maxT) * size.height
                                val on = i == sel
                                drawRoundRect(
                                    if (on) bar else dim,
                                    topLeft = Offset(i * w + w * 0.18f, size.height - h),
                                    size = Size(w * 0.64f, h),
                                    cornerRadius = CornerRadius(w * 0.32f),
                                    alpha = if (on) 1f else 0.55f,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // 横坐标：只标首、中、尾三个日期 —— 30 根柱子标满就糊成一片
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(list.first(), list[list.size / 2], list.last()).forEach {
                                Text(
                                    it.short,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Muted,
                                )
                            }
                        }
                    }
                }

                // ── 选中那天用了哪些模型，各花了多少
                if (pick.models.isNotEmpty()) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Column(Modifier.padding(18.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t("这天用了哪些模型"), style = MaterialTheme.typography.labelMedium, color = Muted)
                        pick.models.sortedByDescending { it.costUSD }.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    tokenShort(m.tokens),
                                    Modifier.padding(end = 12.dp),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = Muted,
                                )
                                Text(
                                    "$" + "%.2f".format(m.costUSD),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = Amber,
                                )
                            }
                        }
                    }
                }

                // ── 区间合计
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    Row(
                        Modifier.padding(18.dp, 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t("这 %d 天合计").format(list.size),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            tokenShort(list.sumOf { it.tokens }),
                            Modifier.padding(end = 12.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = Muted,
                        )
                        Text(
                            "$" + "%.2f".format(list.sumOf { it.costUSD }),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                            color = Amber,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「我的」那张趋势卡上的小折线 —— 最近若干天的 token 走势，一眼看个形状。
 * 数据拿不到就整块不画（调用方负责）。
 */
@Composable
fun TrendSpark(days: List<Day>, modifier: Modifier = Modifier) {
    val line = Copper
    Canvas(modifier) {
        if (days.size < 2) return@Canvas
        val maxT = days.maxOf { it.tokens }.coerceAtLeast(1L)
        val w = size.width / days.size
        days.forEachIndexed { i, d ->
            val h = (d.tokens.toFloat() / maxT) * size.height
            drawRoundRect(
                line,
                topLeft = Offset(i * w + w * 0.2f, size.height - h),
                size = Size(w * 0.6f, h),
                cornerRadius = CornerRadius(w * 0.3f),
                alpha = 0.35f + 0.65f * (i.toFloat() / days.lastIndex),
            )
        }
    }
}

private fun tokenShort(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.0fM".format(n / 1e6)
    n >= 1_000 -> "%.0fK".format(n / 1e3)
    else -> n.toString()
}

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(
            text, Modifier.padding(18.dp, 16.dp),
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
    }
}
