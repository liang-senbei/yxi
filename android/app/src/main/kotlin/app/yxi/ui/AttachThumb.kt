package app.yxi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yxi.ssh.SshSession
import java.io.File

/**
 * 用户消息里那些附件的缩略图。
 *
 * ⚠️ **为什么要做。** 发出去之后，气泡里是
 * `[图片1] /root/src/tmp/begirl/0902-091207-IMG_20260902_091201_037.jpg` ——
 * 一条又长又没用的路径占掉四行，而**用户想看的是那张图**。
 * 用户的原话：「要像参考款一样出一个缩略图，这样就可以方便我们查看，
 * 不是像图三那样只是一条路径」。
 *
 * ⚠️ **图在服务器上，得拉回来。** 发送的那一刻本地还有，但重进对话是从转录读的，
 * 那时候只剩路径。所以走 SFTP 读回来，**按远端路径缓存到本地**，
 * 同一张只拉一次（一张手机拍的图两三 MB，每次重组都拉一遍是灾难）。
 *
 * ⚠️ **拉不到不是错误。** 暂存区 3 天就清（[app.yxi.agent.Attachments.sweep]），
 * 老消息里的图必然拉不到 —— 那时候安静地显示文件名，不弹错、不留一个碎图标。
 */
object Thumbs {

    /** 缓存在 cacheDir/thumb/<路径的哈希>。⚠️ 用哈希不用文件名 —— 文件名会重。 */
    private fun cacheFile(ctx: android.content.Context, remote: String): File {
        val h = remote.hashCode().toString().replace("-", "n")
        return File(File(ctx.cacheDir, "thumb").apply { mkdirs() }, "$h.jpg")
    }

    /** 内存里再挡一层：同一屏里同一张图会被多次组合。 */
    private val mem = HashMap<String, ImageBitmap?>()

    /**
     * 发送那一刻把**本地那份**塞进缓存 —— 图就在这台手机上，没理由再从服务器拉一遍。
     *
     * ⚠️ 用户报的「割裂」就在这儿：发出去先是一条路径，等 SFTP 拉回来才变成图。
     * 而那张图**刚刚才从这台手机传上去**。发送前先按远端路径把缩略图种进 [mem] 和磁盘缓存，
     * 之后不管是「排队中」还是真正入了转录的气泡，第一帧就是图。
     * ⚠️ 读失败不算错（授权过期之类）—— 那就退回 [load] 走远端，跟原来一样。
     */
    fun seed(ctx: android.content.Context, staged: List<app.yxi.agent.Attachments.Staged>) {
        for (st in staged) {
            if (!st.isImage || st.localUri == null || mem.containsKey(st.remotePath)) continue
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(android.net.Uri.parse(st.localUri))
                    ?.use { it.readBytes() } ?: return@runCatching
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var s2 = 1
                while (opts.outWidth / s2 > 480 || opts.outHeight / s2 > 480) s2 *= 2
                val bm = android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = s2 },
                ) ?: return@runCatching
                runCatching {
                    cacheFile(ctx, st.remotePath).outputStream()
                        .use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
                }
                mem[st.remotePath] = bm.asImageBitmap()
            }
        }
    }

    suspend fun load(
        ctx: android.content.Context, ssh: SshSession?, remote: String,
    ): ImageBitmap? {
        mem[remote]?.let { return it }
        if (mem.containsKey(remote)) return null          // 拉过、确实没有
        val f = cacheFile(ctx, remote)
        if (!f.exists()) {
            val s = ssh ?: return null
            val sftp = app.yxi.ssh.catching { s.openSftp() }.getOrNull() ?: return null
            try {
                // ⚠️ 有上限：手机拍的原图动辄十几 MB，全读回来只为画 120dp 的缩略图不值当。
                // 超过 8MB 的直接放弃 —— 显示文件名比把 App 拖死好。
                val bytes = app.yxi.ssh.catching { sftp.read(remote, 8 shl 20) }.getOrNull()
                    ?: return null.also { mem[remote] = null }
                // ⚠️ **先按采样率解码再存。** 原图直接进内存就是几十 MB 的 Bitmap，
                // 几张就 OOM。`inSampleSize` 让解码器在读的时候就缩。
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val want = 480
                var s2 = 1
                while (opts.outWidth / s2 > want || opts.outHeight / s2 > want) s2 *= 2
                val bm = android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = s2 },
                ) ?: return null.also { mem[remote] = null }
                runCatching {
                    f.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
                }
                val img = bm.asImageBitmap()
                mem[remote] = img
                return img
            } finally { runCatching { sftp.close() } }
        }
        val bm = runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        val img = bm?.asImageBitmap()
        mem[remote] = img
        return img
    }
}

/**
 * 一张附件缩略图。图片画图，别的画一个带文件名的小卡片。
 *
 * ⚠️ **点开是放大看原图**，不是打开路径 —— 用户要的是内容。
 */
@Composable
fun AttachThumb(
    ref: app.yxi.agent.Attachments.Ref,
    ssh: SshSession?,
    onOpen: (app.yxi.agent.Attachments.Ref) -> Unit,
) {
    val ctx = LocalContext.current
    if (!ref.isImage) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(ref) },
        ) {
            // ⚠️ Glyph 里没有「文件」这个图标，不为一个小卡片新画一个 path ——
            // 文件名本身就说明了它是什么。
            Row(
                Modifier.padding(12.dp, 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ref.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        return
    }
    var img by remember(ref.path) { mutableStateOf<ImageBitmap?>(null) }
    var tried by remember(ref.path) { mutableStateOf(false) }
    LaunchedEffect(ref.path, ssh) {
        img = Thumbs.load(ctx, ssh, ref.path)
        tried = true
    }
    Box(
        Modifier.size(112.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = img != null) { onOpen(ref) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            img != null -> Image(
                img!!, ref.name,
                Modifier.size(112.dp), contentScale = ContentScale.Crop,
            )
            !tried -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            // ⚠️ 拉不到就写文件名 —— **不画碎图标**。3 天前的图被清掉是正常的，
            // 那不是错误，别让界面看起来像坏了。
            else -> Text(
                ref.name.takeLast(14),
                Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
