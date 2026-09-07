package app.yxi.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Abyss
import app.yxi.agent.Account
import app.yxi.agent.Wish
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import app.yxi.yunxi.Memo
import app.yxi.yunxi.LocationUnavailable
import app.yxi.yunxi.Memos
import app.yxi.yunxi.Priority
import app.yxi.yunxi.Status
import app.yxi.yunxi.NoLocationPermission
import app.yxi.yunxi.NoCityException
import app.yxi.yunxi.Reminders
import app.yxi.yunxi.Repeat
import app.yxi.yunxi.Weather
import app.yxi.yunxi.WeatherApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 云曦小管家 —— 「带人格的服务面板」（PRD：`Yxi_Entertainment/design/steward-prd.md`）。
 * 上面是会动的 Q 版云曦（[YunxiPet]），下面是六张服务卡：备忘录 · 提醒 · 天气 · 签到 · 深渊 · 邮件。
 * 服务层在 `app.yxi.yunxi`（cc-Yxi_pilot：`Memos` / `Reminders` / `WeatherApi`，纯本地、不动服务端）。
 *
 * 三条边界（PRD §1）：不是聊天机器人（没有输入框问她问题、不假装是 Claude）；不是通知机器（推送默认关，v1 只有用户自己设的提醒会响）；
 * 不重复看板。她说的每句话都是预写台词 + 真数据，失败就说失败（STYLE.md §0）。
 */
@Composable
fun YunxiScreen(
    onActivity: () -> Unit = {},
    onAbyss: () -> Unit = {},
    onMail: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 她说什么、做什么 ─────────────────────────────────────────────────
    var line by remember { mutableStateOf(YunxiLines.greeting()) }
    var lineAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pose by remember { mutableStateOf<YunxiPose?>(null) }
    fun say(text: String) { line = text; lineAt = System.currentTimeMillis() }
    /** 办一件事：切举杖 1.6 秒，再说结果 */
    fun doing(block: suspend () -> String) {
        scope.launch {
            pose = YunxiPose.Cast
            val result = block()
            delay(600)
            pose = null
            say(result)
        }
    }
    // 台词停 9 秒再收（一句话要读完，6 秒短了，E2E 截图都赶不上）；收起后再安静 6 秒她自己嘟囔一句
    LaunchedEffect(lineAt) {
        delay(9000); line = ""
        delay(6000); if (line.isEmpty()) say(YunxiLines.idle())
    }

    // ── 数据 ─────────────────────────────────────────────────────────────
    var checkIn by remember { mutableStateOf<Wish.CheckIn?>(null) }
    var checkInFailed by remember { mutableStateOf(false) }
    var abyss by remember { mutableStateOf<Abyss.State?>(null) }
    var weather by remember { mutableStateOf<Weather?>(WeatherApi.cached(ctx)) }
    var weatherErr by remember { mutableStateOf<String?>(null) }
    var weatherLoading by remember { mutableStateOf(false) }
    var weatherRev by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        Memos.load(ctx)
        // 进页第一句：看板上还有几件没做（老板：「提醒我们还有什么东西没做」）。没有没做的就按时段问候
        YunxiLines.todo(Memos.list)?.let { say(it) }
        launch { val c = Wish.checkIn(ctx); if (c == null) checkInFailed = true else checkIn = c }
        launch { abyss = Abyss.state(ctx) }
    }
    LaunchedEffect(weatherRev) {
        if (WeatherApi.city(ctx) == null) return@LaunchedEffect
        weatherLoading = true
        WeatherApi.current(ctx, force = weatherRev > 0).fold(
            onSuccess = { weather = it; weatherErr = null },
            onFailure = { e -> weatherErr = if (e is NoCityException) null else t("没取到天气……可能是网不太好。") },
        )
        weatherLoading = false
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("云曦"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 0.dp),
        )
        YunxiPet(
            pose = pose, line = line,
            onTap = { n -> say(YunxiLines.tap(n)) },
            onLongPress = { say(t("你的手指好暖。")) },
            modifier = Modifier.padding(top = 6.dp),
        )

        TaskBoardCard(onSaved = { withReminder -> doing { if (withReminder) t("记下了。到时候我叫你。") else t("记下了，我替你收好。") } })
        ReminderCard()
        WeatherCard(
            weather, weatherErr, weatherLoading,
            onRefresh = { weatherRev++ },
            onCityPicked = { doing { weatherRev++; t("好，我去看看那边的天。") } },
        )
        CheckInCard(checkIn, checkInFailed, onActivity)
        AbyssCard(abyss, onAbyss)
        MailCard(onMail)
    }
}

