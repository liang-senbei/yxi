package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import app.yxi.ssh.Paths
import app.yxi.ssh.Sftp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区的「文件」模式 —— 照手机的 `FilesScreen` / `FileViewer`：浏览、预览、上传、下载。
 *
 * ⚠️ **走的是这条会话已经连着的那条 SSH**（[Conn.ssh] 开一条 SFTP 通道），不另开连接：
 * 用户在这台机器上已经认证过了，再弹一次认证既多余又容易失败。
 *
 * ⚠️ **起点是会话自己的 cwd**，不是家目录：人点进「文件」十有八九是想看 agent 正在改的那些东西。
 */
@Composable
fun FilesPane(conn: Conn, sess: Session, onOpen: ((String) -> Unit)? = null) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()

    // 一条 SFTP 通道跟着这个连接走；换主机就换一条，离开这一屏就关掉（通道不关会一直占着服务器的 fd）
    var sftp by remember(conn) { mutableStateOf<Sftp?>(null) }
    var path by remember(conn, sess.name) { mutableStateOf(sess.cwd.ifBlank { "." }) }
    var entries by remember(conn) { mutableStateOf<List<Sftp.Entry>>(emptyList()) }
    var busy by remember(conn) { mutableStateOf(true) }
    var note by remember(conn) { mutableStateOf("") }
    var preview by remember(conn) { mutableStateOf<String?>(null) }
    var transfer by remember(conn) { mutableStateOf("") }

    DisposableEffect(conn) {
        onDispose { runCatching { sftp?.close() }; sftp = null }
    }

    // 列目录。⚠️ 失败要说人话：SFTP 的错码（Permission denied / No such file）直接摆出来最有用
    // ⚠️ 写成「启动一个协程」而不是 suspend：调用点都在 Composable 里（面包屑、按钮），
    //    suspend 函数在那儿调不了，而 rememberCoroutineScope 的作用域正好跟着这一屏走。
    fun load(to: String) = scope.launch {
        busy = true; note = ""
        runCatching {
            val s = sftp ?: conn.ssh.openSftp().also { sftp = it }
            val real = s.realpath(to)
            entries = s.list(real)
            path = real
        }.onFailure { note = Sftp.explain(it) }
        busy = false
    }

    LaunchedEffect(conn, sess.name) { load(path) }

    // ⚠️ **预览要盖在列表上，得有个 Box 收着。** 原来 `preview?.let { … }` 跟 Column 平级发射，
    //    父布局（App 里那个 fillMaxSize 的 Column）把它排到了已经占满高度的列表**下面** ——
    //    点文件只看见行高亮了一下，预览在屏幕外，看起来像「点了没反应」。
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(t.surface0)) {
        // 面包屑 + 上一级 + 刷新 + 上传
        Row(
            Modifier.fillMaxWidth().padding(10.dp, 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ load(Paths.dirOf(path)) }, Modifier.size(28.dp)) {
                Icon(Icons.Default.ArrowUpward, "上一级", Modifier.size(16.dp), tint = t.textSecondary)
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Paths.crumbs(path).forEach { (label, full) ->
                    Text(
                        label,
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { load(full) }.padding(6.dp, 3.dp),
                        style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 1,
                    )
                    Text("/", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
            }
            IconButton({ load(path) }, Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, "刷新", Modifier.size(16.dp), tint = t.textSecondary)
            }
            IconButton({
                pickLocalFile()?.let { f ->
                    scope.launch {
                        transfer = "正在上传 ${f.name}…"
                        val to = if (path.endsWith("/")) path + f.name else "$path/${f.name}"
                        runCatching {
                            val s = sftp ?: conn.ssh.openSftp().also { sftp = it }
                            // ⚠️ 流式，不 readBytes：分享一段视频进来原来就是在这儿 OOM 的（手机端 Sftp.write 那条注释）
                            // ⚠️ progress 要**具名传**：write 的最后一个参数是 `resume: Boolean`，
                            //    写成尾随 lambda 会绑到它身上（编译器报的是「期望 Boolean」，很难一眼看出是这个原因）。
                            s.write(to, f.inputStream(), f.length(), progress = { done, total ->
                                transfer = "正在上传 ${f.name}… " + (if (total > 0) "${done * 100 / total}%" else human(done))
                                true
                            })
                        }.onFailure { note = "上传失败：" + Sftp.explain(it) }
                        transfer = ""
                        load(path)
                    }
                }
            }, Modifier.size(28.dp)) {
                Icon(Icons.Default.Upload, "上传到这个目录", Modifier.size(16.dp), tint = t.textSecondary)
            }
        }
        if (transfer.isNotBlank()) Text(transfer, Modifier.padding(14.dp, 0.dp, 14.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        if (note.isNotBlank()) Text(note, Modifier.padding(14.dp, 0.dp, 14.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = t.danger)
        HorizontalDivider(color = t.border)

        Box(Modifier.fillMaxSize()) {
            if (busy && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这个目录是空的", style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.name }) { e ->
                        FileRow(e) {
                            scope.launch {
                                val full = if (path.endsWith("/")) path + e.name else "$path/${e.name}"
                                // ⚠️ 链接没解引用过（大目录只解前几个，见 Sftp.list），点的时候才判
                                val isDir = e.isDir || (e.isLink && runCatching { sftp?.isDir(full) == true }.getOrDefault(false))
                                if (isDir) load(full) else if (onOpen != null) onOpen(full) else preview = full
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { p -> FilePreview(conn, p, sftp) { preview = null } }
    }
}

@Composable
private fun FileRow(e: Sftp.Entry, onOpen: () -> Unit) {
    val t = Tokens.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(14.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (e.isDir) Icons.Default.Folder else Icons.Default.Description,
            null, Modifier.size(16.dp), tint = if (e.isDir) t.accent else t.textMuted,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            e.name + if (e.isLink) " →" else "",
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!e.isDir) Text(human(e.size), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Spacer(Modifier.width(12.dp))
        Text(stamp(e.mtime), Modifier.width(104.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    }
}

/** 图片扩展名 —— 跟手机端 [app.yxi.ui] 那份保持一致 */
private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/**
 * 预览。文本直接读出来显示，图片解码成位图。
 *
 * ⚠️ **有上限**：文本 1MB、图片 8MB（跟手机端同一套数）。没有上限的话，点中一个 2GB 的日志
 * 就是把它整个拉进内存 —— 而用户只是想看一眼头几行。
 */
@Composable
private fun FilePreview(conn: Conn, path: String, sftp: Sftp?, onClose: () -> Unit) {
    val t = Tokens.current
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var err by remember(path) { mutableStateOf("") }
    val ext = Paths.extOf(path)

    LaunchedEffect(path) {
        runCatching {
            val s = sftp ?: conn.ssh.openSftp()
            s.read(path, max = if (ext in IMAGES) 8 shl 20 else 1 shl 20)
        }.onSuccess { bytes = it }.onFailure { err = Sftp.explain(it) }
    }

    Column(Modifier.fillMaxSize().background(t.surface1)) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Paths.nameOf(path), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = t.textPrimary, maxLines = 1)
            TextButton({
                pickSaveTo(Paths.nameOf(path))?.let { out ->
                    bytes?.let { runCatching { out.writeBytes(it) } }
                }
            }) { Text("另存为") }
            TextButton(onClose) { Text("关闭") }
        }
        HorizontalDivider(color = t.border)
        when {
            err.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(err, color = t.danger, style = MaterialTheme.typography.bodyMedium)
            }
            bytes == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            ext in IMAGES -> ImageBody(bytes!!)
            else -> Text(
                // ⚠️ 二进制文件不能直接当文本画：满屏乱码还可能卡住排版。先看一眼有没有 NUL
                if (bytes!!.take(4096).any { it.toInt() == 0 }) "这是个二进制文件，预览不了。用「另存为」拿下来看。"
                else String(bytes!!),
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = t.textPrimary,
            )
        }
    }
}

@Composable
private fun ImageBody(bytes: ByteArray) {
    val img = remember(bytes) {
        runCatching { androidx.compose.ui.res.loadImageBitmap(java.io.ByteArrayInputStream(bytes)) }.getOrNull()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (img == null) Text("这张图解不开", color = Tokens.current.textMuted)
        else androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize().padding(12.dp))
    }
}

/** 系统文件选择框。⚠️ 用 AWT 的 FileDialog 不是 Swing 的 JFileChooser —— 前者在 Windows 上是原生框。 */
private fun pickLocalFile(): java.io.File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "选一个文件上传", java.awt.FileDialog.LOAD)
    d.isVisible = true
    val dir = d.directory ?: return null
    val f = d.file ?: return null
    return java.io.File(dir, f).takeIf { it.isFile }
}

private fun pickSaveTo(name: String): java.io.File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "保存到", java.awt.FileDialog.SAVE)
    d.file = name
    d.isVisible = true
    val dir = d.directory ?: return null
    val f = d.file ?: return null
    return java.io.File(dir, f)
}

private fun human(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n.toDouble() / (1L shl 30))
    n >= 1L shl 20 -> "%.1f MB".format(n.toDouble() / (1L shl 20))
    n >= 1024 -> "%.0f KB".format(n.toDouble() / 1024)
    else -> "$n B"
}

private fun stamp(sec: Int): String =
    if (sec <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000L))
