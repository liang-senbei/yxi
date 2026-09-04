package app.yxi.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yxi.agent.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「我」—— 头像 / 昵称 / 个性签名。
 *
 * ⚠️ **登录之后以服务端为准**（会员服务 `GET /api/me`）。没登录时才用本机存的那份 ——
 * 那是 0.9.97 的过渡版，现在只当「还没登录时也有个名字可看」。
 * ⚠️ **改资料要扣配额**（免费档每月 1 次、pro 每月 2 次、ultra 不限），所以编辑框是
 * 「一起改、一次保存」：一次提交算一次，不管改了几个字段。
 */
object Me {

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /** 本机那份改了就 +1，界面重画（头像是文件不是状态，得手动通知） */
    val rev = mutableIntStateOf(0)

    /** 显示用的昵称：登录了用服务端的，没登录用本机的 */
    fun name(ctx: Context): String =
        Account.me?.nickname?.takeIf { it.isNotBlank() } ?: p(ctx).getString("me.name", "").orEmpty()

    fun sign(ctx: Context): String =
        Account.me?.signature?.takeIf { it.isNotBlank() } ?: p(ctx).getString("me.sign", "").orEmpty()

    fun setLocal(ctx: Context, name: String, sign: String) {
        p(ctx).edit().putString("me.name", name.trim()).putString("me.sign", sign.trim()).apply()
        rev.intValue++
    }

    fun avatarFile(ctx: Context): File = File(ctx.filesDir, "avatar.png")

    /** 服务端头像下下来存本地，按 URL 分文件名 —— 每次进 App 重下一遍没必要 */
    fun cachedRemote(ctx: Context, url: String): File =
        File(ctx.cacheDir, "avatar-" + url.hashCode().toString(16) + ".img")
}

