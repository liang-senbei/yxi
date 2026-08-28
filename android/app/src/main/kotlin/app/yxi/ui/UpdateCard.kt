package app.yxi.ui

import android.content.Context
import android.content.Intent
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
import app.yxi.ssh.SshSession
import app.yxi.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private val Pill = RoundedCornerShape(100.dp)

/**
 * 更新下载器 —— **挂在 app 级 scope 上，活得比任何界面久。**
 *
 * ⚠️ 病根（用户报的）：以前下载跑在更新横幅那个 composable 的 `rememberCoroutineScope` 上，
 * 而且用的是**看板界面持有的 SFTP 通道**。一进会话，看板界面销毁 → 协程被取消、SFTP 通道被关，
 * **下载就停了**。用户原话：点更新，切进会话再退出就停了。
 *
 * 现在：状态（进度/成败）放这个单例里（Compose 能观察 `mutableStateOf`），下载跑在**永不取消的
 * app scope**，而且**自己从常驻的 ssh 连接开一条新 SFTP 通道**（ssh 由 MainActivity 持有，跨界面活着）。
 * 于是切页面、退会话都不影响，下完自动拉起安装器。横幅只是个「显示器」，读这里的状态画进度。
 */
object UpdateDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var forVersion by mutableIntStateOf(-1); private set
    var phase by mutableStateOf(MorphPhase.Idle); private set
    var progress by mutableFloatStateOf(-1f); private set
    var message by mutableStateOf(""); private set

    fun start(ctx: Context, ssh: SshSession?, update: Update) {
        if (phase == MorphPhase.Run && forVersion == update.versionCode) return   // 已经在下这版了，别重开
        val app = ctx.applicationContext
        forVersion = update.versionCode; phase = MorphPhase.Run; progress = -1f; message = ""
        scope.launch {
            val r = runCatching {
                val s = ssh ?: throw RuntimeException(t("没连上"))
                // ⚠️ 自己开一条 SFTP，别用界面那条（那条会随界面销毁被关）
                val sftp = s.openSftp()
                try {
                    val f = File(app.cacheDir, "update/Yxi.apk")
                    val got = sftp.download(update.remotePath, f) { n ->
                        progress = (n.toFloat() / update.sizeBytes).coerceIn(0f, 1f)
                    }
                    // ⚠️ 大小对不上就别装 —— 半个 APK 比不更新糟得多
                    if (got != update.sizeBytes) throw RuntimeException(t("下载不完整（%d/%d），没装").format(got, update.sizeBytes))
                    install(app, f)?.let { throw RuntimeException(it) }
                    t("拉起安装器")
                } finally { runCatching { sftp.close() } }
            }
            r.onSuccess { message = it; phase = MorphPhase.Ok }
                .onFailure { message = (it.message ?: t("下载失败")).take(40); phase = MorphPhase.Fail }
        }
    }
}

/**
 * 「有新版本」的横幅。**只显示状态**，真正的下载在 [UpdateDownloader]（app scope，切页面不断）。
 *
 * ⚠️ **只在真的有更新时出现**，别的时候一行都不占 —— 更新提示是最容易变成噪音的东西。
 */
@Composable
fun UpdateBanner(ssh: SshSession?, update: Update?, onDone: () -> Unit) {
    if (update == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mine = UpdateDownloader.forVersion == update.versionCode

    Surface(color = SurfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp, 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("有新版本 %s").format(update.versionName), style = MaterialTheme.typography.labelLarge, color = Copper)
                Spacer(Modifier.weight(1f))
                Text(update.sizeText, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = Dim)
            }
            if (update.notes.isNotBlank()) {
                Text(update.notes, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MorphButton(
                    phase = if (mine) UpdateDownloader.phase else MorphPhase.Idle,
                    label = t("下载并安装"),
                    modifier = Modifier.weight(1f), height = 46.dp,
                    msg = if (mine) UpdateDownloader.message else "",
                    progress = if (mine) UpdateDownloader.progress else -1f,
                ) { UpdateDownloader.start(ctx, ssh, update) }
                OutlinedButton(onDone, shape = Pill, modifier = Modifier.height(46.dp)) { Text(t("以后")) }
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