/** 她的口吻：短、空灵、不油。所有台词都是预写的，数据只填空 —— 她不假装会思考。 */
internal object YunxiLines {
    fun greeting(): String {
        val h = java.time.LocalTime.now().hour
        return when {
            h in 6..10 -> t("晨雾还没散呢……今天也要加油呀。")
            h in 11..13 -> t("正午的光太亮了，我替你挡一挡。")
            h in 14..17 -> t("午后的风里有旧文明的味道……你闻到了吗？")
            h in 18..21 -> t("星星快出来了，今天辛苦了。")
            else -> t("这么晚还在呀……云曦陪你。")
        }
    }
    fun tap(n: Int): String = when {
        n >= 3 -> t("别戳啦，发间的云要散了……")
        n == 2 -> t("还有事吗？")
        else -> t("嗯？")
    }
    private val idles = listOf("又有一颗星熄灭了……不过没关系，它的梦我收好了。", "风好安静。", "云飘过去了一朵，又一朵。")
    fun idle(): String = t(idles.random())
    /** 看板上还有几件没做；全做完了另说一句；看板是空的返回 null（照常问候） */
    fun todo(list: List<Memo>): String? {
        if (list.isEmpty()) return null
        val open = list.count { !it.done }
        if (open == 0) return t("都做完了，歇一歇。")
        val high = list.count { !it.done && it.priority == Priority.HIGH }
        val od = list.count { it.overdue() }
        val extra = buildList {
            if (high > 0) add(t("%d 件高优先").format(high))
            if (od > 0) add(t("%d 件已过期").format(od))
        }
        return t("还有 %d 件没做").format(open) + (if (extra.isEmpty()) "" else "（" + extra.joinToString("，") + "）") + "。"
    }
}

// ── 卡片骨架 ──────────────────────────────────────────────────────────────

@Composable
private fun Card(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Column(Modifier.padding(18.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Muted) }
            }
            content()
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(100.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp, 6.dp).alpha(if (enabled) 1f else 0.5f),
        style = MaterialTheme.typography.labelMedium,
        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}

