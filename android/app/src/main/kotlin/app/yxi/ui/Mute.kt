package app.yxi.ui

import android.content.Context

/**
 * 静音的会话 —— **按主机分开、存全名（带 `cc-` 前缀，跟 [Pinned] 一致）**。
 *
 * [Pinned] 的反面：置顶是「只有这些才响」，静音是「这些永远别响」。
 * 有些会话（比如一直在跑的部署）你根本不想被它 ping —— 长按卡片消掉它。
 * 只存本地，是这台手机的偏好，不动服务器。顺序无所谓，用 StringSet。
 */
internal object Mute {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun key(hostId: String) = "muted:$hostId"

    fun get(ctx: Context, hostId: String): Set<String> =
        p(ctx).getStringSet(key(hostId), emptySet())!!.toSet()

    fun set(ctx: Context, hostId: String, v: Set<String>) =
        p(ctx).edit().putStringSet(key(hostId), v).apply()

    /** 事件里的 session 是**全名**（`cc-Yxi`）；存的也是全名。 */
    fun isMuted(ctx: Context, hostId: String, fullName: String): Boolean =
        fullName in get(ctx, hostId)

    /** 翻转，返回翻转后是否静音。 */
    fun toggle(ctx: Context, hostId: String, fullName: String): Boolean {
        val cur = get(ctx, hostId)
        val on = fullName !in cur
        set(ctx, hostId, if (on) cur + fullName else cur - fullName)
        return on
    }
}
