package app.yxi.yunxi

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 云曦小管家 · 备忘 / 任务看板（含提醒）。接口约定见 `Yxi_pilot/design/yunxi-api.md`。
 *
 * **一份数据，三种看法**：备忘录列表、任务看板（待办 / 进行中 / 受阻 / 已完成四栏，老板 2026-09-06 照 omggrow 任务台要的）、
 * 提醒卡（`remindAt` 在未来的那些，[upcoming]）。都是同一个 [Memo]，界面只管按 [Memo.status] 分栏。
 * 闹钟的排 / 取消全在 [Memos] 的写操作里顺手做，界面一行都不用碰 [Reminders]。
 *
 * ⚠️ 纯本地（`filesDir/yunxi-memos.json`），不上传 —— 备忘里可能有私事。
 * ⚠️ [list] 是 Compose state（跟 `Account.me` 同款）：composable 里直接读，改了自动重组。
 * ⚠️ 「完成」只有一个真相源 [Memo.status]；`done` 是它的只读影子。旧 JSON 里的 `done:true` 读进来映射成 DONE。
 */
enum class Repeat { NONE, DAILY, WEEKLY }
enum class Status { TODO, DOING, BLOCKED, DONE }
enum class Priority { HIGH, MID, LOW }

data class Memo(
    val id: String,
    /** 标题 / 正文，≤ [Memos.MAX_LEN] 字，写入时截断 */
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: Status = Status.TODO,
    val priority: Priority = Priority.MID,
    /** 截止时刻 epoch ms；没有就 null。过了还没完成 = [Memos.overdue] */
    val dueAt: Long? = null,
    /** 备注，≤ [Memos.MAX_LEN] 字 */
    val note: String = "",
    /** 提醒时刻 epoch ms；非空 = 带提醒 */
    val remindAt: Long? = null,
    val repeat: Repeat = Repeat.NONE,
    /** 最近一次响过的时刻。不重复的提醒响过就不再排（否则每次开机都会再响一遍） */
    val firedAt: Long? = null,
) {
    val done get() = status == Status.DONE
    val hasReminder get() = remindAt != null
    fun overdue(now: Long = System.currentTimeMillis()) = !done && dueAt != null && dueAt < now
    /**
     * 还会响：有时间、没完成、（不重复的）还没响过。
     * ⚠️ **不看时间过没过**：不重复的提醒只要没响过就算 pending —— 关机错过的那条开机后要补响
     *    （晚响好过不响）。第一版这里多了个「60 秒内」的窗口，关机两分钟以上就永远不响了（审查查出的）。
     */
    fun pending(@Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis()): Boolean =
        remindAt != null && !done && (repeat != Repeat.NONE || firedAt == null)
}

object Memos {
    const val MAX = 200
    const val MAX_LEN = 5000

    /**
     * 全部条目，**看板顺序**：没完成的在前 → 优先级高的在前 → 截止近的在前（没截止的最后）→ 最近改过的在前。
     * 完成一条它就沉到底，这是任务板该有的样子；四栏各自 `filter { it.status == … }` 后顺序照样成立。
     */
    var list: List<Memo> by mutableStateOf(emptyList())
        private set
    private var loaded = false

    private val ORDER = compareBy<Memo> { it.done }
        .thenBy { it.priority.ordinal }
        .thenBy(nullsLast<Long>()) { it.dueAt }
        .thenByDescending { it.updatedAt }

    private fun file(ctx: Context) = File(ctx.filesDir, "yunxi-memos.json")

