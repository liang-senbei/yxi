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

    /**
     * 已经下好、等着装的那个包。null = 还没有。
     *
     * ⚠️ **留着它是为了能再拉一次安装器。** 用户报过「点击拉起安装器没反应」——
     * 病根是那时候成功态的按钮**根本不可点**，而它的字写着「拉起安装器」。
     * 系统安装器被误关、被 ROM 拦一次、或者装到一半退出来，都得能再来一次；
     * 重下一遍 28 MB 只为了再弹一个框，纯属浪费。
     */
    var ready by mutableStateOf<File?>(null); private set

    /** 再把安装器拉一次（包已经在本地）。失败就把原因写进 [message]。 */
    fun installNow(ctx: Context) {
        val f = ready ?: return
        val why = install(ctx.applicationContext, f)
        if (why != null) { message = why.take(40); phase = MorphPhase.Fail }
    }

    fun start(ctx: Context, ssh: SshSession?, update: Update) {
        if (phase == MorphPhase.Run && forVersion == update.versionCode) return   // 已经在下这版了，别重开
        val app = ctx.applicationContext
        forVersion = update.versionCode; phase = MorphPhase.Run; progress = -1f; message = ""
        scope.launch {
            val r = runCatching {
                val f = File(app.cacheDir, "update/Yxi.apk")
                val (got, sum) = if (update.fromPublic) {
                    // ⚠️ **默认走公网下载页** —— 这样换个客户也能在 App 里更新，
                    // 不需要他自己的服务器上放包（那要我们能登他机器，耦合太深）。
                    downloadHttp(update.url, f) { n ->
                        progress = (n.toFloat() / update.sizeBytes).coerceIn(0f, 1f)
                    }
                } else {
                    // 回落：包在所连服务器上（防火墙后 / 没外网时还能更新）
                    val s = ssh ?: throw RuntimeException(t("没连上"))
                    val sftp = s.openSftp()
                    try {
                        val n0 = sftp.download(update.remotePath, f) { n ->
                            progress = (n.toFloat() / update.sizeBytes).coerceIn(0f, 1f)
                        }
                        // SFTP 那条路下完再算一遍（走的是用户自己的机器，包不大时可接受）
                        n0 to f.inputStream().use { ins ->
                            val md = java.security.MessageDigest.getInstance("SHA-256")
                            val buf = ByteArray(64 * 1024)
                            while (true) { val r2 = ins.read(buf); if (r2 < 0) break; md.update(buf, 0, r2) }
                            md.digest().joinToString("") { "%02x".format(it) }
                        }
                    } finally { runCatching { sftp.close() } }
                }
                // ⚠️ 大小对不上就别装 —— 半个 APK 比不更新糟得多
                if (got != update.sizeBytes) throw RuntimeException(t("下载不完整（%d/%d），没装").format(got, update.sizeBytes))
                // ⚠️ **大小校验挡不住换包**：那个长度来自同一个响应，能换包的人当然也能改长度。
                //    清单里的 sha256 才算数（2026-09-04 安全审计）。
                if (update.sha256.isNotBlank() && !update.sha256.equals(sum, true)) {
                    runCatching { f.delete() }
                    throw RuntimeException(t("包对不上（校验失败），没装 —— 换个网络再试一次"))
                }
                // ⚠️ 再挡一层：**下来的包必须和本机是同一把签名**。这样即便清单和包被一起换掉，
                //    攻击者也没法让我们去装一个「换了包名并排安装」的冒牌货。
                signatureMismatch(app, f)?.let { runCatching { f.delete() }; throw RuntimeException(it) }
                ready = f
                // ⚠️ **这一下不一定拉得起来，而且失败是静默的。**
                // 下载跑在 app scope 上（切页面不断，这是对的），28 MB 在手机网络下要几分钟 ——
                // 下完那一刻用户很可能已经切出去了。而 Android 10 起**后台不许起 Activity**：
                // `startActivity` 不抛异常、也不返回失败，**就是什么都不发生**。
                // 所以这里拉一次是「顺手」，真正的保障是上面那个 `ready` ——
                // 用户回到这一屏，点那个按钮再拉一次（那时 App 在前台，一定拉得起来）。
                install(app, f)?.let { throw RuntimeException(it) }
                t("已下好 · 点一下安装")
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
                    // 下好之后这个按钮还得能点 —— 安装器被误关 / 被 ROM 拦掉都要能再来一次
                    okTap = mine && UpdateDownloader.ready != null,
                ) {
                    if (mine && UpdateDownloader.phase == MorphPhase.Ok) UpdateDownloader.installNow(ctx)
                    else UpdateDownloader.start(ctx, ssh, update)
                }
                OutlinedButton(onDone, shape = Pill, modifier = Modifier.height(46.dp)) { Text(t("以后")) }
            }
        }
    }
}

/** 从公网下载页取包（流式，带进度）。@return 实际字节数 */
/** @return 下了多少字节 to 这包的 sha256（十六进制小写） */
private suspend fun downloadHttp(url: String, into: File, onBytes: (Long) -> Unit): Pair<Long, String> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        into.parentFile?.mkdirs()
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
        }
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            var n = 0L
            // ⚠️ **边下边算**（照抄 AsrModel 那十行）：下完再读一遍要多花几秒，而这个包 50MB
            val md = java.security.MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                into.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r); md.update(buf, 0, r); n += r
                        onBytes(n)
                    }
                }
            }
            n to md.digest().joinToString("") { "%02x".format(it) }
        } finally { c.disconnect() }
    }

/**
 * @return 出错原因；null = 已经把安装器拉起来了
 *
 * ⚠️ 内部可见是为了让 [FileViewer] 复用 —— 从文件页装一个自己编的 APK
 * 跟自更新是**同一件事**：同样要 `REQUEST_INSTALL_PACKAGES`、同样走 FileProvider、
 * 同样要在没给权限时先把人送去设置页。再抄一份只会漏掉其中一条。
 */
internal fun install(ctx: Context, apk: File): String? {
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

/**
 * 下来的 APK 跟本机是不是同一把签名。@return 出错原因；null = 一致（或读不出来，那时不拦）
 *
 * ⚠️ 只在**明确读到两边的证书**时才判不一致。读不出来就放行 —— 这里的目的是挡「换包名的冒牌货」，
 * 不是给系统的签名校验加锁；因为读不出来而把正常更新拦下来，代价比放行大。
 */
private fun signatureMismatch(ctx: Context, apk: File): String? = runCatching {
    val pm = ctx.packageManager
    val flag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
    val theirs = pm.getPackageArchiveInfo(apk.absolutePath, flag)?.signingInfo?.apkContentsSigners
    val ours = pm.getPackageInfo(ctx.packageName, flag).signingInfo?.apkContentsSigners
    if (theirs.isNullOrEmpty() || ours.isNullOrEmpty()) return@runCatching null
    val a = theirs.map { it.toCharsString() }.toSet()
    val b = ours.map { it.toCharsString() }.toSet()
    if (a != b) t("这个包的签名跟本机不一致，没装") else null
}.getOrNull()
