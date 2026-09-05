package app.yxi.ui

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * 「我的」宫格右上角那颗小红点（老板 2026-09-05：**只要点，不要数字**；点进去就消）。邮件、工单各一个 key。
 *
 * 语义是「**上次看过之后又来了新的**」，不是「还有没读完的」：
 * 水位 = 上次打开那一页时的未读数，现在的未读数比水位高就亮；进页把水位抬到当前值。
 * 所以一封信 / 一条回复没看完不会让红点一直亮着 —— 红点是通知，不是催。
 *
 * ⚠️ 未读数本身仍由服务端给（`/api/me` 的 `unreadMail` / `unreadTickets`），这里**只存「看过没」**，不自己数。
 * ⚠️ 未读数比水位**低**（在别的设备上读掉了）要把水位压下来（[clamp]），不然之后新来的会被旧水位吃掉、红点不亮。
 */
object Badges {
    const val MAIL = "mail"
    const val TICKETS = "tickets"

    /** key → 水位。Compose 状态：水位一动宫格就重画。不在表里 = 这次进程还没改过，以盘上的为准 */
    private val marks = mutableStateMapOf<String, Int>()

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    // ⚠️ 只读不写：这个函数在组合期间被调，组合期间往状态里写会多触发一轮重组
    private fun current(ctx: Context, key: String): Int = marks[key] ?: p(ctx).getInt("badge.$key.seen", 0)

    /** 这一格亮不亮 */
    fun dot(ctx: Context, key: String, unread: Int): Boolean = unread > current(ctx, key)

    /** 打开那一页时、以及在页里读掉几条之后：水位 = 现在的未读数 */
    fun mark(ctx: Context, key: String, unread: Int) {
        if (unread < 0) return
        marks[key] = unread
        p(ctx).edit().putInt("badge.$key.seen", unread).apply()
    }

    /** 水位只能往下压：未读数掉到水位之下（别的设备上读掉了），水位跟着下来 */
    fun clamp(ctx: Context, key: String, unread: Int) {
        if (unread in 0 until current(ctx, key)) mark(ctx, key, unread)
    }
}
