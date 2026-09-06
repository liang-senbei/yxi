package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.LinkPreview
import app.yxi.ssh.SshSession

/**
 * 对话里网址的预览卡片（老板 2026-09-06）。
 *
 * ⚠️ **默认「点了才抓」**（[LinkPreview.Prefs.MANUAL]）：抓一次 = 访问它一次，
 * 而对话里的网址不全是「网站」—— 见 [LinkPreview] 类注释里那条一次性凭据的坑。
 * 客户可以在「设置 → 链接预览」里改成自动或关掉。
 *
 * ⚠️ **图片从手机直连下**（沿用 MailScreen 那套缓存，不引图片库）；抓 og 标签走服务器。
 * 两件事分开：og 是「要不要访问这个站」的问题，图片是「这张图从哪儿加载」的问题。
 */
@Composable
fun LinkCard(url: String, ssh: SshSession?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mode = remember { LinkPreview.Prefs.mode(ctx) }
    if (mode == LinkPreview.Prefs.OFF) return
    if (!LinkPreview.previewable(url)) return

    var card by remember(url) { mutableStateOf<LinkPreview.Card?>(null) }
    var loading by remember(url) { mutableStateOf(false) }
    var problem by remember(url) { mutableStateOf<LinkPreview.Result?>(null) }
    // 自动模式下进屏就抓；手动模式等用户点
    var want by remember(url) { mutableStateOf(mode == LinkPreview.Prefs.AUTO) }

    LaunchedEffect(url, want, ssh) {
        if (!want || card != null || loading || ssh == null) return@LaunchedEffect
        loading = true
        when (val r = LinkPreview.fetch(ssh, url)) {
            is LinkPreview.Result.Ok -> { card = r.card; problem = null }
            else -> problem = r
        }
        loading = false
    }

    val c = card
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
            // 卡片出来之后点它 = 打开；还没出来点它 = 去抓
            // ⚠️ 自己开浏览器，不往 ChatScreen 那个共用文件里再加一个回调参数。
            .clickable {
                if (c != null) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(ctx, t("打不开浏览器：%s").format(it.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                    }
                } else want = true
            },
    ) {
        when {
            c != null -> Column {
                c.image.takeIf { it.isNotBlank() }?.let { img -> CardImage(img) }
                Column(Modifier.padding(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    if (c.title.isNotBlank()) Text(
                        c.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // ⚠️ 抓不到就**如实说**，别摆一张空卡装作有内容（STYLE.md「不骗人」）。
            //    而且要分清是什么问题 —— 「没装 curl」是用户装一个就能解决的，
            //    混在「取不到」里说等于让他以为功能坏了。
            problem == LinkPreview.Result.NoCurl -> Stub(t("这台机器上没有 curl —— 装上就能预览"), null)
            problem != null -> Stub(t("这个链接取不到预览"), null)
            loading -> Stub(t("正在取预览…"), true)
            else -> Stub(t("点一下取预览"), null)
        }
    }
}

/**
 * 卡片的头图。
 * ⚠️ **沿用 [MailScreen] 那套**（`Me.cachedRemote` 落盘缓存 + BitmapFactory），
 *    不为一张预览图引 Coil —— 项目里本来就没有图片库，加一个是 2MB 起步。
 * ⚠️ 这一张是**手机直连**去下的：og 标签走服务器是「要不要访问这个站」的问题，
 *    图片是「从哪儿加载」的问题，两件事分开。取不到就整块不画，不留空白框。
 */
@Composable
private fun CardImage(url: String) {
    val ctx = LocalContext.current
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
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
    bmp?.let {
        androidx.compose.foundation.Image(
            it, contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun Stub(text: String, spinning: Boolean?) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (spinning == true) CircularProgressIndicator(Modifier.height(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    }
}