/** 圆头像：服务端的 > 本机存的 > 占位小人 */
@Composable
fun MeAvatar(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rev = Me.rev.intValue
    val url = Account.me?.avatar
    var bmp by remember(rev, url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(rev, url) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                if (url != null) {
                    val f = Me.cachedRemote(ctx, url)
                    if (!f.exists() || f.length() == 0L) {
                        java.net.URL(url).openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                    }
                    BitmapFactory.decodeFile(f.path)?.asImageBitmap()
                } else {
                    Me.avatarFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
                }
            }.getOrNull()
        }
    }
    // 档位光环（规格由 cc-logto_yxi 给：service/admin/halo-spec.html）。
    // ⚠️ 档位读服务端的 tier，**不自己算**；免费档没有光环。
    val tier = if (Account.signedIn) Account.me?.tier ?: Account.Tier.Free else Account.Tier.Free
    val motion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
    val ring = when (tier) {
        Account.Tier.Pro -> listOf(Color(0xFF346BF0), Color(0xFF8AB4F8), Color(0xFF346BF0))
        Account.Tier.Ultra -> listOf(Color(0xFFFDBE5A), Color(0xFFF59E8C), Color(0xFFFFE1A8), Color(0xFFFDBE5A))
        else -> emptyList()
    }
    val spin = rememberInfiniteTransition(label = "halo")
    // 转一圈：pro 12 秒、ultra 6 秒。⚠️ 系统开了「减弱动效」必须停转（规格里明写的）
    val angle by spin.animateFloat(
        0f, 360f,
        InfiniteRepeatableSpec(tween(if (tier == Account.Tier.Ultra) 6000 else 12000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val breath by spin.animateFloat(
        0f, 1f, InfiniteRepeatableSpec(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mist",
    )
    val a = if (motion) angle else 0f
    val br = if (motion) breath else 0.5f
    val gap = 2.5.dp
    val stroke = if (tier == Account.Tier.Ultra) 3.5.dp else 2.5.dp
    Box(
        modifier.size(size + (gap + stroke) * 2).drawBehind {
            if (ring.isEmpty()) return@drawBehind
            val c = androidx.compose.ui.geometry.Offset(size.toPx() / 2 + (gap + stroke).toPx(), size.toPx() / 2 + (gap + stroke).toPx())
            val rr = size.toPx() / 2 + gap.toPx() + stroke.toPx() / 2
            val ultra = tier == Account.Tier.Ultra
            // 外圈薄雾（只有 ultra）：1.34× 头像，呼吸
            if (ultra) drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFDBE5A).copy(alpha = 0.16f * (0.7f + 0.3f * br)), Color.Transparent),
                    center = c, radius = size.toPx() * 0.67f,
                ),
                radius = size.toPx() * 0.67f, center = c,
            )
            // 外层发光
            drawCircle(
                Brush.radialGradient(
                    listOf(
                        (if (ultra) Color(0xFFF59E8C) else Color(0xFF346BF0)).copy(alpha = if (ultra) 0.22f else 0.18f),
                        Color.Transparent,
                    ),
                    center = c, radius = rr + (if (ultra) 40 else 28).dp.toPx() * 0.5f,
                ),
                radius = rr + (if (ultra) 40 else 28).dp.toPx() * 0.5f, center = c,
            )
            // 内层发光
            drawCircle(
                Brush.radialGradient(
                    0.72f to Color.Transparent,
                    1f to (if (ultra) Color(0xFFFDBE5A) else Color(0xFF346BF0)).copy(alpha = if (ultra) 0.50f else 0.45f),
                    center = c, radius = rr + (if (ultra) 16 else 12).dp.toPx() * 0.5f,
                ),
                radius = rr + (if (ultra) 16 else 12).dp.toPx() * 0.5f, center = c,
            )
            // 环本身：扫描渐变描边，转起来
            rotate(a, c) {
                drawCircle(
                    Brush.sweepGradient(ring, c), radius = rr, center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke.toPx()),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                // 内描边：1dp 白 6%，三档都有 —— 头像本身浅色时不至于糊在底上
                .drawBehind {
                    drawCircle(
                        Color.White.copy(alpha = 0.06f), radius = size.toPx() / 2 - 0.5.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) androidx.compose.foundation.Image(
                b, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop,
            ) else YxiIcon(Ico.Person, size = size * 0.55f, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * 编辑「我」。登录了就写服务端（一次保存 = 一次配额），没登录只存本机 + 给个登录入口。
 *
 * ⚠️ 头像现在**改不了**：Logto 只存 URL，自定义上传要等对象存储（R2）配好；
 * 社交注册那次带过来的头像会自己显示。不做「点了没反应」的假按钮。
 */
@Composable
fun MeDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val signed = Account.signedIn
    var name by remember { mutableStateOf(Me.name(ctx)) }
    var sign by remember { mutableStateOf(Me.sign(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val q = Account.me
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(t("我")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MeAvatar(72.dp) }
                OutlinedTextField(
                    // ⚠️ 只挡控制字符和长度。HTML 转义**不在这儿做** —— 后台已经改成事件委托 + 转义，
                    //    修在 sink 端才是正解；客户端过滤只是噪音，改包的人绕得过去。
                    name, { v -> v.filterNot(Char::isISOControl).take(24).let { name = it } },
                    label = { Text(t("昵称")) }, singleLine = true,
                    supportingText = { Text(t("1–24 个字")) },
                )
                OutlinedTextField(
                    sign, { v -> v.filterNot(Char::isISOControl).take(60).let { sign = it } },
                    label = { Text(t("个性签名")) }, maxLines = 3,
                    supportingText = { Text(t("最多 60 个字")) },
                )
                if (signed && q != null) Text(
                    when {
                        q.quotaRemaining == null -> t("改多少次都行")
                        else -> t("这个月还能改 %d 次").format(q.quotaRemaining) +
                            (q.nextRefreshAt?.take(10)?.let { " · " + t("%s 恢复").format(it) } ?: "")
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                )
                if (!signed) Text(
                    t("还没登录 —— 现在改只存在这台手机上"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                )
                err?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (!signed) { Me.setLocal(ctx, name, sign); onClose(); return@TextButton }
                    busy = true; err = null
                    scope.launch {
                        // ⚠️ 一次提交算一次配额 —— 所以昵称和签名一起发，没改的字段不发
                        val e = Account.saveProfile(
                            ctx,
                            // ⚠️ 空昵称别发：服务端会拒，而这一次提交照样扣一格配额
                            nickname = name.trim().takeIf { it.isNotEmpty() && it != q?.nickname },
                            signature = sign.trim().takeIf { it != q?.signature },
                        )
                        busy = false
                        if (e == null) onClose() else err = e
                    }
                },
            ) { Text(if (busy) t("保存中…") else t("保存")) }
        },
        dismissButton = {
            if (!signed) TextButton({ Account.startLogin(ctx); onClose() }) { Text(t("登录")) }
            else TextButton({ if (!busy) onClose() }) { Text(t("取消")) }
        },
    )
}
