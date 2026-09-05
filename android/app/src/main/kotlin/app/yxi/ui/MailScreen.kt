package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
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
 *
 * ⚠️⚠️ **未读和未领是两件事**（cc-logto_yxi 2026-09-05 明确提醒，服务端也分两个数）：
 * **读过了也可能没领。** 所以这一页顶上分开说：红点跟着未读走（那是「有新东西」的通用含义），
 * 未领单独一行提醒。合成一个数之后，「红点没了但东西还躺在信里没拿」这件事就再也说不出来了。
 *
 * 老板 2026-09-05：进来要**看得出哪些读过哪些没读**，并且**能自己删信**。
 * 所以顶上一排筹片筛「全部 / 未读 / 已读」，未读的左边一颗红点（跟宫格上那颗同色），读过的标题压暗；
 * 删除在展开的信里（可见入口 + 确认框，STYLE.md「危险动作永远多一步」），有东西没领的先领再删。
 * 宫格红点的水位在 [Badges]：这一页每次拿到新的未读数就把水位抬上去 —— 「点进去就消」就是这一句。
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
    var filter by remember { mutableStateOf(Filter.All) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val r = Account.mail(ctx)
        if (r == null) failed = true else { items.addAll(r.first); cursor = r.second }
        loading = false
    }
    // 「点进去就消」：进页、以及在这页里每读掉一封，都把红点水位抬到当前未读数（见 [Badges]）
    val unreadNow = Account.me?.unreadMail
    LaunchedEffect(unreadNow) { unreadNow?.let { Badges.mailSeen(ctx, it) } }

    Column(modifier.fillMaxSize()) {
        Text(
            t("邮件"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )
        // ⚠️ 未领**单独说**，不并进未读的红点里
        val unclaimed = Account.me?.unclaimedMail ?: 0
        if (unclaimed > 0) Text(
            t("有 %d 封信里的东西还没领").format(unclaimed),
            Modifier.padding(18.dp, 0.dp, 18.dp, 8.dp),
            style = MaterialTheme.typography.labelMedium, color = Copper,
        )
        // 全部 / 未读 N / 已读。N 用服务端的数（跨页也准），不数本地这几十条
        Row(
            Modifier.padding(18.dp, 2.dp, 18.dp, 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MailChip(t("全部"), filter == Filter.All) { filter = Filter.All }
            val n = unreadNow ?: 0
            MailChip(if (n > 0) t("未读 %d").format(n) else t("未读"), filter == Filter.Unread) { filter = Filter.Unread }
            MailChip(t("已读"), filter == Filter.Read) { filter = Filter.Read }
        }
        val shown = when (filter) {
            Filter.All -> items
            // 正展开着的那封别因为刚被标成已读就从「未读」里消失 —— 人还在读它
            Filter.Unread -> items.filter { it.unread || it.id == open }
            Filter.Read -> items.filter { !it.unread }
        }
        when {
            loading && items.isEmpty() -> Hint(t("正在取…"))
            failed && items.isEmpty() -> Hint(t("取不到 —— 网络不通，或者登录过期了。下拉重试或重新登录。"))
            items.isEmpty() -> Hint(t("还没有信。兑换成功、会员快到期这些会发到这里。"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 0.dp, 14.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 这一筛没有：说一句，但「看更早的」照样留着 —— 更早的页里可能有
                if (shown.isEmpty()) item {
                    Text(
                        if (filter == Filter.Unread) t("没有未读的信") else t("没有已读的信"),
                        Modifier.fillMaxWidth().padding(18.dp, 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                items(shown, key = { it.id }) { m ->
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
                                // 没读过的左边一颗红点（跟「我的」宫格上那颗同色）—— 别只靠字重，深色下几乎看不出来
                                Box(
                                    Modifier.size(7.dp).clip(CircleShape)
                                        .background(if (m.unread) MaterialTheme.colorScheme.error else Color.Transparent),
                                )
                                Text(kindLabel(m.kind), style = MaterialTheme.typography.labelSmall, color = kindColor(m.kind))
                                Text(
                                    app.yxi.agent.Tz.dateTime(m.createdAt),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall, color = Muted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                            }
                            Text(
                                m.title,
                                style = MaterialTheme.typography.titleSmall,
                                // 读过的标题压暗：一眼分得出「哪些还没看」
                                color = if (m.unread) MaterialTheme.colorScheme.onSurface else Muted,
                                maxLines = if (expanded) 3 else 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            AnimatedVisibility(expanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(m.body, style = MaterialTheme.typography.bodyMedium)
                                    // 发件人：名字和头像都由服务端给 —— 换 logo 不用改历史信件
                                    if (m.fromName.isNotBlank()) Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        SenderAvatar(m.fromAvatar, 20.dp)
                                        Text(m.fromName, style = MaterialTheme.typography.labelSmall, color = Muted)
                                    }
                                    if (m.attachments.isNotEmpty()) Attachments(m) { got ->
                                        val i = items.indexOfFirst { it.id == m.id }
                                        // 领取顺带标已读（领了还算未读很奇怪）—— 两个标记一起更新
                                        if (i >= 0) items[i] = m.copy(claimedAt = "claimed", readAt = m.readAt ?: "read")
                                        got?.let { }
                                    }
                                    // 删除：可见入口 + 确认框（STYLE.md「危险动作永远多一步」）。
                                    // 有东西没领的不给删 —— 服务端也会拒（409），这里只是把话先说在前面
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        if (m.claimable) Text(
                                            t("先领取再删"), Modifier.padding(12.dp, 6.dp),
                                            style = MaterialTheme.typography.labelSmall, color = Muted,
                                        ) else Text(
                                            t("删除"),
                                            Modifier.clip(RoundedCornerShape(100.dp))
                                                .clickable { confirmDelete = m.id }
                                                .padding(12.dp, 6.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
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

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("删除这封信？")) },
            text = { Text(t("删了就找不回来了。")) },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch {
                        // ⚠️ 失败不动本地列表（Account.deleteMail 的注释）：没删掉就说没删掉
                        if (Account.deleteMail(ctx, id)) {
                            items.removeAll { it.id == id }
                            if (open == id) open = null
                        } else android.widget.Toast.makeText(
                            ctx, t("没删掉 —— 网络不通或登录过期了，信还在。"), android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(t("取消")) } },
        )
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

/**
 * 信里的附件 + 「领取」。
 *
 * ⚠️ `code` 类附件**什么都不发**，只是印一串码面给用户自己去兑 —— 所以
 * 一封只带 code 的信**没有「领取」这一步**，画一个点了什么都不发生的按钮比不画更糟。
 * ⚠️ 领取失败**不改本地状态**：说成领到了而实际没有，用户会以为东西丢了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Attachments(m: Account.Mail, onClaimed: (Account.Claim?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(m.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.attachments.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (a.grantable) Copper else Muted))
                Text(a.name, style = MaterialTheme.typography.bodySmall)
                // 兑换码是给人抄走的，长按复制
                if (a.kind == "code") Text(
                    t("长按复制"), style = MaterialTheme.typography.labelSmall, color = Muted,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("code", a.name))
                            android.widget.Toast.makeText(ctx, t("复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ),
                )
            }
        }
        when {
            // 只有码面：没有「领」这一步，说清楚就行
            m.attachments.none { it.grantable } -> Text(
                t("这串码自己去「兑换」里输"), style = MaterialTheme.typography.labelSmall, color = Muted,
            )
            !m.claimedAt.isNullOrEmpty() -> Text(
                t("已领取"), style = MaterialTheme.typography.labelMedium, color = Muted,
            )
            else -> Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(100.dp),
                modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        val r = Account.claimMail(ctx, m.id)
                        busy = false
                        if (r == null) {
                            android.widget.Toast.makeText(
                                ctx, t("没领到 —— 网络不通或登录过期了，东西还在信里。"),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            // ⚠️ replay = 之前已经领过，**这次没有重复发**。照实说，别说成「领取成功」
                            android.widget.Toast.makeText(
                                ctx,
                                if (r.replay) t("这封信之前已经领过了，没有重复发") else t("领好了"),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            onClaimed(r)
                        }
                    }
                },
            ) {
                Text(
                    if (busy) t("领取中…") else t("领取"),
                    Modifier.padding(18.dp, 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * 发件人头像。⚠️ 复用 [Me.cachedRemote] 那套缓存（下一次就走本地文件）——
 * 全部信件都是同一个 official，实际只会下一次。拿不到就画一个圆底，**不留空洞**。
 */
@Composable
private fun SenderAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = Me.cachedRemote(ctx, url)
                if (!f.exists() || f.length() == 0L) {
                    java.net.URL(url).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
                }
                android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            androidx.compose.foundation.Image(
                bitmap = it, contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

/** 收件箱筛选 */
private enum class Filter { All, Unread, Read }

/** 药丸筹片（同 LinesPanel 的 ScopeChip）：选中 primaryContainer，没选 surfaceContainerHigh */
@Composable
private fun MailChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(100.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(14.dp, 7.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
