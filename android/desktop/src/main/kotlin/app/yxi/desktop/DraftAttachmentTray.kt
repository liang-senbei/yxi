package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.imageio.ImageIO

internal fun attachmentLabels(items: List<DraftAttach>): List<String> {
    var image = 0; var file = 0
    return items.map { if (it.isImage) "图片${++image}" else "附件${++file}" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DraftAttachmentTray(items: List<DraftAttach>, remove: (DraftAttach) -> Unit,
    labels: List<String> = attachmentLabels(items), showTransferStatus: Boolean = true) {
    if (items.isEmpty()) return
    require(labels.size == items.size)
    var preview by remember { mutableStateOf<DraftAttach?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { index, attachment -> key(attachment.stamp) {
            val bitmap = attachmentBitmap(attachment, 160)
            TooltipArea(tooltip = { Surface(shadowElevation = 4.dp, shape = RoundedCornerShape(6.dp)) {
                Text(labels[index] + if (attachment.isImage) "" else " · ${attachment.name}", Modifier.padding(8.dp))
            } }) {
                Box(Modifier.size(if (attachment.isImage) 80.dp else 150.dp, 80.dp).clip(RoundedCornerShape(14.dp))
                    .background(Tokens.current.surface1).border(0.5.dp, Tokens.current.border, RoundedCornerShape(14.dp))
                    .clickable { preview = attachment }) {
                    if (bitmap != null) Image(bitmap, labels[index], Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(Modifier.align(Alignment.Center).padding(8.dp)) {
                        Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(22.dp))
                        Text(if (attachment.isImage) labels[index] else attachment.name, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                    }
                    if (showTransferStatus) when (val status = attachment.state) {
                        DraftState.Waiting -> CircularProgressIndicator(Modifier.align(Alignment.BottomStart).padding(5.dp).size(14.dp), strokeWidth = 2.dp)
                        is DraftState.Uploading -> LinearProgressIndicator(progress = { status.percent / 100f }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
                        is DraftState.Failed -> Text("上传失败", Modifier.align(Alignment.BottomCenter).background(Tokens.current.surface2).padding(3.dp), color = Tokens.current.danger, style = MaterialTheme.typography.labelSmall)
                        is DraftState.Done -> Unit
                    }
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        IconButton({ if (preview === attachment) preview = null; remove(attachment) }, Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).background(Tokens.current.textPrimary, RoundedCornerShape(50))) {
                            Icon(Icons.Outlined.Close, "移除${labels[index]}", Modifier.size(14.dp), tint = Tokens.current.surface2)
                        }
                    }
                }
            }
        } }
    }
    preview?.takeIf { it in items }?.let { attachment ->
        val bitmap = attachmentBitmap(attachment, 1400)
        WorkbenchDialog(onDismissRequest = { preview = null }, title = { Text(labels[items.indexOf(attachment)] + " · " + attachment.name) },
            text = { Column {
                if (bitmap != null) Image(bitmap, "附件预览", Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit)
                else Text(if (attachment.isImage) "无法解码此图片的本地预览。" else attachment.name)
                (attachment.state as? DraftState.Failed)?.let { Text(it.msg, color = Tokens.current.danger) }
            } }, confirmButton = { TextButton({ preview = null }) { Text("关闭") } })
    }
}

@Composable
private fun attachmentBitmap(attachment: DraftAttach, maxSize: Int): ImageBitmap? {
    var bitmap by remember(attachment.stamp, maxSize) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.stamp, maxSize) {
        if (!attachment.isImage) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            val standard = runCatching {
                attachment.open().use { input -> ImageIO.createImageInputStream(input).use imageStream@ { stream ->
                    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@imageStream null
                    try {
                        reader.input = stream
                        val step = (maxOf(reader.getWidth(0), reader.getHeight(0)) / maxSize).coerceAtLeast(1)
                        val params = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                        reader.read(0, params).toComposeImageBitmap()
                    } finally { reader.dispose() }
                } }
            }.getOrNull()
            standard ?: runCatching { skiaThumbnail(attachment, maxSize) }.getOrNull()
        }
    }
    return bitmap
}

/** Skia covers WebP when ImageIO has no decoder. Both encoded input and pixel size are bounded. */
private fun skiaThumbnail(attachment: DraftAttach, maxSize: Int): ImageBitmap? {
    val cap = 32 * 1024 * 1024
    val bytes = attachment.open().use { it.readNBytes(cap + 1) }
    if (bytes.size > cap) return null
    return org.jetbrains.skia.Image.makeFromEncoded(bytes).use decoded@ { image ->
        if (image.width.toLong() * image.height > 48_000_000L) return@decoded null
        val ratio = minOf(1.0, maxSize.toDouble() / maxOf(image.width, image.height))
        val width = (image.width * ratio).toInt().coerceAtLeast(1)
        val height = (image.height * ratio).toInt().coerceAtLeast(1)
        org.jetbrains.skia.Bitmap().use resizedBitmap@ { resized ->
            resized.allocPixels(org.jetbrains.skia.ImageInfo.makeN32Premul(width, height))
            val pixels = resized.peekPixels() ?: return@resizedBitmap null
            pixels.use sampled@ {
                if (!image.scalePixels(it, org.jetbrains.skia.SamplingMode.MITCHELL, false)) return@sampled null
                // Detach the Compose bitmap from resources closed by this decoder.
                org.jetbrains.skia.Image.makeFromBitmap(resized).use { scaled ->
                    scaled.toComposeImageBitmap()
                }
            }
        }
    }
}
