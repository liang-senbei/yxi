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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** 有两副面孔的：排好版的「阅读」和原文「源码」。 */
private val RENDERABLE = setOf("md", "html", "htm")
private val TEXTISH = setOf(
    "md", "txt", "json", "kt", "java", "py", "js", "ts", "tsx", "jsx", "sh", "bash", "zsh",
    "yml", "yaml", "toml", "ini", "conf", "cfg", "xml", "html", "htm", "css", "sql", "go", "rs",
    "c", "h", "cpp", "hpp", "rb", "php", "gradle", "kts", "properties", "env", "log", "csv",
)

/**
 * 看一个远端文件。**只读。**
 *
 * markdown 和 html 默认走**阅读模式**，可以切到源码 —— 手机上看 README 就该是排好版的，
 * 但改动之前你总想看一眼原文（PRD 附录 G）。html 的「阅读」= 真的按 CSS 渲染出来。
 */
@Composable
fun FileViewer(sftp: Sftp?, path: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewerScope = androidx.compose.runtime.rememberCoroutineScope()
    val ext = Paths.extOf(path)
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var source by remember(path) { mutableStateOf(false) }   // [RENDERABLE] 的「源码」开关
    var truncated by remember(path) { mutableStateOf(false) }
    var dling by remember(path) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, sftp) {
        val s = sftp ?: return@LaunchedEffect
        val limit = if (ext in IMAGES) 8 shl 20 else 1 shl 20
        app.yxi.ssh.catching { s.read(path, limit) }
            .onSuccess { bytes = it; truncated = it.size >= limit }
            .onFailure { error = Sftp.explain(it) }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = SurfaceContainer, shape = Pill, modifier = Modifier.clip(Pill).clickable(onClick = onBack)) {
                Text("←", Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(Paths.nameOf(path), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    error ?: bytes?.let { human(it.size.toLong()) + if (truncated) t(" · 已截断") else "" } ?: t("读取中…"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    color = if (error != null) MaterialTheme.colorScheme.error else Dim,
                )
            }
            // 下载**整个原文件**到手机（不是那份有上限的预览）：图/视频进相册，csv/xlsx/pdf 等进「下载」目录。
            // 从对话里点文件路径就能到这个查看器，所以这一颗按钮 = 直接在对话里把文件抓到手机。
            Surface(
                color = SurfaceContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(enabled = sftp != null && !dling) {
                    dling = true
                    scope.launch {
                        val name = Paths.nameOf(path)
                        val mime = MediaSaver.mimeOf(name, "")
                        val msg = runCatching {
                            val s = sftp ?: error(t("没连上"))
                            val f = java.io.File(ctx.cacheDir, "dl/$name")
                            withContext(Dispatchers.IO) {
                                f.parentFile?.mkdirs()
                                s.download(path, f)
                                MediaSaver.save(ctx, name, mime, f)
                            }
                            runCatching { f.delete() }
                            if (mime.startsWith("image/") || mime.startsWith("video/")) t("已存到相册") else t("已存到下载目录")
                        }.getOrElse { (it.message ?: t("下载失败")).take(30) }
                        dling = false
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text(
                    if (dling) t("下载中…") else t("下载"),
                    Modifier.padding(14.dp, 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = Copper,
                )
            }
            // APK 多一颗「装上」—— 你在服务器上编的包，一步装到手机里试。
            //
            // ⚠️ **必须下到 `cacheDir/update/`**：`res/xml/file_paths.xml` 只开放了这一个目录，
            // FileProvider 拿不到别处的 URI，安装器会收到一个它读不了的 content:// 然后失败。
            // ⚠️ 装这一步复用自更新那套 [install] —— 权限没给时先送设置页那条分支也一起继承，
            // 再抄一份必定漏掉其中一条。
            if (Paths.nameOf(path).endsWith(".apk", ignoreCase = true)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = Pill,
                    modifier = Modifier.clip(Pill).clickable(enabled = sftp != null && !dling) {
                        dling = true
                        scope.launch {
                            val msg = runCatching {
                                val s = sftp ?: error(t("没连上"))
                                val f = java.io.File(ctx.cacheDir, "update/${Paths.nameOf(path)}")
                                withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); s.download(path, f) }
                                install(ctx, f) ?: t("安装器拉起来了")
                            }.getOrElse { (it.message ?: t("装不上")).take(40) }
                            dling = false
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text(
                        if (dling) t("下载中…") else t("装上"),
                        Modifier.padding(14.dp, 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (ext in RENDERABLE && bytes != null) {
                Surface(
                    color = if (source) SurfaceContainerHigh else SurfaceContainer, shape = Pill,
                    modifier = Modifier.clip(Pill).clickable { source = !source },
                ) {
                    Text(
                        if (source) t("源码") else t("阅读"),
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
                    // ⚠️ HTML 写的 <img> 渲染器不认，整段被当成 HTML 块吞掉 —— 先转成 Markdown 图（#208）
                    val md = remember(b) { app.yxi.agent.MarkdownFix.apply(b.decodeToString()) }
                    Markdown(
                        md,
                        typography = yxiMarkdown(),           // 默认标题 57sp，文档在手机上同样不能这么排
                        modifier = Modifier.fillMaxWidth(),   // 库的默认是 fillMaxSize()，会把滚动撑坏
                        imageTransformer = remember(sftp, path) { SftpImages(sftp, Paths.dirOf(path), viewerScope) },
                    )
                }
                (ext == "html" || ext == "htm") && !source -> HtmlBody(b.decodeToString())
                ext == "json" -> JsonBody(b.decodeToString())
                ext in TEXTISH || looksTextual(b) -> CodeBody(b.decodeToString(), ext)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("二进制文件，不显示"), style = MaterialTheme.typography.bodyMedium, color = Dim)
                }
            }
        }
    }
}

/** 没有扩展名的文件（Makefile、Dockerfile、脚本）也该能看 —— 抽样看有没有 NUL 字节。 */
private fun looksTextual(b: ByteArray): Boolean =
    b.take(4000).none { it == 0.toByte() }

/** ⚠️ `internal` 不是 `private`：聊天里的附件预览([ChatScreen])也用这一份，别再抄一遍。 */
@Composable
internal fun ImageBody(b: ByteArray) {
    val bmp = remember(b) { runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(t("这张图解不开"), style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
        return
    }
    Image(
        bmp.asImageBitmap(), null,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        contentScale = ContentScale.FillWidth,
    )
}

/**
 * 按 CSS 渲染的 html。用系统自带的 WebView —— 手机上本来就有一个浏览器引擎，
 * 没必要自己实现排版。
 *
 * ⚠️ **JS 关着，而且不能开。** 这是从服务器上拉回来的任意文件，
 * 在 WebView 里跑它的脚本 = 让远端文件在 app 的进程里执行。
 * `javaScriptEnabled` 默认就是 false，这里再显式写一次是怕以后有人「顺手」打开。
 * 同理 `allowFileAccess` / `allowContentAccess` 都按死 —— 否则页面能读手机本地文件。
 *
 * ⚠️ **baseUrl 传 null**：这样页面落在一个不透明源上，既加载不了外链，
 * 也没有同源可言。代价是**外部的 `<link rel=stylesheet>` / `<img src>` 不会加载**
 * —— 这条路上根本没有网络，只有一条 SSH 连接（跟 [SftpImages] 那条注释同一个道理）。
 * 内联的 `<style>` 完全正常，Claude 生成的那种单文件 html 就是内联的。
 * ponytail: 真要外链，照 [SftpImages] 的样子加个 shouldInterceptRequest 走 SFTP 取。
 */
@Composable
private fun HtmlBody(html: String) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true      // 手机上看桌面宽度的页面，得能捏
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.WHITE)  // 网页自己多半假设白底
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        modifier = Modifier.fillMaxSize(),
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
private class SftpImages(
    private val sftp: Sftp?, private val baseDir: String,
    /** ⚠️ 必须是 FileViewer 那一层的 scope。加载不能挂在 transform 自己的组合上（见下） */
    private val scope: kotlinx.coroutines.CoroutineScope,
) : ImageTransformer {
    // ⚠️⚠️ **不能在 transform 里 produceState。** 渲染器在组合期间反复重调 transform，每次都是新的
    // 组合作用域 —— 上一版就是这么写的：日志里图片明明读回来了（35KB，600×243），紧接着
    // 「The coroutine scope left the composition」，状态随组合一起被丢掉，再来一遍，永远显示不出来
    // （模拟器里逮到的，#212）。所以缓存放在这个对象上：读一次，组合爱怎么重建都无所谓；
    // 用 mutableStateMap 是为了读完能触发重组把图画出来。
    private val done = androidx.compose.runtime.mutableStateMapOf<String, Painter>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Composable
    override fun transform(link: String): ImageData? {
        if (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("data:")) return null
        done[link]?.let { return ImageData(painter = it, contentScale = ContentScale.FillWidth) }
        if (link in failed || !inflight.add(link)) return null
        val s = sftp ?: return null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val path = Paths.resolve(baseDir, link)
            val bmp = runCatching { s.read(path, 8 shl 20) }
                .onFailure { android.util.Log.w("YxiMd", "img $link -> $path: ${it.message}") }
                .getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bmp != null) done[link] = BitmapPainter(bmp.asImageBitmap()) else failed += link
                inflight -= link
            }
        }
        return null
    }
}
