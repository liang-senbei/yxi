package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Day
import app.yxi.agent.Today
import app.yxi.agent.Usage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 用量显示（PRD P0-13）：5 小时窗口 + 今日花费 + 近 7 天，**读那台机自己的 ~/.claude**（core 的 Usage）。
 *
 * 形态照手机端：主机分组头下面一条紧凑条，点开是详情弹窗。
 * ccusage 不在 = 快照三个字段全 null → **整块不画**（Usage 的规矩：显示一个假的比不显示危险得多）。
 */

/** 一台主机的一轮用量快照。三个字段都可能 null（ccusage 三个命令读到几样算几样）。 */
data class UsageSnap(val at: Long, val blocks: Usage?, val today: Today?, val week: List<Day>?) {
    /** 全 null = 这台机器上探不到 ccusage，界面上一个像素都不该出现 */
    val hasData: Boolean get() = blocks != null || today != null || week != null
}

/** 进程级缓存，键 = 主机 id。侧栏收起再展开不清零；刷新间隔 5 分钟，弹窗里的「刷新」立刻拉。 */
object UsageCache {
    private val byHost = HashMap<String, MutableState<UsageSnap?>>()
    private val busy = HashSet<String>()

    fun state(hostId: String): MutableState<UsageSnap?> =
        synchronized(byHost) { byHost.getOrPut(hostId) { mutableStateOf(null) } }

    /** 拉一轮（probe + today + daily 各自容错）。⚠️ 取消要重抛：吞了它会把好端端的缓存覆盖成全 null。 */
    suspend fun refresh(c: Conn) {
        synchronized(busy) { if (!busy.add(c.host.id)) return }
        try {
            if (!c.ssh.isConnected) return
            val blocks = forgiving { Usage.probe(c.ssh) }
            val today = forgiving { Usage.today(c.ssh) }
            val week = forgiving { Usage.daily(c.ssh, 7) }
            state(c.host.id).value = UsageSnap(System.currentTimeMillis(), blocks, today, week)
        } finally {
            synchronized(busy) { busy.remove(c.host.id) }
        }
    }

