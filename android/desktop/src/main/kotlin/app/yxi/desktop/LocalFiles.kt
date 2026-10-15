package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Sftp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 本机文件（本机对齐 P6b）：只读地浏览、预览本机目录，外观同服务器的 FilesPane（行、预览共用 [FileRow] / [FilePreviewView]）。
 * 和服务器的差别：没有上传（本机文件本来就在这台电脑上）；预览里「另存为」换成「复制路径」；面包屑按本机路径拼（Windows 的盘符也行）。
 * 读目录、读文件都在 IO 线程上；目录最多列 [MAX_ENTRIES] 项，预览文本最多 1 MB、图片 8 MB（同服务器）。
 */
internal object LocalFiles {
    const val MAX_ENTRIES = 3000

    /** 列一个目录：目录在前、再按名字排（同 Sftp.list），符号链接按指向判断是不是目录；读不了的项跳过。 */
    fun list(directory: File): List<Sftp.Entry> {
        val children = directory.listFiles() ?: error(if (!directory.exists()) "目录不存在：${directory.path}" else "没有权限读取：${directory.path}")
        return children.asSequence().take(MAX_ENTRIES).mapNotNull { file ->
            runCatching {
                val link = Files.isSymbolicLink(file.toPath())
                val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val dir = if (link) file.isDirectory else attributes.isDirectory
                Sftp.Entry(file.name, dir, if (dir) 0L else (if (link) file.length() else attributes.size()),
                    (attributes.lastModifiedTime().toMillis() / 1000).toInt(), link)
            }.getOrNull()
        }.sortedWith(compareBy<Sftp.Entry>({ !it.isDir }, { it.name.lowercase() }, { it.name })).toList()
    }

    /** 读文件开头最多 [max] 字节（大文件只看头上一段，同服务器预览）。 */
    fun read(file: File, max: Int): ByteArray {
        require(file.isFile) { "不是文件：${file.path}" }
        return file.inputStream().use { it.readNBytes(max) }
    }

    /** 面包屑：从根到 [path] 的每一级（名字 + 完整路径）；根显示成它自己（/、C:\）。 */
    fun crumbs(path: File): List<Pair<String, String>> =
        generateSequence(path.absoluteFile) { it.parentFile }.toList().reversed().map { (it.name.ifBlank { it.path }) to it.path }

    fun explain(e: Throwable): String = when (e) {
        is java.nio.file.AccessDeniedException, is SecurityException -> "没有权限：" + e.message.orEmpty()
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}

@Composable
internal fun LocalFilesPane(taskKey: String, directory: String) {
    // M4d：Code 风格画成规格表 §3.12 的文件面板（左树右预览，CodeFiles.kt）；经典照旧
    if (LocalThemeSpec.current.style == UiStyle.Code) { CodeLocalFiles(taskKey, directory); return }
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    var path by remember(taskKey) { mutableStateOf(File(directory.ifBlank { System.getProperty("user.home") }).absoluteFile) }
    var entries by remember(taskKey) { mutableStateOf<List<Sftp.Entry>>(emptyList()) }
    var busy by remember(taskKey) { mutableStateOf(true) }
    var note by remember(taskKey) { mutableStateOf("") }
    var preview by remember(taskKey) { mutableStateOf<File?>(null) }
    var copied by remember(taskKey) { mutableStateOf("") }

    fun load(to: File) = scope.launch {
        busy = true; note = ""
        try {
            val real = withContext(Dispatchers.IO) { to.canonicalFile }
            entries = withContext(Dispatchers.IO) { LocalFiles.list(real) }
            path = real
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { note = LocalFiles.explain(e) }
        busy = false
    }
    LaunchedEffect(taskKey) { load(path) }
    LaunchedEffect(copied) { if (copied.isNotBlank()) { kotlinx.coroutines.delay(3000); copied = "" } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(t.surface0)) {
            // 面包屑 + 上一级 + 刷新（同服务器；没有上传）
            Row(Modifier.fillMaxWidth().padding(10.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ path.parentFile?.let { load(it) } }, Modifier.size(28.dp), enabled = path.parentFile != null) {
                    Icon(Icons.Default.ArrowUpward, "上一级", Modifier.size(16.dp), tint = t.textSecondary)
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    LocalFiles.crumbs(path).forEachIndexed { index, (label, full) ->
                        Text(label, Modifier.clip(RoundedCornerShape(6.dp)).clickable { load(File(full)) }.padding(6.dp, 3.dp),
                            style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 1)
                        if (index > 0 || !label.endsWith(File.separator)) Text(File.separator, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                }
                IconButton({ load(path) }, Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, "刷新", Modifier.size(16.dp), tint = t.textSecondary)
                }
            }
            if (copied.isNotBlank()) Text("已复制路径：$copied", Modifier.padding(14.dp, 0.dp, 14.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            if (note.isNotBlank()) Text(note, Modifier.padding(14.dp, 0.dp, 14.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = t.danger)
            HorizontalDivider(color = t.border)
            Box(Modifier.fillMaxSize()) {
                when {
                    busy && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (note.isBlank()) "这个目录是空的" else "", style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.name }) { e ->
                            FileRow(e) { val full = File(path, e.name); if (e.isDir) load(full) else preview = full }
                        }
                    }
                }
            }
        }
        preview?.let { file ->
            FilePreviewView(file.path, file.name, file.extension.lowercase(), { max -> withContext(Dispatchers.IO) { LocalFiles.read(file, max) } }, LocalFiles::explain,
                actions = {
                    TextButton({
                        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(file.path), null) }
                            .onSuccess { copied = file.path }
                    }) { Text(if (copied == file.path) "已复制" else "复制路径") }
                }, onClose = { preview = null })
        }
    }
}

/** 本机文件的 Code 外观（M4d）：数据同经典（LocalFiles 只读列目录、读开头），预览头部的动作是「复制路径」（复制后 3 秒内显示对勾）。 */
@Composable
private fun CodeLocalFiles(taskKey: String, directory: String) {
    var copied by remember(taskKey) { mutableStateOf("") }
    LaunchedEffect(copied) { if (copied.isNotBlank()) { kotlinx.coroutines.delay(3000); copied = "" } }
    val root = remember(directory) { File(directory.ifBlank { System.getProperty("user.home") }).absoluteFile.path }
    val source = remember(root) {
        CodeFileSource(root,
            list = { dir -> withContext(Dispatchers.IO) { LocalFiles.list(File(dir)) } },
            isDir = { path -> withContext(Dispatchers.IO) { File(path).isDirectory } },
            read = { path, max -> withContext(Dispatchers.IO) { LocalFiles.read(File(path), max) } },
            join = { dir, name -> File(dir, name).path },
            explain = LocalFiles::explain)
    }
    CodeFilesView(taskKey, source, actions = { path, _ ->
        CodeIconButton(if (copied == path) Icons.Filled.Check else Icons.Outlined.ContentCopy, if (copied == path) "已复制路径" else "复制路径") {
            runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(path), null) }
                .onSuccess { copied = path }
        }
    })
}