    /** 幂等。进页面、收到广播时先调一次。 */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        list = runCatching { file(ctx).takeIf { it.exists() }?.let { parse(it.readText()) } ?: emptyList() }
            .getOrDefault(emptyList()).sortedWith(ORDER)
    }

    fun get(id: String): Memo? = list.firstOrNull { it.id == id }

    /** 满 [MAX] 条 / 空标题返回 null。带提醒会顺手排闹钟。 */
    fun add(
        ctx: Context, text: String, remindAt: Long? = null, repeat: Repeat = Repeat.NONE,
        priority: Priority = Priority.MID, dueAt: Long? = null, note: String = "", status: Status = Status.TODO,
    ): Memo? {
        load(ctx)
        val body = text.trim().take(MAX_LEN)
        if (body.isEmpty() || list.size >= MAX) return null
        val now = System.currentTimeMillis()
        val m = Memo(
            UUID.randomUUID().toString(), body, now, now,
            status = status, priority = priority, dueAt = dueAt, note = note.trim().take(MAX_LEN),
            remindAt = remindAt, repeat = if (remindAt == null) Repeat.NONE else repeat,
        )
        commit(ctx, list + m)
        if (m.pending(now)) Reminders.schedule(ctx, m)
        return m
    }

    /** 任意字段改。提醒相关字段变了就重排或取消闹钟；完成了闹钟取消，改回未完成（时间没到）会重排。 */
    fun update(ctx: Context, memo: Memo) {
        load(ctx)
        val old = get(memo.id) ?: return
        val now = System.currentTimeMillis()
        val m = memo.copy(
            text = memo.text.trim().take(MAX_LEN).ifEmpty { old.text },
            note = memo.note.trim().take(MAX_LEN),
            updatedAt = now,
            repeat = if (memo.remindAt == null) Repeat.NONE else memo.repeat,
            // 换了提醒时间 = 新的一次提醒，响过的记录作废
            firedAt = if (memo.remindAt != old.remindAt) null else memo.firedAt,
        )
        commit(ctx, list.map { if (it.id == m.id) m else it })
        if (m.pending(now)) Reminders.schedule(ctx, m) else Reminders.cancel(ctx, m.id)
    }

    fun setStatus(ctx: Context, id: String, status: Status) {
        get(id)?.takeIf { it.status != status }?.let { update(ctx, it.copy(status = status)) }
    }

    fun setPriority(ctx: Context, id: String, priority: Priority) {
        get(id)?.takeIf { it.priority != priority }?.let { update(ctx, it.copy(priority = priority)) }
    }

    /** 完成 ↔ 待办（勾选框那种用法） */
    fun toggleDone(ctx: Context, id: String) {
        get(id)?.let { setStatus(ctx, id, if (it.done) Status.TODO else Status.DONE) }
    }

    fun remove(ctx: Context, id: String) {
        load(ctx)
        if (get(id) == null) return
        commit(ctx, list.filterNot { it.id == id })
        Reminders.cancel(ctx, id)
    }

    /** 四栏各有多少条（四个键都在，没有就是 0）——云曦台词「还有 N 件没做」用 */
    fun counts(): Map<Status, Int> = Status.entries.associateWith { s -> list.count { it.status == s } }

    /** 截止已过、还没完成的，最早过期的在前 */
    fun overdue(now: Long = System.currentTimeMillis()): List<Memo> =
        list.filter { it.overdue(now) }.sortedBy { it.dueAt }

    /** 还没到点的提醒，按时间正序（提醒卡用） */
    fun upcoming(now: Long = System.currentTimeMillis()): List<Memo> =
        list.filter { it.pending(now) && (it.remindAt ?: 0) > now }.sortedBy { it.remindAt }

    /** 下一条要响的（云曦台词「下一条提醒 14:30 · 开会」用） */
    fun next(now: Long = System.currentTimeMillis()): Memo? = upcoming(now).firstOrNull()

    /** 响过了：记 firedAt；重复的把 remindAt 推到下一次。由 [Reminders] 在到点时调。 */
    internal fun markFired(ctx: Context, id: String, nextAt: Long?, now: Long) {
        val m = get(id) ?: return
        val n = m.copy(firedAt = now, remindAt = nextAt ?: m.remindAt, updatedAt = now)
        commit(ctx, list.map { if (it.id == id) n else it })
        if (n.pending(now)) Reminders.schedule(ctx, n)
    }

    private fun commit(ctx: Context, l: List<Memo>) {
        list = l.sortedWith(ORDER)
        runCatching { write(file(ctx), list) }
    }

    /** 解析磁盘上的 JSON。**向后兼容 1.1.13 的旧格式**：没有 `status` 就看 `done`（true → DONE，否则 TODO）。 */
    internal fun parse(json: String): List<Memo> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Memo(
                id = o.getString("id"),
                text = o.optString("text"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                status = runCatching { Status.valueOf(o.getString("status")) }
                    .getOrElse { if (o.optBoolean("done")) Status.DONE else Status.TODO },
                priority = runCatching { Priority.valueOf(o.getString("priority")) }.getOrDefault(Priority.MID),
                dueAt = if (o.has("dueAt")) o.optLong("dueAt") else null,
                note = o.optString("note"),
                remindAt = if (o.has("remindAt")) o.optLong("remindAt") else null,
                repeat = runCatching { Repeat.valueOf(o.optString("repeat", "NONE")) }.getOrDefault(Repeat.NONE),
                firedAt = if (o.has("firedAt")) o.optLong("firedAt") else null,
            )
        }
    }

    /** 先写临时文件再改名 —— 写一半断电不会把整本备忘弄丢 */
    private fun write(f: File, l: List<Memo>) {
        val a = JSONArray()
        l.forEach { m ->
            a.put(JSONObject().apply {
                put("id", m.id); put("text", m.text)
                put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
                put("status", m.status.name)
                if (m.priority != Priority.MID) put("priority", m.priority.name)
                m.dueAt?.let { put("dueAt", it) }
                if (m.note.isNotBlank()) put("note", m.note)
                m.remindAt?.let { put("remindAt", it) }
                if (m.repeat != Repeat.NONE) put("repeat", m.repeat.name)
                m.firedAt?.let { put("firedAt", it) }
            })
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.writeText(a.toString()); tmp.delete() }
    }
}
