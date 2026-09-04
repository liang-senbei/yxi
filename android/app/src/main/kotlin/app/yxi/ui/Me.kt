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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 「我」—— 头像 / 昵称 / 个性签名 / 订阅档位。
 *
 * ⚠️ **全在本机**。这一版没有账号系统（logto 那边在做），所以昵称签名头像都只存在这台手机上，
 * 档位固定是 [Tier.Free]。等账号接上，这里换成从服务端读，界面不用动。
 * ⚠️ 头像存成 `filesDir/avatar.png`，进来时压到 256px —— 相册里随手一张就是几 MB，
 * 原图塞进 SharedPreferences 或者每次解码全尺寸都是自找卡顿。
 */
object Me {
    enum class Tier { Free, Pro, Ultra }

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /** 改了任何一项就 +1 —— 界面读它来重新画（头像是文件，不是状态，得手动通知） */
    val rev = mutableIntStateOf(0)

    fun name(ctx: Context): String = p(ctx).getString("me.name", "").orEmpty()
    fun sign(ctx: Context): String = p(ctx).getString("me.sign", "").orEmpty()
    fun tier(ctx: Context): Tier =
        runCatching { Tier.valueOf(p(ctx).getString("me.tier", "Free")!!) }.getOrDefault(Tier.Free)

    fun set(ctx: Context, name: String, sign: String) {
        p(ctx).edit().putString("me.name", name.trim()).putString("me.sign", sign.trim()).apply()
        rev.intValue++
    }

    fun avatar(ctx: Context): File = File(ctx.filesDir, "avatar.png")

    /** 相册选的图存成头像。压到 256px 见方，失败就当没选（不弹错，用户会自己再点一次）。 */
    fun setAvatar(ctx: Context, uri: android.net.Uri) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val side = minOf(src.width, src.height)
            val square = android.graphics.Bitmap.createBitmap(
                src, (src.width - side) / 2, (src.height - side) / 2, side, side,
            )
            val small = android.graphics.Bitmap.createScaledBitmap(square, 256, 256, true)
            avatar(ctx).outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            rev.intValue++
        }
    }
}

/** 圆头像：有图就画图，没有就画个占位小人 */
@Composable
fun MeAvatar(size: androidx.compose.ui.unit.Dp = 56.dp, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rev = Me.rev.intValue
    val bmp = remember(rev) {
        val f = Me.avatar(ctx)
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null
    }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) androidx.compose.foundation.Image(
            bmp, contentDescription = null,
            modifier = Modifier.size(size), contentScale = ContentScale.Crop,
        ) else YxiIcon(Ico.Person, size = size * 0.55f, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** 编辑「我」：昵称 + 个性签名 + 换头像 */
@Composable
fun MeDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(Me.name(ctx)) }
    var sign by remember { mutableStateOf(Me.sign(ctx)) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) Me.setAvatar(ctx, uri) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("我")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MeAvatar(72.dp, Modifier.clickable { pick.launch("image/*") })
                        Text(
                            t("点头像换一张"), Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(t("昵称")) }, singleLine = true)
                OutlinedTextField(sign, { sign = it }, label = { Text(t("个性签名")) }, maxLines = 3)
            }
        },
        confirmButton = { TextButton({ Me.set(ctx, name, sign); onClose() }) { Text(t("保存")) } },
        dismissButton = { TextButton(onClose) { Text(t("取消")) } },
    )
}
