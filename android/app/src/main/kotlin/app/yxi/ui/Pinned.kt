package app.yxi.ui

import android.content.Context

/**
 * 置顶的会话名 —— **有序**。按主机分开存。
 *
 * ⚠️ **从 `Set` 改成了 `List`（0.8.5）**：置顶要能**手动排序**（长按拖动），
 * 顺序本身就是数据。`Set` 没有顺序，存了也留不住拖出来的次序。
 * 成员判断（`name in pinned`）在 `List` 上照样能用。
 *
 * ⚠️ 只存在手机本地，不写进服务器。置顶是「我关心哪几个 + 什么次序」，
 * 是这台手机的偏好，不是那台机器的状态。
 *
 * 落盘用换行分隔的一个字符串（`SharedPreferences` 没有有序集合类型，
 * 而 `StringSet` 又恰恰**不保证顺序**——正是要避开的）。
 */
internal object Pinned {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun key(hostId: String) = "pinned_ordered:$hostId"

    fun get(ctx: Context, hostId: String): List<String> =
        p(ctx).getString(key(hostId), null)
            ?.split('\n')?.filter { it.isNotBlank() }
            ?: emptyList()

    fun set(ctx: Context, hostId: String, v: List<String>) =
        p(ctx).edit().putString(key(hostId), v.joinToString("\n")).apply()

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
     * ⚠️ 两边必须是同一种形式。`EventService` 里另有一个去了前缀的短名变量，
     * 拿那个来比会一条都对不上。[app.yxi.agent.PinnedTest] 盯着这件事。
     */
    fun shouldNotify(onlyPinned: Boolean, pins: List<String>, eventSession: String): Boolean =
        !onlyPinned || pins.isEmpty() || eventSession in pins
}
