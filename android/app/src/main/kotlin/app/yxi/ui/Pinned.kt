package app.yxi.ui

import android.content.Context

/**
 * 置顶的会话名。**按主机分开存** —— 换台机器同名会话未必是同一件事。
 *
 * ⚠️ 只存在手机本地，不写进服务器。置顶是「我关心哪几个」，
 * 是这台手机的偏好，不是那台机器的状态 —— 写过去会污染别人的视图。
 */
internal object Pinned {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    fun get(ctx: Context, hostId: String): Set<String> =
        p(ctx).getStringSet("pinned:$hostId", emptySet()) ?: emptySet()
    fun set(ctx: Context, hostId: String, v: Set<String>) =
        p(ctx).edit().putStringSet("pinned:$hostId", v).apply()

    /**
     * 「只通知置顶的会话」。**默认开。**
     *
     * ⚠️ **一条都没置顶时故意不生效**（[app.yxi.watch.EventService] 里那句 `pins.isNotEmpty()`）。
     * 否则新装的人打开 app 什么通知都收不到，而设置页上两个绿勾都亮着 ——
     * 正是 TROUBLESHOOTING #91 那个坑。宁可多响，不可静悄悄地全静音。
     * 设置页会把「现在等于全部通知」这件事明写出来。
     */
    fun onlyPinned(ctx: Context): Boolean = p(ctx).getBoolean("notifyPinnedOnly", true)
    fun setOnlyPinned(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("notifyPinnedOnly", v).apply()

    /**
     * 这条事件该不该把手机点亮。抽成纯函数是为了能测 —— 它错了的表现是
     * **「一条通知都收不到，而且没有任何报错」**，靠肉眼在服务里看不出来。
     *
     * @param eventSession 事件里的 `session` 字段，**带 `cc-` 前缀**（如 `cc-Yxi`）
     * @param pins         [get] 存的是 `Session.name`，**也带前缀**
     *
     * ⚠️ 两边必须是同一种形式。`EventService` 里另有一个去了前缀的短名变量（给标题用的），
     * 拿那个来比会一条都对不上。[PinnedTest] 盯着这件事。
     */
    fun shouldNotify(onlyPinned: Boolean, pins: Set<String>, eventSession: String): Boolean =
        !onlyPinned || pins.isEmpty() || eventSession in pins
}
