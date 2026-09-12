package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.yxi.agent.AccountApi
import kotlinx.coroutines.launch

/**
 * 「我的」——登录 / 资料 / 会员 / 额度 / 曦光 / 信箱红点。
 *
 * 壳(左栏入口 + [Page.Me] 路由)是 pilot 的,约好的接口就是这一个函数;内容归 cc-logto_yxi。
 *
 * 登录走**系统浏览器 + 本机回环端口回调**(不内嵌 webview,见 desktop-reference §2.1),
 * 实现在 [MeAuth]。数据模型和解析跟手机端**共用 `:core` 的 [AccountApi]** ——
 * 服务端加个字段两边一起有,不会分叉。
 *
 * ⚠️ **契约里「正常就是 null」的字段**(服务端 2026-09-04 明确交代过,手机端被咬过):
 * 到期时间(没兑过码 / 永久授予)、额度三项(ultra 时全 null,**先读 unlimited** 别拿 remaining 判)、
 * 昵称头像签名邮箱(没设过 / 只用社交登录没绑邮箱)。所以这一页每一处都要有空态。
 */
@Composable
fun MePane(state: AppState) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var mailOpen by remember { mutableStateOf(false) }

    // 进页面拉一次:有令牌就顺手刷资料,没令牌就停在登录按钮上
    LaunchedEffect(Unit) { MeAuth.load() }
    val owner = MeAuth.me?.userId
    if (mailOpen && MeAuth.signedIn && owner != null) {
        val api = remember(owner) { app.yxi.agent.MailApi { path, method, body -> MeAuth.accountRequest(owner, path, method, body) } }
        MailPane(owner, api, onBack = { mailOpen = false; scope.launch { err = MeAuth.refresh().orEmpty() } }, onCounters = { MeAuth.mailCounters(owner, it) })
        return
    }

    Box(Modifier.fillMaxSize().background(t.surface0)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("我的", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
                Spacer(Modifier.height(16.dp))

                val me = MeAuth.me
                when {
                    !MeAuth.signedIn -> SignInCard(
                        why = MeAuth.signedOutWhy,
                        waiting = MeAuth.waitingBrowser,
                        err = err,
                        busy = busy,
                    ) {
                        scope.launch {
                            busy = true; err = ""
                            err = MeAuth.signIn().orEmpty()
                            busy = false
                        }
                    }
                    me == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp))
                        Spacer(Modifier.height(0.dp))
                        Text(
                            if (err.isEmpty()) "正在读你的资料…" else err,
                            Modifier.padding(start = 12.dp),
                            color = if (err.isEmpty()) t.textMuted else t.danger,
                        )
                    }
                    else -> SignedIn(me, busy, err, onMail = { mailOpen = true },
                        onRefresh = {
                            scope.launch { busy = true; err = MeAuth.refresh().orEmpty(); busy = false }
                        },
                        onSignOut = { MeAuth.signOut(); err = "" })
                }
            }
        }
    }
}