// ── 任务看板（老板 2026-09-06：照 omggrow 的任务看板 —— 四栏 · 优先级 · 截止 · 随手记；备忘录升级而来，同一份数据）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskBoardCard(onSaved: (withReminder: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.MID) }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var pickDue by remember { mutableStateOf(false) }
    var remindAt by remember { mutableStateOf<Long?>(null) }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    var pickTime by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Status?>(null) }      // null = 全部
    var editing by remember { mutableStateOf<String?>(null) }     // 展开的那条
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val memos = Memos.list
    val counts = Memos.counts()
    val open = memos.count { !it.done }

    Card(t("任务看板"), trailing = if (open > 0) t("还有 %d 件没做").format(open) else null) {
        // 四栏：手机上竖着放不下四列，用筹片切栏，数字就是每栏几件
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(t("全部"), on = filter == null) { filter = null }
            Status.entries.forEach { st -> Pill("${statusLabel(st)} ${counts[st] ?: 0}", on = filter == st) { filter = st } }
        }

        // ── 随手记 ──
        OutlinedTextField(
            text, { text = it.take(Memos.MAX_LEN) },
            placeholder = { Text(t("随手记一件……")) },
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(), maxLines = 4,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("优先级"), style = MaterialTheme.typography.labelSmall, color = Muted)
            Priority.entries.forEach { pr -> Pill(priorityLabel(pr), on = priority == pr) { priority = pr } }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("截止"), style = MaterialTheme.typography.labelSmall, color = Muted)
            Pill(dueAt?.let { dayStamp(it) } ?: t("不设"), on = dueAt != null) { dueAt = null }
            Pill(t("今天")) { dueAt = endOfDay(0) }
            Pill(t("明天")) { dueAt = endOfDay(1) }
            Pill(t("选日期…")) { pickDue = true }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("提醒"), style = MaterialTheme.typography.labelSmall, color = Muted)
            Pill(remindAt?.let { clockStamp(it) } ?: t("不提醒"), on = remindAt != null) { remindAt = null }
            Pill(t("30 分钟后")) { remindAt = System.currentTimeMillis() + 30 * 60_000 }
            Pill(t("1 小时后")) { remindAt = System.currentTimeMillis() + 60 * 60_000 }
            Pill(t("今晚 21:00")) { remindAt = nextAt(21, 0) }
            Pill(t("明早 9:00")) { remindAt = nextAt(9, 0, tomorrow = true) }
            Pill(t("自定义…")) { pickTime = true }
        }
        if (remindAt != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(t("不重复"), on = repeat == Repeat.NONE) { repeat = Repeat.NONE }
            Pill(t("每天"), on = repeat == Repeat.DAILY) { repeat = Repeat.DAILY }
            Pill(t("每周"), on = repeat == Repeat.WEEKLY) { repeat = Repeat.WEEKLY }
        }
        // 精确闹钟被系统关了要说出来（Android 12+ 可能默认关），并给去开的入口
        if (remindAt != null && !Reminders.canExact(ctx)) Text(
            t("系统没开「闹钟和提醒」权限，提醒可能会晚几分钟。点这里去开。"),
            Modifier.clickable { Reminders.exactSettingsIntent(ctx)?.let { ctx.startActivity(it) } },
            style = MaterialTheme.typography.labelSmall, color = Amber,
        )
        Surface(
            color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(100.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(100.dp)).clickable(enabled = text.isNotBlank()) {
                val ok = Memos.add(ctx, text.trim(), remindAt, repeat, priority = priority, dueAt = dueAt)
                if (ok == null) android.widget.Toast.makeText(ctx, t("看板满了（%d 件），清几件再记。").format(Memos.MAX), android.widget.Toast.LENGTH_SHORT).show()
                else { onSaved(remindAt != null); text = ""; remindAt = null; repeat = Repeat.NONE; dueAt = null; priority = Priority.MID }
            }.alpha(if (text.isNotBlank()) 1f else 0.5f),
        ) {
            Text(
                t("记下"), Modifier.fillMaxWidth().padding(vertical = 11.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
            )
        }

        // ── 列表（Memos.list 已按 未完成在前 → 优先级 → 截止 → 更新时间 排好）──
        val shown = memos.filter { filter == null || it.status == filter }
        if (memos.isEmpty()) Text(t("看板空空的，随手记一件吧。"), style = MaterialTheme.typography.bodySmall, color = Muted)
        else if (shown.isEmpty()) Text(t("这一栏没有。"), style = MaterialTheme.typography.bodySmall, color = Muted)
        shown.forEach { m ->
            TaskRow(
                m, expanded = editing == m.id,
                onToggle = { editing = if (editing == m.id) null else m.id },
                onDelete = { confirmDelete = m.id },
            )
        }
    }

    if (pickTime) {
        val st = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(t("几点提醒？")) },
            text = { TimePicker(st) },
            confirmButton = { TextButton({ remindAt = nextAt(st.hour, st.minute); pickTime = false }) { Text(t("好")) } },
            dismissButton = { TextButton({ pickTime = false }) { Text(t("取消")) } },
        )
    }
    if (pickDue) {
        val ds = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = dueAt ?: System.currentTimeMillis())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDue = false },
            confirmButton = {
                TextButton({
                    // DatePicker 给的是那一天 UTC 0 点的毫秒；截止按手机时钟那一天的 23:59 算
                    ds.selectedDateMillis?.let { utc ->
                        val d = java.time.Instant.ofEpochMilli(utc).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        dueAt = d.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    pickDue = false
                }) { Text(t("好")) }
            },
            dismissButton = { TextButton({ pickDue = false }) { Text(t("取消")) } },
        ) { androidx.compose.material3.DatePicker(state = ds) }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("删掉这件？")) },
            text = { Text(t("有提醒的话一起取消。")) },
            confirmButton = { TextButton({ Memos.remove(ctx, id); confirmDelete = null; if (editing == id) editing = null }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(t("取消")) } },
        )
    }
}

