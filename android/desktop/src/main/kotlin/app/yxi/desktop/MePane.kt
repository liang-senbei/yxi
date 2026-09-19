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
import androidx.compose.runtime.DisposableEffect
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
    PreferencesLayout("我的", listOf("个人资料", "账号与连接", "钱包与订单", "商城", "兑换码", "信箱", "工单"), state.meSection,
        { state.meSection = it }, { state.page = Page.Workspace }) { AccountContent(state) }
}

@Composable
private fun AccountContent(state: AppState) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var mailOpen by remember { mutableStateOf(false) }
    var supportOpen by remember { mutableStateOf(false) }
    var walletOpen by remember { mutableStateOf(false) }
    var shopOpen by remember { mutableStateOf(false) }
    var loginRequest by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) { onDispose { MeAuth.cancelPendingSignIn() } }

    // 进页面拉一次:有令牌就顺手刷资料,没令牌就停在登录按钮上
    LaunchedEffect(state.accountLoginRequest) {
        if (state.accountLoginRequest > state.accountLoginHandled) {
            state.accountLoginHandled = state.accountLoginRequest
            busy = true; err = ""
            try { err = MeAuth.signIn(forceLogin = state.accountForceLogin).orEmpty() }
            finally { busy = false }
        } else MeAuth.load()
    }
    LaunchedEffect(state.meSection) {
        mailOpen = state.meSection == "信箱"
        supportOpen = state.meSection == "工单"
        walletOpen = state.meSection == "钱包与订单"
        shopOpen = state.meSection == "商城"
    }
    val owner = MeAuth.me?.userId
    val generation = MeAuth.sessionGeneration
    if (shopOpen && MeAuth.signedIn && owner != null) {
        androidx.compose.runtime.key(owner, generation) { ShopPane(owner, generation, state.shopPurchases) { shopOpen = false; state.meSection = "个人资料" } }
        return
    }
    if (walletOpen && MeAuth.signedIn && owner != null) {
        androidx.compose.runtime.key(owner, generation) { WalletPane(owner, generation, onShop = { state.meSection = "商城" }) { walletOpen = false; state.meSection = "个人资料" } }
        return
    }
    if (supportOpen && MeAuth.signedIn && owner != null) {
        val api = remember(owner, generation) { app.yxi.agent.SupportApi { path, method, body -> MeAuth.accountRequest(owner, path, method, body, generation) } }
        androidx.compose.runtime.key(owner, generation) { SupportPane(owner, api, state.support, onBack = { supportOpen = false; state.meSection = "个人资料" }, onUnread = { MeAuth.supportUnread(owner, it, generation) }) }
        return
    }
    if (mailOpen && MeAuth.signedIn && owner != null) {
        val api = remember(owner, generation) { app.yxi.agent.MailApi { path, method, body -> MeAuth.accountRequest(owner, path, method, body, generation) } }
        androidx.compose.runtime.key(owner, generation) { MailPane(owner, api, onBack = { mailOpen = false; state.meSection = "个人资料"; scope.launch { err = MeAuth.refresh().orEmpty() } }, onCounters = { MeAuth.mailCounters(owner, it, generation) }) }
        return
    }

    Box(Modifier.fillMaxSize().background(t.surface0)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(state.meSection, style = MaterialTheme.typography.headlineSmall, color = t.textPrimary)
                Spacer(Modifier.height(16.dp))
                if (state.meSection == "账号与连接") ConnectionIdentityCard(state)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton({ state.requestAccountLogin(true) }, enabled = !busy && !MeAuth.waitingBrowser) { Text(if (MeAuth.signedIn) "切换账号" else "浏览器登录") }
                    if (MeAuth.signedIn) TextButton({ MeAuth.signOut() }, enabled = !busy) { Text("退出登录") }
                }
                Spacer(Modifier.height(16.dp))

                val me = MeAuth.me
                when {
                    !MeAuth.signedIn -> SignInCard(
                        why = MeAuth.signedOutWhy,
                        waiting = MeAuth.waitingBrowser,
                        err = err,
                        busy = busy,
                        onCancel = { loginRequest++; MeAuth.signOut(); busy = false; err = "" },
                    ) {
                        val request = ++loginRequest
                        scope.launch {
                            busy = true; err = ""
                            try {
                                val problem = MeAuth.signIn(forceLogin = true).orEmpty()
                                if (request == loginRequest) err = problem
                            } finally { if (request == loginRequest) busy = false }
                        }
                    }
                    me == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val problem = err.ifBlank { MeAuth.profileError }
                        Text(problem.ifBlank { "正在读取账户资料…" }, color = if (problem.isBlank()) t.textMuted else t.danger)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ scope.launch { busy = true; try { err = MeAuth.refresh().orEmpty() } finally { busy = false } } }, enabled = !busy) { Text(if (busy) "正在重试…" else "重试") }
                            TextButton({ MeAuth.signOut(); err = "" }) { Text("返回登录") }
                        }
                    }
                    state.meSection == "个人资料" -> SignedIn(me, busy, err, onMail = { state.meSection = "信箱" }, onSupport = { state.meSection = "工单" },
                        onRefresh = {
                            scope.launch { busy = true; err = MeAuth.refresh().orEmpty(); busy = false }
                        },
                        onSignOut = { MeAuth.signOut(); err = "" })
                }
                if (MeAuth.signedIn && owner != null && state.meSection == "兑换码") androidx.compose.runtime.key(owner, generation) {
                    Spacer(Modifier.height(16.dp))
                    RedeemCodeCard(owner, generation)
                }
            }
        }
    }
}

