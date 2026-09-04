package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yxi.agent.Account
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 站内信 —— **我们发给你的**（兑换成功、会员到期、公告、系统通知）。
 * 跟工单中心是反向的两条路：工单是你写给我们。
 *
 * 接口形状由 cc-logto_yxi 定并已上线（`logto_yxi/design/wallet-mail.md`）：
 * `GET /api/mail?limit=30&before=<id>` → `{items, nextCursor, unread}`；
 * `POST /api/mail/<id>/read` → `{ok, unread}`。
 *
 * ⚠️ 翻页游标是 **before**（取比它更旧的），不是 after —— 收件箱从顶上插新信，
 * 按页码取会重复或漏。往下翻传**上一页最后一条的 id**。
 * ⚠️ **「拿不到」和「没有信」要分开说**：网络不通时写「没有信」是在骗人。
 */
@Composable
fun MailScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Account.Mail>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val r = Account.mail(ctx)
        if (r == null) failed = true else { items.addAll(r.first); cursor = r.second }
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        Text(
            t("邮件"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 8.dp),
        )
        when {
            loading && items.isEmpty() -> Hint(t("正在取…"))
            failed && items.isEmpty() -> Hint(t("取不到 —— 网络不通，或者登录过期了。下拉重试或重新登录。"))
            items.isEmpty() -> Hint(t("还没有信。兑换成功、会员快到期这些会发到这里。"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 0.dp, 14.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { m ->
                    val expanded = open == m.id
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable {
                            open = if (expanded) null else m.id
                            if (m.unread) scope.launch {
                                if (Account.markMailRead(ctx, m.id)) {
                                    val i = items.indexOfFirst { it.id == m.id }
                                    if (i >= 0) items[i] = m.copy(readAt = "read")
                                }
                            }
                        },
                    ) {
                        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // 没读过的左边一颗点 —— 别只靠字重，深色下几乎看不出来
                                Box(
                                    Modifier.size(7.dp).clip(CircleShape)
                                        .background(if (m.unread) Copper else Color.Transparent),
                                )
                                Text(kindLabel(m.kind), style = MaterialTheme.typography.labelSmall, color = kindColor(m.kind))
                                Text(
                                    m.createdAt.take(16).replace('T', ' '),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall, color = Muted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                            }
                            Text(
                                m.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = if (expanded) 3 else 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            AnimatedVisibility(expanded) {
                                Text(m.body, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!expanded) Text(
                                m.body, style = MaterialTheme.typography.bodySmall, color = Muted,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 还有更旧的：传上一页最后一条的 id
                cursor?.let { cur ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable {
                                scope.launch {
                                    val r = Account.mail(ctx, before = cur)
                                    if (r != null) { items.addAll(r.first); cursor = r.second }
                                }
                            },
                        ) {
                            Text(
                                t("看更早的"), Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.labelLarge, color = Muted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 四种 kind 是契约里固定的，不会悄悄加值（cc-logto_yxi 承诺加值会通知）。 */
private fun kindLabel(kind: String): String = when (kind) {
    "redeem" -> t("兑换")
    "expiry" -> t("到期")
    "notice" -> t("公告")
    else -> t("系统")
}

@Composable
private fun kindColor(kind: String): Color = when (kind) {
    "redeem" -> Copper
    "expiry" -> Amber
    "notice" -> MaterialTheme.colorScheme.tertiary
    else -> Muted
}

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
