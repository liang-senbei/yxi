package app.yxi.ui

import android.content.Context
import org.json.JSONObject

/**
 * 真额度（`/usage`）的缓存 —— 主机页长按展开时先把上次查到的画出来，再后台刷新。
 *
 * ⚠️ 存的是订阅配额（[app.yxi.agent.Quota]），跟 ccusage 那套本地花费**是两回事**。
 *
 * ⚠️ 这个文件原来还有会话页顶上的用量卡、主机页的用量细线 —— 0.8.4 都删了
 * （用户要「会话页顶上不显示额度，只留主机长按」）。剩这一个缓存对象。
 */
object QuotaCache {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    fun put(ctx: Context, hostId: String, q: app.yxi.agent.Quota.Q) {
        val o = JSONObject()
            .put("s", q.sessionPct).put("sr", q.sessionResets)
            .put("w", q.weekPct).put("wr", q.weekResets)
            .put("at", System.currentTimeMillis())
        p(ctx).edit().putString("quota:$hostId", o.toString()).apply()
    }

    /** @return (额度, 距今多少分钟)；没缓存返回 null */
    fun get(ctx: Context, hostId: String): Pair<app.yxi.agent.Quota.Q, Long>? = runCatching {
        val o = JSONObject(p(ctx).getString("quota:$hostId", null) ?: return null)
        val age = (System.currentTimeMillis() - o.optLong("at")) / 60_000
        app.yxi.agent.Quota.Q(
            o.optInt("s"), o.optString("sr"), o.optInt("w"), o.optString("wr"),
        ) to age
    }.getOrNull()
}