    private suspend fun <T> forgiving(block: suspend () -> T): T? =
        runCatching { block() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
}

private const val REFRESH_MS = 5 * 60 * 1000L

/**
 * 主机分组头下面的紧凑用量条：5h 窗口进度 + 剩余 + 今日花费。
 * 连接断开不画（exec 断线返回空串，会被当成「没数据」—— 那正好，宁可空着）。
 * 刷新循环挂在侧栏组合上：侧栏收起就停，展开时过了 5 分钟先刷一轮再画。
 */
@Composable
fun UsageStrip(c: Conn) {
    val snapState = remember(c.host.id) { UsageCache.state(c.host.id) }
    // ⚠️ 刷新循环必须在 early return **之前**：放后面的话「还没数据」时它根本不进组合，永远没有第一轮
    LaunchedEffect(c) {
        while (true) {
            val s = snapState.value
            if (c.ssh.isConnected && (s == null || System.currentTimeMillis() - s.at > REFRESH_MS)) UsageCache.refresh(c)
            delay(30_000)
        }
    }
    val snap = snapState.value ?: return
    // 没数据不画。⚠️ 是「还没拉到」也不画 —— 第一轮回来前侧栏就先不闪这条，等下一轮自然出现
    if (!snap.hasData) return
    val t = Tokens.current
    var open by remember { mutableStateOf(false) }
    // 5h 窗口没有 active block（刚开新窗口 / 这会儿没在跑）时 blocks 是 null —— 今日/本周有数据照样显示这条
    val texts = listOfNotNull(
        snap.blocks?.let { "5h 剩 " + it.remainText },
        snap.today?.let { "今日 " + it.costText },
        snap.week?.takeIf { it.isNotEmpty() }?.let { "本周 " + cost(it.sumOf { d -> d.costUSD }) },
    )
    if (texts.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 10.dp, top = 1.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp)).clickable { open = true },
    ) {
        snap.blocks?.let { b ->
            Box(Modifier.fillMaxWidth().height(3.dp).background(t.border, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier.fillMaxWidth(b.elapsedPercent.coerceIn(3, 100) / 100f)
                        .height(3.dp).background(t.warning, RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(texts.joinToString("  "), Modifier.padding(top = 3.dp), fontSize = 10.sp, color = t.textMuted)
    }
    if (open) UsageDialog(c, snapState) { open = false }
}

/** 用量详情：5 小时窗口 / 今天 / 近 7 天三段。「刷新」立刻重拉一轮。 */
@Composable
fun UsageDialog(c: Conn, snapState: MutableState<UsageSnap?>, onClose: () -> Unit) {
    val snap = snapState.value
    val t = Tokens.current
    var refreshing by remember { mutableStateOf(false) }
    WorkbenchDialog(
        onDismissRequest = onClose,
        title = { Text("用量 · " + c.host.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (snap == null || !snap.hasData) {
                    Text("这台机器上探不到 ccusage，读不到用量。", style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
                } else {
                    snap.blocks?.let { b ->
                        Section("5 小时窗口") {
                            Box(Modifier.fillMaxWidth().height(5.dp).background(t.border, RoundedCornerShape(3.dp))) {
                                Box(
                                    Modifier.fillMaxWidth(b.elapsedPercent.coerceIn(1, 100) / 100f).height(5.dp)
                                        .background(if (b.elapsedPercent >= 80) t.danger else t.accent, RoundedCornerShape(3.dp)),
                                )
                            }
                            Text(
                                "已过 ${b.elapsedPercent}% · 剩 ${b.remainText}  ·  ${b.tokenText} tokens  ${cost(b.costUSD)}",
                                style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
                            )
                        }
                    }
                    snap.today?.let { day ->
                        Section("今天") {
                            Text("${day.tokenText} tokens  ${cost(day.costUSD)}", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                        }
                    }
                    snap.week?.takeIf { it.isNotEmpty() }?.let { week ->
                        Section("近 ${week.size} 天") {
                            val max = week.maxOf { it.tokens }.coerceAtLeast(1)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                week.forEach { d -> DayBar(d, d.tokens.toFloat() / max) }
                            }
                            Text(
                                "合计 ${tokenShort(week.sumOf { it.tokens })} tokens  ${cost(week.sumOf { it.costUSD })}",
                                style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                            )
                        }
                    }
                }
                Text(
                    if (snap == null) "正在读那台机器的用量…" else "读于 " + ago(snap.at) + " · ccusage 按那台机的价目表算钱",
                    style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !refreshing, onClick = {
                refreshing = true
                CoroutineScope(Dispatchers.IO).launch { UsageCache.refresh(c); refreshing = false }
            }) { Text(if (refreshing) "刷新中…" else "刷新") }
        },
        dismissButton = { TextButton(onClose) { Text("关掉") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val t = Tokens.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = t.textPrimary)
        content()
    }
}

/** 一天的条：日期 + 比例条 + tokens / 钱。 */
@Composable
private fun DayBar(d: Day, frac: Float) {
    val t = Tokens.current
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Text(d.short, style = CodeStyle, color = t.textMuted, modifier = Modifier.width(44.dp))
        Box(Modifier.weight(1f).height(6.dp).background(t.border, RoundedCornerShape(3.dp))) {
            Box(
                Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(6.dp)
                    .background(t.accent.copy(alpha = 0.75f), RoundedCornerShape(3.dp)),
            )
        }
        Text("${d.tokenText}  ${cost(d.costUSD)}", style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
    }
}

/** ⚠️ 两位小数：一天烧个位数美元是常态，取整会看见一串 $0（core Today 的同一条规矩）。 */
private fun cost(usd: Double): String = "$" + "%.2f".format(usd)

private fun tokenShort(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.0fM".format(n / 1e6)
    n >= 1_000 -> "%.0fK".format(n / 1e3)
    else -> n.toString()
}
