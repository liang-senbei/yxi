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
}