@Composable
private fun ConnectionIdentityCard(state: AppState) {
    val t = Tokens.current
    val conn = state.conn
    val task = conn?.let { state.codexWorkspace.tasks(it.host).firstOrNull { task -> task.key == state.codexSelectedTaskKey } }
    val controller = task?.let { state.codexWorkspace.controllers[it.key] }
    val scope = rememberCoroutineScope()
    var authSummary by remember(conn) { mutableStateOf("") }
    var claudeSummary by remember(conn) { mutableStateOf("") }
    var readingAuth by remember(conn) { mutableStateOf(false) }
    Card {
        Text("账号与连接", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text("Yxi 账号 · " + if (MeAuth.signedIn) "已登录" else "未登录", style = MaterialTheme.typography.bodyMedium)
        Text("用于会员、钱包与信箱。", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Spacer(Modifier.height(10.dp))
        Text("服务器身份", style = MaterialTheme.typography.bodyMedium)
        Text(if (conn == null) "尚未选择服务器" else "${conn.host.label} · ${conn.host.username}@${conn.host.hostname}:${conn.host.port}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (conn != null) Text(if (conn.ssh.isConnected) "SSH 已连接" else "SSH 未连接", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Spacer(Modifier.height(10.dp))
        Text("Agent 提供方", style = MaterialTheme.typography.bodyMedium)
        Text(controller?.configuredProvider?.takeIf { it.isNotBlank() }?.let { "当前 Codex 连接配置：$it" } ?: "由所选服务器的运行器配置管理", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Text(authSummary.ifBlank { "提供方认证状态尚未检查；Yxi 登录和 SSH 连接不代表模型账号已登录。" }, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (conn != null) TextButton({
            readingAuth = true
            scope.launch {
                try {
                    val result = CodexAppServer.connect(conn.ssh).use { it.authenticationSummary() }
                    if (state.conn === conn) authSummary = "$result。此查询不验证模型请求是否可用。"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { if (state.conn === conn) authSummary = "认证状态未读到，请检查服务器连接与 Codex 版本后重试。" }
                finally { readingAuth = false }
            }
        }, enabled = conn.ssh.isConnected && !readingAuth) { Text(if (readingAuth) "正在查询…" else "查询 Codex 认证") }
        if (claudeSummary.isNotBlank()) Text(claudeSummary, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (conn != null) TextButton({
            readingAuth = true
            scope.launch {
                try {
                    val result = ProviderIdentity.claude(conn)
                    if (state.conn === conn) claudeSummary = "$result。独立任务的环境配置可能不同。"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { if (state.conn === conn) claudeSummary = "认证状态未读到，请检查 Claude 版本和服务器连接后重试。" }
                finally { readingAuth = false }
            }
        }, enabled = conn.ssh.isConnected && !readingAuth) { Text("查询 Claude 认证") }
        if (conn != null) TextButton({ state.openRoutes() }) { Text("管理服务器线路") }
    }
}

@Composable
private fun SignInCard(why: String, waiting: Boolean, err: String, busy: Boolean, onCancel: () -> Unit, onSignIn: () -> Unit) {
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
            Text(if (waiting) "等浏览器那边授权…" else if (busy) "正在准备登录…" else "用 Yxi 账号登录")
        }
        if (waiting) {
            TextButton(onCancel) { Text("取消本次登录") }
            Text(
                "浏览器已打开，授权完成后会自动继续。也可以取消本次登录后重试。",
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
    onSupport: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    val t = Tokens.current
    TextButton(onMail) { Text("打开信箱 · 未读 ${me.unreadMail} · 待领取 ${me.unclaimedMail}") }
    TextButton(onSupport) { Text("工单中心 · ${me.unreadTickets} 张有新回复") }
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
            quotaSummary(me),
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
                "可在上方信箱或工单中心查看详情。",
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