/** 一件：优先级标 · 标题 · 状态；下面一行截止 / 提醒；展开后能改状态、优先级、取消提醒、删除 */
@Composable
private fun TaskRow(m: Memo, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle).padding(6.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PriorityTag(m.priority)
            Text(
                m.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (m.done) Muted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (m.done) TextDecoration.LineThrough else null,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            )
            Text(statusLabel(m.status), style = MaterialTheme.typography.labelSmall, color = statusColor(m.status))
        }
        val meta = buildList {
            m.dueAt?.let { if (m.overdue()) add(t("已过期 %s").format(dayStamp(it))) else if (!m.done) add(t("截止 %s").format(dayStamp(it))) }
            m.remindAt?.let { at ->
                add((if (m.pending()) t("提醒 %s") else t("已提醒 %s")).format(clockStamp(at)) +
                    when (m.repeat) { Repeat.DAILY -> " · " + t("每天"); Repeat.WEEKLY -> " · " + t("每周"); else -> "" })
            }
        }
        if (meta.isNotEmpty()) Text(
            meta.joinToString(" · "), Modifier.padding(start = 2.dp), style = MaterialTheme.typography.labelSmall,
            color = if (m.overdue()) MaterialTheme.colorScheme.error else if (m.pending()) Amber else Muted,
        )
        if (expanded) {
            // 状态：一行四个，点哪个就换到哪栏（同 omggrow 那个下拉，这里用筹片更顺手）
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Status.entries.forEach { st -> Pill(statusLabel(st), on = m.status == st) { Memos.setStatus(ctx, m.id, st) } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { pr -> Pill(priorityLabel(pr), on = m.priority == pr) { Memos.setPriority(ctx, m.id, pr) } }
                if (m.hasReminder) Pill(t("取消提醒")) { Memos.update(ctx, m.copy(remindAt = null, repeat = Repeat.NONE)) }
                Pill(t("删除")) { onDelete() }
            }
        }
    }
}

@Composable
private fun PriorityTag(p: Priority) {
    val (label, color) = when (p) {
        Priority.HIGH -> t("高") to MaterialTheme.colorScheme.error
        Priority.MID -> t("中") to Amber
        Priority.LOW -> t("低") to Muted
    }
    Text(
        label,
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(6.dp, 1.dp),
        style = MaterialTheme.typography.labelSmall, color = color,
    )
}

private fun statusLabel(s: Status): String = when (s) {
    Status.TODO -> t("待办"); Status.DOING -> t("在做"); Status.BLOCKED -> t("受阻"); Status.DONE -> t("已完成")
}
private fun priorityLabel(p: Priority): String = when (p) { Priority.HIGH -> t("高"); Priority.MID -> t("中"); Priority.LOW -> t("低") }

@Composable
private fun statusColor(s: Status): Color = when (s) {
    Status.TODO -> MaterialTheme.colorScheme.onSurfaceVariant
    Status.DOING -> Copper
    Status.BLOCKED -> MaterialTheme.colorScheme.error
    Status.DONE -> Muted
}

/** 截止用「哪一天」显示（按手机时钟），不显示时分 */
private fun dayStamp(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))

/** 今天 + [days] 天的 23:59（手机时钟） */
private fun endOfDay(days: Long): Long =
    java.time.LocalDate.now().plusDays(days).atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * 提醒 / 取数时刻的显示：**按手机时钟**（`ZoneId.systemDefault()`），不走 [app.yxi.agent.Tz] 选的时区。
 * 闹钟是按手机时钟排的（[nextAt] 用 `ZonedDateTime.now()`），显示若用用户在设置里选的别的时区，
 * 「今晚 21:00」会显示成「05:00」—— 闹钟响得对、标签在骗人（Opus 审查抓的）。两处必须同一个钟。
 */
private fun clockStamp(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

/** 今天的 h:m；已经过了就明天。[tomorrow] 强制明天。按手机时钟（和 [clockStamp] 同一个钟）。 */
private fun nextAt(h: Int, m: Int, tomorrow: Boolean = false): Long {
    var z = java.time.ZonedDateTime.now().withHour(h).withMinute(m).withSecond(0).withNano(0)
    if (tomorrow || !z.isAfter(java.time.ZonedDateTime.now())) z = z.plusDays(1)
    return z.toInstant().toEpochMilli()
}

// ── 提醒（接下来会响的）──────────────────────────────────────────────────

@Composable
private fun ReminderCard() {
    val ctx = LocalContext.current
    Memos.list   // 订阅
    val up = Memos.upcoming()
    Card(t("提醒"), trailing = if (up.isEmpty()) null else t("%d 条").format(up.size)) {
        if (!Reminders.notificationsEnabled(ctx)) Text(
            t("通知被关了，提醒到点也响不了。去系统设置里给 Yxi 开通知。"),
            style = MaterialTheme.typography.labelSmall, color = Amber,
        )
        if (up.isEmpty()) Text(t("接下来没有要响的。在上面记一条、选个时间就行。"), style = MaterialTheme.typography.bodySmall, color = Muted)
        up.take(5).forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(clockStamp(m.remindAt ?: 0L), style = MaterialTheme.typography.labelMedium, color = Amber)
                Text(m.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.repeat != Repeat.NONE) Text(if (m.repeat == Repeat.DAILY) t("每天") else t("每周"), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

// ── 天气 ─────────────────────────────────────────────────────────────────

@Composable
private fun WeatherCard(w: Weather?, err: String?, loading: Boolean, onRefresh: () -> Unit, onCityPicked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(WeatherApi.city(ctx) == null) }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<app.yxi.yunxi.City>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchErr by remember { mutableStateOf<String?>(null) }

    // 定位（老板 2026-09-06 拍板「用手机定位」）：只要粗略定位权限，拿一次位置反查成城市，不保存坐标（服务层 WeatherApi.locate）
    var locating by remember { mutableStateOf(false) }
    var locErr by remember { mutableStateOf<String?>(null) }
    fun locate() {
        locating = true; locErr = null
        scope.launch {
            WeatherApi.locate(ctx).fold(
                onSuccess = { c -> WeatherApi.setCity(ctx, c); picking = false; hits = emptyList(); q = ""; onCityPicked() },
                onFailure = { e ->
                    locErr = when (e) {
                        is NoLocationPermission -> t("没有定位权限 —— 手动选城市也行。")
                        is LocationUnavailable -> t("定位没拿到（手机可能关了定位）—— 手动选城市也行。")
                        else -> t("定位失败……手动选城市也行。")
                    }
                },
            )
            locating = false
        }
    }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) locate() else locErr = t("没有定位权限 —— 手动选城市也行。") }

    Card(t("天气"), trailing = w?.city?.name) {
        if (picking) {
            Text(t("先告诉我你在哪座城。"), style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (locating) t("定位中…") else t("用当前位置"), enabled = !locating) {
                    if (WeatherApi.hasLocationPermission(ctx)) locate()
                    else askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                Text(t("只用一次粗略位置，不保存。"), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            locErr?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text(t("城市名，中文就行")) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                // 老板 2026-09-07：单字「找」一律换成 🔍。图标不用翻译，En.kt 里那两条已经删了
                Pill(if (searching) "🔍…" else "🔍", enabled = q.isNotBlank() && !searching) {
                    searching = true; searchErr = null
                    scope.launch {
                        hits = runCatching { WeatherApi.search(q.trim()) }.getOrElse { searchErr = t("没搜到……可能是网不太好。"); emptyList() }
                        if (hits.isEmpty() && searchErr == null) searchErr = t("没有这座城，换个写法试试。")
                        searching = false
                    }
                }
            }
            searchErr?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            hits.forEach { c -> LinkRow(c.label) { WeatherApi.setCity(ctx, c); picking = false; hits = emptyList(); q = ""; onCityPicked() } }
            if (WeatherApi.city(ctx) != null) Pill(t("算了，还用原来的")) { picking = false }
        } else {
            when {
                w != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(weatherGlyph(w.code), style = MaterialTheme.typography.headlineMedium)
                        Column(Modifier.weight(1f)) {
                            Text(t("%s，%d°").format(w.text, Math.round(w.tempC)), style = MaterialTheme.typography.titleMedium)
                            Text(
                                t("体感 %d° · 湿度 %d%% · 风 %d km/h").format(Math.round(w.feelsC), w.humidity, Math.round(w.windKmh)),
                                style = MaterialTheme.typography.labelSmall, color = Muted,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        w.days.take(3).forEach { d ->
                            Column {
                                Text(d.date.takeLast(5), style = MaterialTheme.typography.labelSmall, color = Muted)
                                Text("${weatherGlyph(d.code)} ${Math.round(d.hiC)}° / ${Math.round(d.loC)}°", style = MaterialTheme.typography.labelMedium)
                                if (d.rainPct > 0) Text(t("降雨 %d%%").format(d.rainPct), style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    }
                    // ⚠️ 过期的要说是过期的（缓存 30 分钟）；取失败但有旧数据也要说
                    if (err != null) Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else if (w.stale) Text(t("这是 %s 取的，正在更新…").format(clockStamp(w.fetchedAt)), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                loading -> Text(t("正在取…"), style = MaterialTheme.typography.bodySmall, color = Muted)
                err != null -> Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text(t("正在取…"), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(t("刷新"), enabled = !loading) { onRefresh() }
                Pill(t("换城市")) { picking = true }
                Spacer(Modifier.weight(1f))
                Text(t("数据 Open-Meteo"), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

// ── 三张只读状态卡：签到 / 深渊 / 邮件 ──────────────────────────────────

@Composable
private fun CheckInCard(c: Wish.CheckIn?, failed: Boolean, onActivity: () -> Unit) {
    Card(t("签到")) {
        when {
            c != null && c.checkedInToday -> LinkRow(t("今天签过了 · 连着 %d 天").format(c.streak), onActivity)
            c != null -> LinkRow(t("该签到啦——今天的曦光还没拿呢。"), onActivity)
            failed -> Text(t("取不到 —— 网络不通，或者登录过期了。"), style = MaterialTheme.typography.bodySmall, color = Muted)
            else -> Text(t("正在取…"), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun AbyssCard(s: Abyss.State?, onAbyss: () -> Unit) {
    Card(t("深渊")) {
        if (s == null) Text(t("取不到 —— 网络不通，或者登录过期了。"), style = MaterialTheme.typography.bodySmall, color = Muted)
        else {
            val reset = untilReset(s.season.endsAt)
            LinkRow(
                s.season.name.ifBlank { t("本期") } + " · ★ ${s.totalStars} / ${s.rules.maxStars}" + (if (reset.isNotEmpty()) " · $reset" else "") +
                    (if (!s.played) " · " + t("本期还没打") else ""),
                onAbyss,
            )
        }
    }
}

@Composable
private fun MailCard(onMail: () -> Unit) {
    val n = Account.me?.unreadMail ?: 0
    Card(t("邮件")) {
        LinkRow(if (n > 0) t("有 %d 封没读的信。").format(n) else t("没有新信。"), onMail)
    }
}

/** 天气符号：按服务层的 [WeatherApi.Kind] 七类给一个字，不做图标 —— 一句话天气配一个字就够 */
private fun weatherGlyph(code: Int): String = when (WeatherApi.kind(code)) {
    WeatherApi.Kind.CLEAR -> "☀"; WeatherApi.Kind.PARTLY -> "⛅"; WeatherApi.Kind.CLOUDY -> "☁"; WeatherApi.Kind.FOG -> "🌫"
    WeatherApi.Kind.RAIN -> "🌧"; WeatherApi.Kind.SNOW -> "❄"; WeatherApi.Kind.THUNDER -> "⛈"
}
