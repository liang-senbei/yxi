package app.yxi.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Paths
import app.yxi.ssh.Sftp
import app.yxi.ui.theme.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val TEXTISH = setOf(
    "md", "txt", "json", "kt", "java", "py", "js", "ts", "tsx", "jsx", "sh", "bash", "zsh",
    "yml", "yaml", "toml", "ini", "conf", "cfg", "xml", "html", "css", "sql", "go", "rs",
    "c", "h", "cpp", "hpp", "rb", "php", "gradle", "kts", "properties", "env", "log", "csv",
)

/**
 * 看一个远端文件。**只读。**
 *
 * markdown 默认走**阅读模式**，可以切到源码 —— 手机上看 README 就该是排好版的，
 * 但改动之前你总想看一眼原文（PRD 附录 G）。
 */
@Composable
fun FileViewer(sftp: Sftp?, path: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ext = Paths.extOf(path)
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var source by remember(path) { mutableStateOf(false) }   // md 的「源码」开关
    var truncated by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path, sftp) {
        val s = sftp ?: return@LaunchedEffect
        val limit = if (ext in IMAGES) 8 shl 20 else 1 shl 20
        runCatching { s.read(path, limit) }
            .onSuccess { bytes = it; truncated = it.size >= limit }
            .onFailure { error = Sftp.explain(it) }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = SurfaceContainer, shape = Pill, modifier = Modifier.clickable(onClick = onBack)) {
                Text("←", Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(Paths.nameOf(path), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    error ?: bytes?.let { human(it.size.toLong()) + if (truncated) " · 已截断" else "" } ?: "读取中…",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    color = if (error != null) MaterialTheme.colorScheme.error else Dim,
                )
            }
            if (ext == "md" && bytes != null) {
                Surface(
                    color = if (source) SurfaceContainerHigh else SurfaceContainer, shape = Pill,
                    modifier = Modifier.clickable { source = !source },
                ) {
                    Text(
                        if (source) "源码" else "阅读",
                        Modifier.padding(14.dp, 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (source) Copper else Muted,
                    )
                }
            }
        }

        val b = bytes ?: return@Column
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ext in IMAGES -> ImageBody(b)
                ext == "md" && !source -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp, 4.dp, 18.dp, 28.dp)) {
                    Markdown(
                        b.decodeToString(),
                        modifier = Modifier.fillMaxWidth(),   // 库的默认是 fillMaxSize()，会把滚动撑坏
                        imageTransformer = remember(sftp, path) { SftpImages(sftp, Paths.dirOf(path)) },
                    )
                }
                ext == "json" -> JsonBody(b.decodeToString())
                ext in TEXTISH || looksTextual(b) -> CodeBody(b.decodeToString(), ext)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("二进制文件，不显示", style = MaterialTheme.typography.bodyMedium, color = Dim)
                }
            }
        }
    }
}

/** 没有扩展名的文件（Makefile、Dockerfile、脚本）也该能看 —— 抽样看有没有 NUL 字节。 */
private fun looksTextual(b: ByteArray): Boolean =
    b.take(4000).none { it == 0.toByte() }

@Composable
private fun ImageBody(b: ByteArray) {
    val bmp = remember(b) { runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这张图解不开", style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
        return
    }
    Image(
        bmp.asImageBitmap(), null,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        contentScale = ContentScale.FillWidth,
    )
}

@Composable
private fun CodeBody(text: String, ext: String) {
    // 横向也要能滚：代码折行读起来很痛苦
    Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 28.dp)) {
        Text(
            Highlight.of(text, ext),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            color = OnSurfaceVariant,
        )
    }
}

/**
 * 让 markdown 里的图片走 SFTP 读回来。
 *
 * ⚠️ **`link` 是 markdown 源码里原样的那个字符串**（库不做任何 URL 拼接），
 * 所以相对路径要自己按**这份 md 所在的目录**解析 —— 解析歪了界面上只表现为「图裂了」，
 * 看不出是路径错还是文件真没有。[Paths.resolve] 有测试盯着（`PathsTest.相对路径解析`）。
 *
 * ⚠️ http(s) 链接直接放弃：这条路上根本没有网络，只有一条 SSH 连接。
 */
private class SftpImages(private val sftp: Sftp?, private val baseDir: String) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        if (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("data:")) return null
        val painter by androidx.compose.runtime.produceState<Painter?>(null, link, sftp) {
            val s = sftp ?: return@produceState
            value = runCatching { s.read(Paths.resolve(baseDir, link), 8 shl 20) }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?.asImageBitmap()
                ?.let(::BitmapPainter)
        }
        return painter?.let { ImageData(painter = it, contentScale = ContentScale.FillWidth) }
    }
}
