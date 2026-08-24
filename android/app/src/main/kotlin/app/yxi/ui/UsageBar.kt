package app.yxi.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Usage
import app.yxi.ui.theme.*
import org.json.JSONObject

private val Pill = RoundedCornerShape(100.dp)

/**
 * 用量的**缓存**。
 *
 * ⚠️ 主机列表上不为了显示用量去连每一台机器 —— 那样打开 App 就要建 N 条 SSH 连接。
 * 会话看板本来就连着，在那儿探一次、存下来；列表读缓存并标出**是多久以前的**。
 * 显示一个不标时间的旧数字，比不显示更容易误导。
 */
object UsageCache {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    fun put(ctx: Context, hostId: String, u: Usage) {
        val o = JSONObject()
            .put("remain", u.remainingMinutes).put("pct", u.elapsedPercent)
            .put("tokens", u.tokens).put("cost", u.costUSD).put("tpm", u.tokensPerMinute)
            .put("at", System.currentTimeMillis())
        p(ctx).edit().putString("usage:$hostId", o.toString()).apply()
    }

    /** @return (用量, 距now多少分钟)；没缓存返回 null */
    fun get(ctx: Context, hostId: String): Pair<Usage, Long>? = runCatching {
        val o = JSONObject(p(ctx).getString("usage:$hostId", null) ?: return null)
        val age = (System.currentTimeMillis() - o.optLong("at")) / 60_000
        Usage(
            o.optInt("remain"), o.optInt("pct"), o.optLong("tokens"),
            o.optDouble("cost"), o.optDouble("tpm"),
        ) to age
    }.getOrNull()
}

/**
 * 额度一行：`5 小时额度   ████░░  10%   4:59am (UTC) 重置`。
 * [pct] 为 null 表示还没拿到，这时只画提示文字。
 */
@Composable
private fun QuotaLine(label: String, pct: Int?, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        if (pct != null) {
            Box(Modifier.weight(1f).height(6.dp).background(SurfaceContainerHigh, Pill)) {
                Box(
                    Modifier.fillMaxWidth(pct / 100f).height(6.dp)
                        // ⚠️ 85% 以上变琥珀：这个色在全 app 只表示「要你动手了」
                        .background(if (pct > 85) Amber else Teal, Pill)
                )
            }
            // ⚠️ **「已用」和「剩」都要写出来。** 只给一个百分比的话，
            // 读的人得自己在脑子里做减法 —— 而这个数是用来决定「今天还能不能开大活」的，
            // 别让人算。用户的原话：「没有标清楚用了多少还剩多少」。
            Text(
                t("已用 %d%% · 剩 %d%%").format(pct, 100 - pct),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (pct > 85) Amber else OnSurface,
            )
        } else {
            Text(note, style = MaterialTheme.typography.labelSmall, color = Dim, modifier = Modifier.weight(1f))
        }
    }
    if (pct != null && note.isNotBlank()) {
        Text(note + t(" 重置"), style = MaterialTheme.typography.labelSmall, color = Dim)
    }
}

/**
 * 主机行上的一档额度：`5 小时  ██░░░░  已用 10% · 剩 90%   6:50pm 重置`。
 *
 * ⚠️ **「已用」和「剩」都写出来。** 这个数是用来决定「今天还能不能开大活」的，
 * 只给一个百分比等于让人自己做减法。
 */
@Composable
private fun MiniQuota(label: String, pct: Int, resets: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f).height(4.dp).background(SurfaceContainerHigh, Pill)) {
            Box(
                Modifier.fillMaxWidth(pct / 100f).height(4.dp)
                    .background(if (pct > 85) Amber else Teal, Pill)
            )
        }
        Text(
            t("已用 %d%% · 剩 %d%%").format(pct, 100 - pct),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (pct > 85) Amber else OnSurfaceVariant,
        )
    }
    if (resets.isNotBlank()) {
        Text(
            "        " + resets + t(" 重置"),
            style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 1,
        )
    }
}

