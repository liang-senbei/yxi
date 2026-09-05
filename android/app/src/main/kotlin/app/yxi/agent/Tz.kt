package app.yxi.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * **时区**（用户 2026-09-05：「设置里加时区，UTC / 北京 / 纽约 / 洛杉矶 / 东京，Yxi 里所有显示的时间跟着换算」）。
 *
 * ⚠️⚠️ **全 App 只从这儿格式化绝对时间。** 原来散着写：`take(16).replace('T',' ')`（把服务端的 +08:00 字面当本地显示）、
 * `ZoneId.of("Asia/Shanghai")` 写死两处、`take(10)` 截日期 —— 换时区就得一处处翻。收敛成三个函数之后，
 * 设置一改全 App 跟着变，而且**「几分钟前」这类相对时间不受影响**（它们本来就跟时区无关，在 ui/TimeFmt.kt）。
 *
 * ⚠️ 服务端的时间字段是 **ISO-8601 带 +08:00**（契约写的），少数没带偏移的按北京时间理解 ——
 *    服务端在北京时区。unix 秒（实验室 / 工单）本来就是绝对时刻，直接换算。
 * ⚠️ 默认「跟随手机」：多数人就该看到手机的时间；老板在海外机房 / 多地队友才需要固定一个。
 */
object Tz {
    enum class Zone(val id: String, private val zh: String) {
        Device("", "跟随手机"),
        Utc("UTC", "UTC 世界标准时间"),
        Beijing("Asia/Shanghai", "北京"),
        NewYork("America/New_York", "纽约"),
        LosAngeles("America/Los_Angeles", "洛杉矶"),
        Tokyo("Asia/Tokyo", "东京");

        // ⚠️ get() 不是构造参数：换语言要跟着变（跟 SessionState.label 同一个坑）
        val label: String get() = app.yxi.ui.t(zh)
        val zoneId: ZoneId get() = if (id.isEmpty()) ZoneId.systemDefault() else ZoneId.of(id)
    }

    var zone by mutableStateOf(Zone.Device)
        private set

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    fun load(ctx: Context) { zone = Zone.entries.firstOrNull { it.name == p(ctx).getString("tz", null) } ?: Zone.Device }
    fun set(ctx: Context, z: Zone) { zone = z; p(ctx).edit().putString("tz", z.name).apply() }

    /** 现在这个时区的简短标识，给界面上「按 北京 时间显示」那种提示用；跟随手机时给手机时区的 id。 */
    val shortName: String get() = if (zone == Zone.Device) ZoneId.systemDefault().id else zone.label

    private val SERVER = ZoneOffset.ofHours(8)

    /** 服务端 ISO 字串 → 绝对时刻。带偏移的按偏移，不带的按北京时间；解不出来返回 null。 */
    fun parse(iso: String?): Instant? {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty() || s == "null") return null
        return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s.replace(' ', 'T')).toInstant(SERVER) }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)).atStartOfDay().toInstant(SERVER) }.getOrNull()
    }

    private val DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val D = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 只有日期没有时刻的值（`2026-10-06`）。⚠️ 这种是**日历日**不是时刻，换时区不该动它 ——
     *  按北京 0 点换到纽约会变成前一天，「10-06 到期」显示成「10-05 到期」是错的。 */
    private fun dateOnly(s: String) = s.length == 10 && s[4] == '-' && s[7] == '-'

    /** ISO → 「2026-09-05 16:08」（按设置的时区）。解不出来原样退回（截 16 位）—— 别把一段乱码显示成空。 */
    fun dateTime(iso: String?): String {
        val s = iso?.trim().orEmpty()
        if (dateOnly(s)) return s
        return parse(s)?.atZone(zone.zoneId)?.format(DT) ?: s.take(16).replace('T', ' ')
    }
    /** ISO → 「2026-09-05」。纯日期原样返回（见 [dateOnly]）。 */
    fun date(iso: String?): String {
        val s = iso?.trim().orEmpty()
        if (dateOnly(s)) return s
        return parse(s)?.atZone(zone.zoneId)?.format(D) ?: s.take(10)
    }
    /** unix 秒 → 「09-05 16:08」（实验室 / 工单那种短格式） */
    fun stamp(epochSec: Long): String = Instant.ofEpochSecond(epochSec).atZone(zone.zoneId).format(SHORT)
}
