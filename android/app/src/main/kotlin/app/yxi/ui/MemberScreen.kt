package app.yxi.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.yxi.agent.Account
import kotlinx.coroutines.launch

/**
 * 会员中心 —— 档位 / 到期 / 这个月还能改几次 / 兑换码。
 *
 * ⚠️ **整页就是 `GET /api/me` 的渲染**（cc-logto_yxi 的会员服务）。等级是 Logto 角色说了算，
 * 配额和到期是服务端算的 —— App 这边一个数都不自己推。
 * ⚠️ 还不能买：支付没做。所以两档只写「有什么」，按钮是「即将开放」，
 * 但**兑换码是真的能用**（默认 31 天）。
 */
@Composable
fun MemberScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val me = Account.me
    val tier = me?.tier ?: Account.Tier.Free
    var loading by remember { mutableStateOf(false) }
    // 进来拉一次最新的（会员到期、配额都可能在别处变了）
    LaunchedEffect(Unit) {
        Account.loadPurchase()          // 公开接口，没登录也拉 —— 价格是给还没买的人看的
        if (Account.signedIn) { loading = true; Account.refresh(ctx); loading = false }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp, 12.dp, 18.dp, 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "←", Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(10.dp, 4.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(t("会员中心"), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeAvatar(52.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    Me.name(ctx).ifBlank { if (Account.signedIn) t("还没设昵称") else t("还没登录") },
                    style = MaterialTheme.typography.titleMedium,
                )
                val line = when {
                    !Account.signedIn -> t("登录后才能看会员状态")
                    tier == Account.Tier.Free -> t("当前：免费版")
                    me?.neverExpires == true -> t("当前：%s · 永久").format(tier.name)
                    me?.expiresAt != null -> t("当前：%s · %s 到期").format(tier.name, me.expiresAt.take(10))
                    else -> t("当前：%s").format(tier.name)
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (!Account.signedIn) Button({ Account.startLogin(ctx) }, shape = RoundedCornerShape(50)) { Text(t("登录")) }
        }
        // 掉登录了要说一句 —— 页面突然变回「未登录」而不给理由，最让人发毛
        Account.signedOutWhy?.takeIf { !Account.signedIn }?.let {
            Spacer(Modifier.height(8.dp))
            Text(t(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (Account.signedIn && me != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    me.quotaRemaining == null -> t("资料改多少次都行")
                    else -> t("这个月还能改 %d 次资料").format(me.quotaRemaining) +
                        (me.nextRefreshAt?.take(10)?.let { " · " + t("%s 恢复").format(it) } ?: "")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        me?.bans?.firstOrNull()?.let { ban ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        t("这个账号被封禁了"), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        t("原因：%s").format(ban.reason ?: t("未说明")) + " · " +
                            (ban.until?.take(10)?.let { t("到 %s").format(it) } ?: t("永久")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        t("封禁期间改不了资料、也兑不了码。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = .8f),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        RedeemBox()
        Spacer(Modifier.height(12.dp))
        BuyBox()
        Spacer(Modifier.height(18.dp))
        Plan(
            name = t("免费版"), tagline = t("一台机器，够用"),
            accent = MaterialTheme.colorScheme.outline, own = tier == Account.Tier.Free,
            lines = listOf(t("1 台主机"), t("会话看板 + 对话 + 终端"), t("本机语音转写"), t("资料每月能改 1 次")),
        )
        Spacer(Modifier.height(12.dp))
        Plan(
            name = "Pro", tagline = t("多机器盯梢，随手就批"),
            price = Account.purchase?.plans?.firstOrNull { it.tier == "pro" },
            accent = Color(0xFF4C8DF6), own = tier == Account.Tier.Pro,
            lines = listOf(t("主机不限台"), t("后台盯梢 + 通知里直接批"), t("实验室：让 agent 画图"), t("资料每月能改 2 次")),
        )
        Spacer(Modifier.height(12.dp))
        Plan(
            name = "Ultra", tagline = t("整队 agent 一起带"),
            price = Account.purchase?.plans?.firstOrNull { it.tier == "ultra" },
            accent = Color(0xFFB07CFF), own = tier == Account.Tier.Ultra,
            lines = listOf(t("Pro 的全部"), t("多设备同步"), t("实验室额度更高"), t("资料改多少次都行")),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            Account.purchase?.note ?: t("买到兑换码之后，回来在上面输入就能开通。"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        if (Account.signedIn) {
            Spacer(Modifier.height(18.dp))
            TextButton({ Account.signOut(ctx) }) { Text(t("退出登录"), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * 兑换码。⚠️ 输入自动大写 + 自动补连字符：码里没有 0O1IL，手输很容易打错格式。
 * ⚠️ 同一个人重兑同一张码是**安全的**（服务端幂等，不重复加天数）—— 断网重试不用怕。
 */
@Composable
fun RedeemBox(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YxiIcon(Ico.Crown, size = 20.dp, tint = Color(0xFFE8912D))
                Text(t("兑换码"), style = MaterialTheme.typography.titleMedium)
            }
            // ⚠️ **存的是「纯码」，连字符只是画出来的**（VisualTransformation）。
            //    第一版是每敲一个字就把整串重排一遍再写回去 —— 输入快一点（或粘贴）就会**串位**：
            //    实测用 adb 灌进去 18 个字符，9 个位置对不上。重写输入框的内容是要付代价的，
            //    能用「只改显示」解决就别改值。
            OutlinedTextField(
                code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(15) },
                placeholder = { Text("YXI-XXXX-XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = DashedCode,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
            )
            msg?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = !busy && code.length >= 8,
                onClick = {
                    if (!Account.signedIn) { msg = t("先登录再兑换"); ok = false; return@Button }
                    busy = true; msg = null
                    scope.launch {
                        val r = Account.redeem(ctx, tidyCode(code))
                        busy = false
                        ok = r.isSuccess
                        msg = r.getOrNull() ?: r.exceptionOrNull()?.message
                        if (r.isSuccess) code = ""
                    }
                },
                shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) t("兑换中…") else t("兑换")) }
        }
    }
}

/** 把纯码画成 `YXI-XXXX-XXXX-XXXX`：只动显示，不动输入框里存的值 */
private val DashedCode = androidx.compose.ui.text.input.VisualTransformation { text ->
    val body = text.text
    val shown = tidyCode(body)
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(shown),
        object : androidx.compose.ui.text.input.OffsetMapping {
            // 前 3 位之后每 4 位多一个连字符
            override fun originalToTransformed(offset: Int) = offset + when {
                offset <= 3 -> 0
                offset <= 7 -> 1
                offset <= 11 -> 2
                else -> 3
            }
            override fun transformedToOriginal(offset: Int) = offset - when {
                offset <= 3 -> 0
                offset <= 8 -> 1
                offset <= 13 -> 2
                else -> 3
            }
        },
    )
}

/** 大写、去掉乱七八糟的字符、每 4 位补一个连字符（YXI-XXXX-XXXX-XXXX） */
private fun tidyCode(raw: String): String {
    val body = raw.uppercase().filter { it.isLetterOrDigit() }.take(15)
    if (body.length <= 3) return body
    val rest = body.drop(3).chunked(4)
    return (listOf(body.take(3)) + rest).joinToString("-")
}

@Composable
private fun Plan(
    name: String, tagline: String, accent: Color, own: Boolean, lines: List<String>,
    price: Account.Plan? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 顶上一条渐变，区分档位；不是整块上色 —— 卡片一花，字就难读
            Box(Modifier.fillMaxWidth().height(4.dp).background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.15f)))))
            Column(Modifier.padding(16.dp, 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YxiIcon(Ico.Crown, size = 20.dp, tint = accent)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (own) Text(
                        t("当前"),
                        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(8.dp, 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    // ⚠️ 价格来自 `/api/purchase`，**不写进 APK** —— 老板改价不用等发版
                    if (price != null) Text(
                        "¥${price.price}", style = MaterialTheme.typography.titleMedium, color = accent,
                    ) else if (!own) Text(
                        t("即将开放"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    tagline + (price?.let { " · " + t("%d 天").format(it.days) } ?: ""),
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(10.dp))
                lines.forEach {
                    Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * 怎么买。⚠️ **不接支付**：去发卡站或加微信买兑换码，回来在上面那个框里输入。
 * 价格、店铺地址、微信号和二维码**全部来自 `/api/purchase`**，一个字都不写进 APK ——
 * 老板改价换码不用等发版（logto_yxi 2026-09-04）。
 */
@Composable
private fun BuyBox() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p = Account.purchase ?: return
    var qr by remember(p.qrUrl) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var showQr by remember { mutableStateOf(false) }
    LaunchedEffect(p.qrUrl, showQr) {
        if (showQr && qr == null) qr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                java.net.URL(p.qrUrl).openStream().use { it.readBytes() }.let { bytes ->
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("怎么买"), style = MaterialTheme.typography.titleMedium)
            p.plans.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "¥${it.price} · ${it.desc}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.shopUrl))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    shape = RoundedCornerShape(50), modifier = Modifier.weight(1f),
                ) { Text(t("去商店买")) }
                TextButton({ showQr = !showQr }, modifier = Modifier.weight(1f)) {
                    Text(if (showQr) t("收起微信") else t("加微信买"))
                }
            }
            if (showQr) {
                Column(
                    Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    qr?.let {
                        androidx.compose.foundation.Image(
                            it, contentDescription = null,
                            modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(12.dp)),
                        )
                    } ?: Text(t("二维码加载中…"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(
                        t("微信号 %s（%s）· 长按复制").format(p.wechatId, p.wechatName),
                        Modifier.clickable {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("wechat", p.wechatId))
                            android.widget.Toast.makeText(ctx, t("微信号复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
