package app.yxi.ui

import android.content.Context
import androidx.compose.foundation.background
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

/** 主机列表上的一条细线。**没有数据就返回不画任何东西** —— 调用方不用判断。 */
@Composable
fun UsageStrip(ctx: Context, hostId: String) {
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

/** 会话看板顶上的详情卡。同样：没数据就什么都不画。 */
@Composable
fun UsageCard(u: Usage?) {
    if (u == null) return
    Surface(color = SurfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp, 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("5 小时窗口"),
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