/**
 * 真额度的缓存。**跟 [UsageCache] 一个套路，但存的是完全不同的东西**
 * （那个是本地日志算出来的花费，这个是订阅配额，见 [app.yxi.agent.Quota]）。
 *
 * ⚠️ **必须落盘。** 额度是「点一下才去查」的，不缓存的话主机页永远是空的 ——
 * 用户的原话就是「主机里面的服务器还是没有标清楚」。
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

/** 主机列表上的一条细线。**没有数据就返回不画任何东西** —— 调用方不用判断。 */
@Composable
fun UsageStrip(ctx: Context, hostId: String) {
    // 额度两档先画 —— 这是用户真正想在主机列表上看到的东西
    QuotaCache.get(ctx, hostId)?.let { (q, qage) ->
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MiniQuota(t("5 小时"), q.sessionPct, q.sessionResets)
            MiniQuota(t("本周"), q.weekPct, q.weekResets)
            Text(
                if (qage < 60) t("%d 分钟前查的").format(qage) else t("%d 小时前查的").format(qage / 60),
                style = MaterialTheme.typography.labelSmall, color = Dim,
            )
        }
    }
    val (u, age) = UsageCache.get(ctx, hostId) ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).height(4.dp)
                .background(SurfaceContainerHigh, Pill),
        ) {
            Box(
                Modifier.fillMaxWidth(u.elapsedPercent / 100f).height(4.dp)
                    .background(if (u.elapsedPercent > 85) Amber else Teal, Pill)
            )
        }
        Text(
            t("剩 %s").format(u.remainText) + if (age > 30) t(" · %dh 前").format(age / 60) else "",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = Dim,
        )
    }
}

/**
 * 会话看板顶上的详情卡。同样：没数据就什么都不画。
 *
 * ⚠️ **这里有两种完全不同的「百分比」，别混：**
 *   · `u.elapsedPercent` —— **5 小时窗口过去了多少时间**，跟你烧了多少额度**无关**。
 *     它只回答「离下次重置还有多久」。原来的标题只写「5 小时窗口」，
 *     很容易被读成额度（用户就是这么问的：「为什么只有五小时额度」）。
 *   · [quota] —— **真正的订阅额度**，来自 `/usage`（[app.yxi.agent.Quota]）。
 *     ccusage 算不出这个，它只看本地日志，不知道你的订阅用掉了几成。
 *
 * @param onRefresh 点一下就去那台机器上跑一次 `/usage`。
 *   ⚠️ 它会借一个**闲着且输入框是空的**会话来跑，借不到就什么都不做（见 [Quota.borrowable]）。
 */
@Composable
fun UsageCard(
    u: Usage?,
    quota: app.yxi.agent.Quota.Q? = null,
    quotaBusy: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    if (u == null && quota == null) return
    Surface(
        color = SurfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = !quotaBusy, onClick = onRefresh),
    ) {
        Column(Modifier.padding(16.dp, 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 真额度：拿到了就摆在最上面，它比下面那条时间进度重要得多
            when {
                quotaBusy -> QuotaLine(t("额度"), null, t("问一下这台机器…"))
                quota != null -> {
                    QuotaLine(t("5 小时额度"), quota.sessionPct, quota.sessionResets)
                    QuotaLine(t("本周额度"), quota.weekPct, quota.weekResets)
                }
                else -> QuotaLine(t("额度"), null, t("点一下查"))
            }
            if (u == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // ⚠️ 不能只写「5 小时窗口」—— 下面那条是**时间**进度不是额度
                    t("窗口已过"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (u.elapsedPercent > 85) Amber else Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    t("距重置 %s").format(u.remainText),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = OnSurface,
                )
            }
            Box(Modifier.fillMaxWidth().height(6.dp).background(SurfaceContainerHigh, Pill)) {
                Box(
                    Modifier.fillMaxWidth(u.elapsedPercent / 100f).height(6.dp)
                        .background(if (u.elapsedPercent > 85) Amber else Teal, Pill)
                )
            }
            Text(
                "${u.tokenText} token · $${"%.2f".format(u.costUSD)}" +
                    if (u.tokensPerMinute > 0) t(" · %d/分").format(u.tokensPerMinute.toInt()) else "",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Dim,
            )
        }
    }
}