@Composable
private fun SignInCard(why: String, waiting: Boolean, err: String, busy: Boolean, onSignIn: () -> Unit) {
    val t = Tokens.current
    Card {
        Text("还没登录", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
        Text(
            // 说清会发生什么:点下去会跳出浏览器,不说的话用户会以为程序卡了或者被劫持
            "点下面这个按钮会打开你的浏览器完成登录,授权完自动回到这里。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted,
        )
        if (why.isNotEmpty()) {
            Text(why, Modifier.padding(top = 8.dp), color = t.danger, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onSignIn, enabled = !busy && !waiting) {
            Text(if (waiting) "等浏览器那边授权…" else "用 Yxi 账号登录")
        }
        if (waiting) {
            Text(
                "浏览器已经打开了。授权完这一页会自己继续;要是浏览器没弹出来,再点一次会给你地址。",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted,
            )
        }
        if (err.isNotEmpty()) {
            Text(err, Modifier.padding(top = 10.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SignedIn(
    me: AccountApi.Me,
    busy: Boolean,
    err: String,
    onMail: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    val t = Tokens.current
    TextButton(onMail) { Text("打开信箱 · 未读 ${me.unreadMail} · 待领取 ${me.unclaimedMail}") }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    me.nickname.ifEmpty { "还没起名字" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (me.nickname.isEmpty()) t.textMuted else t.textPrimary,
                )
                if (me.email.isNotEmpty()) {
                    // ⚠️ 登录来源按未知值兜底:服务端以后会冒出新的连接器名,**别写穷举**
                    val from = when (me.signInWith) {
                        "google" -> " · 用 Google 登录"
                        "github" -> " · 用 GitHub 登录"
                        "email" -> " · 邮箱注册"
                        else -> ""
                    }
                    Text(me.email + from, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            TextButton(onClick = onSignOut) { Text("退出登录") }
        }
        if (me.signature.isNotEmpty()) {
            Text(me.signature, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
        }
        if (err.isNotEmpty()) {
            Text(err, Modifier.padding(top = 8.dp), color = t.danger, style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(12.dp))
    Card {
        Text("会员", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (me.tier) {
                    AccountApi.Tier.Ultra -> "Ultra"
                    AccountApi.Tier.Pro -> "Pro"
                    AccountApi.Tier.Free -> "免费"
                },
                style = MaterialTheme.typography.bodyLarge, color = t.accent, fontWeight = FontWeight.Medium,
            )
            Text(
                when {
                    me.tier == AccountApi.Tier.Free -> "· 还不是会员"
                    me.neverExpires -> "· 永久"
                    me.expiresAt != null -> "· " + me.expiresAt!!.take(10) + " 到期"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium, color = t.textMuted,
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("余额", AccountApi.yuan(me.balanceCents))
            Stat("曦光", me.tickets.toString() + (if (me.micro > 0) " + ${me.micro} 微曦" else ""))
        }
    }

    Spacer(Modifier.height(12.dp))
    Card {
        Text("改资料额度", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
        Text(
            // ⚠️ **先看 quotaRemaining 是不是 null**:ultra 是「不限」,而不是「用完了」——
            //    手机端当年就是拿 remaining 判断,把付费用户拦在门外(#229)
            if (me.quotaRemaining == null) "不限"
            else "还能改 ${me.quotaRemaining} 次(共 ${me.quotaLimit} 次)" +
                (me.nextRefreshAt?.take(10)?.let { " · $it 恢复" } ?: ""),
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted,
        )
        if (me.quotaRule.isNotEmpty()) {
            Text(me.quotaRule, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        }
    }

    if (me.unreadMail > 0 || me.unclaimedMail > 0 || me.unreadTickets > 0) {
        Spacer(Modifier.height(12.dp))
        Card {
            Text("待处理", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
            Column(Modifier.padding(top = 8.dp)) {
                // ⚠️ 未读和未领是**两回事**:读过了也可能没领。分开说,别合并成一个数。
                if (me.unreadMail > 0) Text("${me.unreadMail} 封信没读", color = t.textMuted, style = MaterialTheme.typography.bodyMedium)
                if (me.unclaimedMail > 0) Text("${me.unclaimedMail} 封信里有东西没领", color = t.textMuted, style = MaterialTheme.typography.bodyMedium)
                if (me.unreadTickets > 0) Text("${me.unreadTickets} 张工单有新回复", color = t.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "信箱可在本机打开；工单完整操作仍在补齐，可先在手机上处理。",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted,
            )
        }
    }

    if (me.bans.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card {
            Text("账号限制", style = MaterialTheme.typography.titleMedium, color = t.danger)
            me.bans.forEach { b ->
                Text(
                    b.productName + (b.reason?.let{ ":$it" } ?: "") + (b.until?.let { " · " + it.take(10) + " 解除" } ?: " · 永久"),
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val t = Tokens.current
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = t.textPrimary)
    }
}

/** 这一页统一的卡片:面板底 + 细边 + 圆角。颜色一律从 [Tokens] 拿,别写死。 */
@Composable
private fun Card(content: @Composable ColumnScopeAlias.() -> Unit) {
    val t = Tokens.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius))
            .background(t.surface2)
            .border(1.dp, t.border, RoundedCornerShape(Radius))
            .padding(18.dp),
        content = content,
    )
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope
