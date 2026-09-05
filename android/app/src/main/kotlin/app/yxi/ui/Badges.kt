package app.yxi.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 「我的」宫格右上角那颗小红点（老板 2026-09-05：**只要点，不要数字**；点进去就消）。
 *
 * 语义是「**上次看过之后又来了新的**」，不是「还有没读完的」：
 * 水位 = 上次打开那一页时的未读数，现在的未读数比水位高就亮；进页把水位抬到当前值。
 * 所以一封信没读完不会让红点一直亮着 —— 红点是通知，不是催。
 *
 * ⚠️ 未读数本身仍由服务端给（`/api/me` 的 `unreadMail`），这里**只存「看过没」**，不自己数信。
 * ⚠️ 未读数比水位**低**（在别的设备上读掉了）要把水位压下来（[mailClamp]），
 *    不然之后新来一封会被旧水位吃掉、红点不亮。
 */
object Badges {
    private const val KEY_MAIL = "badge.mail.seen"

    /** -1 = 还没从盘上读。Compose 状态：水位一动宫格就重画 */
    private var mailSeen by mutableIntStateOf(-1)

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    private fun seen(ctx: Context): Int {
        if (mailSeen < 0) mailSeen = p(ctx).getInt(KEY_MAIL, 0)
        return mailSeen
    }

    /** 邮件格亮不亮 */
    fun mailDot(ctx: Context, unread: Int): Boolean = unread > seen(ctx)

    /** 打开邮件页时、以及在邮件页里读掉几封之后：水位 = 现在的未读数 */
    fun mailSeen(ctx: Context, unread: Int) {
        if (unread < 0) return
        mailSeen = unread
        p(ctx).edit().putInt(KEY_MAIL, unread).apply()
    }

    /** 水位只能往下压：未读数掉到水位之下（别的设备上读掉了），水位跟着下来 */
    fun mailClamp(ctx: Context, unread: Int) {
        if (unread in 0 until seen(ctx)) mailSeen(ctx, unread)
    }
}
