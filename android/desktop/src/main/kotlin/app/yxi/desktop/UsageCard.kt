package app.yxi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Day
import app.yxi.agent.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 每台服务器近半年的每日用量（M4n，规格表 §3.8 新会话页统计卡）：来自那台机器上的 `ccusage daily --json`（[Usage.daily]，钱是 ccusage 按价目表算的）。
 * 每台主机这次运行只读一次：在调用方给的 App 级 scope 里读，切页面、改草稿都不会把它取消（斜杠菜单那次的教训）；没装 ccusage / 读失败记成 null，卡片整块不显示。
 */
internal object UsageHistory {
    private val byHost = mutableStateMapOf<String, List<Day>?>()
    private val loading = HashSet<String>()

    /** 读过的结果：没读过和没有数据都是 null。 */
    fun of(hostId: String): List<Day>? = byHost[hostId]

    fun load(c: Conn, scope: CoroutineScope) {
        val id = c.host.id
        if (id in byHost || c.status != Conn.Status.Connected || !loading.add(id)) return
        scope.launch {
            try { byHost[id] = try { Usage.daily(c.ssh, 182) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
            finally { loading.remove(id) }
        }
    }

    /** 截图夹具用：直接放一份数据（不连服务器）。 */
    internal fun put(hostId: String, days: List<Day>?) { byHost[hostId] = days }
    /** 截图夹具每张图前清空（这是全局的，放过数据的场景会串到后面同一台主机的新会话页）。 */
    internal fun clearForFixture() { byHost.clear(); synchronized(loading) { loading.clear() } }
}

internal enum class UsageRange(val label: String, val days: Long?) { All("全部", null), Month("30 天", 30), Week("7 天", 7) }

/** 统计卡上的六个数（参照端的六个标签没量到，规格表 §3.8；这里用 Yxi 能真算出来的，PRD §14.1）。 */
internal data class UsageSummary(val tokens: Long, val costUSD: Double, val activeDays: Int, val longestStreak: Int, val averageTokens: Long, val topModel: String?)

internal object UsageStats {
    private fun inRange(days: List<Day>, range: UsageRange, today: LocalDate): List<Pair<LocalDate, Day>> =
        days.mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }
            .filter { (date, _) -> !date.isAfter(today) && (range.days == null || ChronoUnit.DAYS.between(date, today) < range.days) }

    fun summary(days: List<Day>, range: UsageRange, today: LocalDate): UsageSummary {
        val picked = inRange(days, range, today).filter { it.second.tokens > 0 }
        val dates = picked.map { it.first }.toSortedSet()
        var longest = 0; var run = 0; var previous: LocalDate? = null
        for (date in dates) { run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1; longest = maxOf(longest, run); previous = date }
        val tokens = picked.sumOf { it.second.tokens }
        return UsageSummary(tokens, picked.sumOf { it.second.costUSD }, dates.size, longest, if (dates.isEmpty()) 0 else tokens / dates.size,
            models(days, range, today).firstOrNull()?.first)
    }

    /** 各模型的 tokens，从多到少。 */
    fun models(days: List<Day>, range: UsageRange, today: LocalDate): List<Pair<String, Long>> =
        inRange(days, range, today).flatMap { it.second.models }.groupBy { it.name }.mapValues { (_, v) -> v.sumOf { it.tokens } }
            .filterValues { it > 0 }.toList().sortedByDescending { it.second }

    /**
     * 热力图（规格表 §3.8：26 列 × 7 行）：一列一周（周一在上），最后一列是今天所在的那周，今天之后的格子是 null（不画）。
     * 0 = 没用；有用量的日子按 tokens 在这半年里的三分位分成 1 / 2 / 3 档（规格表 §1.4 只见到三档非空色）。
     */
    fun heatmap(days: List<Day>, today: LocalDate): List<List<Int?>> {
        val start = today.with(DayOfWeek.MONDAY).minusWeeks(25)
        val used = inRange(days, UsageRange.All, today).filter { !it.first.isBefore(start) && it.second.tokens > 0 }.associate { it.first to it.second.tokens }
        val sorted = used.values.sorted()
        fun level(t: Long): Int {
            if (t <= 0) return 0
            if (sorted.size < 3) return 3
            return when { t <= sorted[sorted.size / 3] -> 1; t <= sorted[sorted.size * 2 / 3] -> 2; else -> 3 }
        }
        return (0 until 26).map { week -> (0 until 7).map { day ->
            val date = start.plusWeeks(week.toLong()).plusDays(day.toLong())
            if (date.isAfter(today)) null else level(used[date] ?: 0)
        } }
    }

    fun tokenText(t: Long): String = when {
        t >= 1_000_000_000 -> "%.1fB".format(t / 1e9)
        t >= 1_000_000 -> "%.1fM".format(t / 1e6)
        t >= 1_000 -> "%.0fK".format(t / 1e3)
        else -> t.toString()
    }
}

/**
 * 新会话页的统计卡（M4n，规格表 §3.8）：#F0F0EF 底、无边框、480×305.6、圆角 8、内边距 12（上 8）。
 * 上面一行胶囊（高 20、圆角 6、选中底 #E6E6E5）：左「概览 / 模型」，右「全部 / 30 天 / 7 天」；
 * 概览是 3×2 的数字小块（#D8D8D8、149.6×44、圆角 5、间隔 4；标签 12 弱色，数值 13.5 半粗）；模型是各模型的 tokens 和占比条；
 * 下面 26×7 的热力图（格 14.6、步距 17.6；空 #D8D8D8，三档 #86ACEA / #6394E3 / #2566D0），最后是一行弱色脚注。只在有数据时画。
 */
@Composable
internal fun CodeUsageCard(days: List<Day>, host: String, today: LocalDate) {
    val t = Tokens.current
    var tab by remember { mutableStateOf("概览") }
    var range by remember { mutableStateOf(UsageRange.All) }
    val card = if (t.dark) Color(0xFF20201F) else Color(0xFFF0F0EF)
    val block = if (t.dark) Color(0xFF343434) else Color(0xFFD8D8D8)
    val selected = if (t.dark) Color(0xFF3A3A39) else Color(0xFFE6E6E5)
    val levels = listOf(block, Color(0xFF86ACEA), Color(0xFF6394E3), Color(0xFF2566D0))
    @Composable fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
        Box(Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(if (on) selected else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center) {
            Text(label, color = if (on) t.textPrimary else t.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
        }
    }
    Column(Modifier.padding(start = 10.4.dp).width(480.dp).height(305.6.dp).background(card, RoundedCornerShape(8.dp)).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("概览", "模型").forEach { Pill(it, tab == it) { tab = it } }
            Spacer(Modifier.weight(1f))
            UsageRange.entries.forEach { r -> Pill(r.label, range == r) { range = r } }
        }
        Spacer(Modifier.height(20.dp))   // 小块从卡片顶 +48 起（规格 y 209.6 − 161.6）
        Box(Modifier.height(92.dp).fillMaxWidth()) {
            if (tab == "概览") {
                val s = UsageStats.summary(days, range, today)
                val cells = listOf("用量（tokens）" to UsageStats.tokenText(s.tokens), "花费" to "$%.2f".format(s.costUSD), "活跃天数" to "${s.activeDays} 天",
                    "最长连续" to "${s.longestStreak} 天", "日均（活跃日）" to UsageStats.tokenText(s.averageTokens), "最常用模型" to (s.topModel ?: "—"))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cells.chunked(3).forEach { rowCells ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowCells.forEach { (label, value) ->
                                Column(Modifier.width(149.6.dp).height(44.dp).background(block, RoundedCornerShape(5.dp)).padding(4.8.dp)) {
                                    Text(label, color = t.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                                    Text(value, color = t.textPrimary, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            } else {
                val models = UsageStats.models(days, range, today).take(4)
                val total = models.sumOf { it.second }.coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (models.isEmpty()) Text("这段时间没有用量", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    models.forEach { (name, tokens) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.width(150.dp), color = t.textPrimary, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f).height(6.dp).background(block, RoundedCornerShape(3.dp))) {
                                Box(Modifier.fillMaxWidth(tokens.toFloat() / total).height(6.dp).background(levels[3], RoundedCornerShape(3.dp)))
                            }
                            Text(UsageStats.tokenText(tokens), Modifier.padding(start = 8.dp).width(56.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.4.dp))   // 热力图顶 +146.4（规格 y 308）
        val grid = remember(days, today) { UsageStats.heatmap(days, today) }
        Canvas(Modifier.size(456.dp, 120.2.dp)) {
            val cell = 14.6.dp.toPx(); val step = 17.6.dp.toPx(); val r = CornerRadius(2.dp.toPx())
            grid.forEachIndexed { week, column -> column.forEachIndexed { day, level ->
                if (level != null) drawRoundRect(levels[level], Offset(week * step, day * step), Size(cell, cell), r)
            } }
        }
        Spacer(Modifier.height(13.8.dp))   // 脚注 y ≈442
        Text("近半年 · 来自 $host 上的 ccusage（金额按 ccusage 的价目表）", color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 统计卡用的「今天」：生产是系统时间，截图夹具是固定时钟（[LocalStatusClock]）。 */
@Composable
internal fun usageToday(): LocalDate = Instant.ofEpochMilli(LocalStatusClock.current.now()).atZone(ZoneId.systemDefault()).toLocalDate()
