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
 * 云曦小管家 · 备忘录（含提醒）。接口约定见 `Yxi_pilot/design/yunxi-api.md`。
 *
 * **一份数据**：一条备忘可以带一个提醒时间；「提醒」不是另一张表，
 * 提醒卡 = `remindAt` 在未来的备忘（[Memos.upcoming]）。这样界面只管一张列表，
 * 闹钟的排 / 取消全在 [Memos] 的写操作里顺手做，界面一行都不用碰 [Reminders]。
 *
 * ⚠️ 纯本地（`filesDir/yunxi-memos.json`），不上传 —— 备忘里可能有私事。
 * ⚠️ [list] 是 Compose state（跟 `Account.me` 同款）：composable 里直接读，改了自动重组。
 */
enum class Repeat { NONE, DAILY, WEEKLY }

data class Memo(
    val id: String,
    /** ≤ [Memos.MAX_LEN] 字，超出在写入时截断 */
    val text: String,
    val done: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** epoch ms；非空 = 带提醒 */
    val remindAt: Long? = null,
    val repeat: Repeat = Repeat.NONE,
    /** 最近一次响过的时刻。不重复的提醒响过就不再排（否则每次开机都会再响一遍） */
    val firedAt: Long? = null,
) {
    val hasReminder get() = remindAt != null
    /**
     * 还会响：有时间、没勾掉、（不重复的）还没响过。
     * ⚠️ **不看时间过没过**：不重复的提醒只要没响过就算 pending —— 关机错过的那条开机后要补响
     *    （晚响好过不响）。第一版这里多了个「60 秒内」的窗口，关机两分钟以上就永远不响了（审查查出的）。
     */
    fun pending(@Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis()): Boolean =
        remindAt != null && !done && (repeat != Repeat.NONE || firedAt == null)
}

object Memos {
    const val MAX = 200
    const val MAX_LEN = 5000

    /** 全部备忘，**createdAt 倒序**（新的在上；勾选 / 改字不改顺序，免得在手指底下跳）。 */
    var list: List<Memo> by mutableStateOf(emptyList())
        private set
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "yunxi-memos.json")

    /** 幂等。进页面、收到广播时先调一次。 */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        list = runCatching { read(file(ctx)) }.getOrDefault(emptyList())
    }

    fun get(id: String): Memo? = list.firstOrNull { it.id == id }

    /** 满 [MAX] 条返回 null。带提醒会顺手排闹钟。 */
    fun add(ctx: Context, text: String, remindAt: Long? = null, repeat: Repeat = Repeat.NONE): Memo? {
        load(ctx)
        val body = text.trim().take(MAX_LEN)
        if (body.isEmpty() || list.size >= MAX) return null
        val now = System.currentTimeMillis()
        val m = Memo(
            UUID.randomUUID().toString(), body, false, now, now, remindAt,
            if (remindAt == null) Repeat.NONE else repeat,
        )
        commit(ctx, list + m)
        if (m.pending(now)) Reminders.schedule(ctx, m)
        return m
    }

    /** 文字 / 勾选 / 提醒任意改。提醒相关字段变了就重排或取消闹钟。 */
    fun update(ctx: Context, memo: Memo) {
        load(ctx)
        val old = get(memo.id) ?: return
        val now = System.currentTimeMillis()
        val m = memo.copy(
            text = memo.text.trim().take(MAX_LEN).ifEmpty { old.text },
            updatedAt = now,
            repeat = if (memo.remindAt == null) Repeat.NONE else memo.repeat,
            // 换了提醒时间 = 新的一次提醒，响过的记录作废
            firedAt = if (memo.remindAt != old.remindAt) null else memo.firedAt,
        )
        commit(ctx, list.map { if (it.id == m.id) m else it })
        if (m.pending(now)) Reminders.schedule(ctx, m) else Reminders.cancel(ctx, m.id)
    }

    fun toggleDone(ctx: Context, id: String) {
        get(id)?.let { update(ctx, it.copy(done = !it.done)) }
    }

    fun remove(ctx: Context, id: String) {
        load(ctx)
        if (get(id) == null) return
        commit(ctx, list.filterNot { it.id == id })
        Reminders.cancel(ctx, id)
    }

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
        list = l.sortedByDescending { it.createdAt }
        runCatching { write(file(ctx), list) }
    }

    private fun read(f: File): List<Memo> {
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Memo(
                id = o.getString("id"),
                text = o.optString("text"),
                done = o.optBoolean("done"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
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
                put("id", m.id); put("text", m.text); put("done", m.done)
                put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
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
