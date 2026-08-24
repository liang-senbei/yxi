package app.yxi.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.yxi.agent.Update
import app.yxi.ssh.Sftp
import app.yxi.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

private val Pill = RoundedCornerShape(100.dp)

/**
 * 「有新版本」的横幅。
 *
 * ⚠️ **只在真的有更新时出现**，别的时候一行都不占 —— 更新提示是最容易变成噪音的东西。
 * 装不装是系统和用户说了算：我们只负责把包下下来、把系统安装器拉起来。
 */
@Composable
fun UpdateBanner(sftp: Sftp?, update: Update?, onDone: () -> Unit) {
    if (update == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember(update.versionCode) { mutableStateOf(-1f) }
    var err by remember(update.versionCode) { mutableStateOf<String?>(null) }

    Surface(color = SurfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp, 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("有新版本 %s").format(update.versionName), style = MaterialTheme.typography.labelLarge, color = Copper)
                Spacer(Modifier.weight(1f))
                Text(
                    update.sizeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Dim,
                )
            }
            if (update.notes.isNotBlank()) {
                Text(update.notes, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 3)
            }
            err?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

            if (progress >= 0f) {
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth(), color = Copper)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val s = sftp ?: return@Button
                            scope.launch {
                                progress = 0f; err = null
                                val f = File(ctx.cacheDir, "update/Yxi.apk")
                                val got = runCatching {
                                    s.download(update.remotePath, f) { n ->
                                        progress = (n.toFloat() / update.sizeBytes).coerceIn(0f, 1f)
                                    }
                                }.getOrElse { progress = -1f; err = t("下载失败：%s").format(it.message); return@launch }
                                // ⚠️ 大小对不上就别装 —— 半个 APK 装上去的后果比不更新糟得多
                                if (got != update.sizeBytes) {
                                    progress = -1f; err = t("下载不完整（%d/%d），没装").format(got, update.sizeBytes); return@launch
                                }
                                progress = -1f
                                install(ctx, f)?.let { err = it }
                            }
                        },
                        shape = Pill, modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text(t("下载并安装")) }
                    OutlinedButton(onDone, shape = Pill, modifier = Modifier.height(44.dp)) { Text(t("以后")) }
                }
            }
        }
    }
}

/** @return 出错原因；null = 已经把安装器拉起来了 */
private fun install(ctx: Context, apk: File): String? {
    if (android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
        // 没给「安装未知应用」的权限就先送去设置页 —— 直接 startActivity 会被静默拒
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return t("先在设置里允许本应用安装，然后再点一次")
    }
    return runCatching {
        val uri = FileProvider.getUriForFile(ctx, "app.yxi.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        null
    }.getOrElse { t("拉不起安装器：%s").format(it.message) }
}
