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
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(
            b, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop,
        ) else YxiIcon(Ico.Person, size = size * 0.55f, tint = MaterialTheme.colorScheme.onSecondaryContainer)
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
                    name, { if (it.length <= 24) name = it }, label = { Text(t("昵称")) }, singleLine = true,
                    supportingText = { Text(t("1–24 个字")) },
                )
                OutlinedTextField(
                    sign, { if (it.length <= 60) sign = it }, label = { Text(t("个性签名")) }, maxLines = 3,
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
                            nickname = name.trim().takeIf { it != q?.nickname },
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
