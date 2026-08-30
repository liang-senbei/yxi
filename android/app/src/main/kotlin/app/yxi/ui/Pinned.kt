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

/**
 * 收藏 —— **一个状态标记，跟置顶各管各的**。
 *
 * · 置顶 = 位置：把它拎到看板最上面。
 * · 收藏 = 状态：这个会话我还要用。被终止后进「未启用」，随时原地拉回来。
 *
 * 一个会话可以既置顶又收藏，也可以只收藏不置顶（想留一堆、但只把三个顶上去）。
 *
 * ⚠️ 存在手机本地就够了 —— 跟分组不一样，收藏**没有第二个读者**。
 * 分组要存服务器是因为组里的 agent 自己要读它（`yxi-hub who`），
 * 收藏只有你自己看，多存一份到服务器只是多一个会不同步的地方。
 */
internal object Favorites {
    private fun p(ctx: android.content.Context) =
        ctx.getSharedPreferences("yxi", android.content.Context.MODE_PRIVATE)
    private fun key(hostId: String) = "faved:$hostId"

    fun get(ctx: android.content.Context, hostId: String): Set<String> =
        p(ctx).getString(key(hostId), null)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun set(ctx: android.content.Context, hostId: String, v: Set<String>) =
        p(ctx).edit().putString(key(hostId), v.joinToString("\n")).apply()

    /**
     * 记住它开在哪个目录 —— **为了被终止之后还能原地拉回来**。
     * ⚠️ 只记名字不够：`tmux new-session` 不带 `-c` 会开在 `$HOME`，
     * 而不是它原来干活的地方（见 [app.yxi.agent.Dirs]）。
     */
    fun remember(ctx: android.content.Context, hostId: String, name: String, cwd: String) {
        if (cwd.isBlank()) return
        p(ctx).edit().putString("favcwd:$hostId:$name", cwd).apply()
    }

    fun cwdOf(ctx: android.content.Context, hostId: String, name: String): String? =
        p(ctx).getString("favcwd:$hostId:$name", null)?.takeIf { it.isNotBlank() }
}
