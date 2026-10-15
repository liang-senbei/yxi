package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.ssh.Sftp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Code 风格文件面板的数据来源（M4d）：本机读磁盘（LocalFiles），服务器走这条 SSH 的 SFTP。[root] 是起点目录（会话的工作目录），
 * [list] 列一个目录（目录在前、按名字排，同 Sftp.list），[isDir] 问一个路径是不是目录（只在点了还没判过的符号链接时才问，同经典），
 * [read] 读文件开头最多 max 字节，[join] 拼路径，[explain] 把异常说成人话。
 */
internal class CodeFileSource(
    val root: String,
    val list: suspend (String) -> List<Sftp.Entry>,
    val isDir: suspend (path: String) -> Boolean,
    val read: suspend (path: String, max: Int) -> ByteArray,
    val join: (dir: String, name: String) -> String,
    val explain: (Throwable) -> String,
)

/** 树里看得见的一行：[depth] 是缩进层级，[dir] 是不是目录，[link] 是还没判过指向的符号链接（点的时候再问 [CodeFileSource.isDir]）。 */
internal data class FileTreeRow(val path: String, val name: String, val depth: Int, val dir: Boolean, val link: Boolean = false)

internal object FileTree {
    /**
     * 按展开状态把树拍平（M4d）：从 [root] 的子项开始，展开了的目录接着列它已读到的子项。
     * [filter] 不为空时改成平铺：已读到的所有项里名字含它的（不分大小写），名字写相对 [root] 的路径。
     */
    fun rows(root: String, children: Map<String, List<FileTreeRow>>, expanded: Set<String>, filter: String): List<FileTreeRow> {
        val out = ArrayList<FileTreeRow>()
        if (filter.isBlank()) {
            fun walk(dir: String) {
                for (row in children[dir].orEmpty()) {
                    out += row
                    if (row.dir && row.path in expanded) walk(row.path)
                }
            }
            walk(root)
        } else {
            val needle = filter.trim().lowercase()
            for (row in children.values.flatten()) if (needle in row.name.lowercase())
                out += row.copy(name = row.path.removePrefix(root).trimStart('/', '\\').ifBlank { row.name }, depth = 0)
            out.sortWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        }
        return out
    }

    /** 文本按行拆开（最多 [max] 行；Windows 换行去掉 \r）；看着像二进制（前 4 KiB 里有 NUL）就返回 null。 */
    fun lines(bytes: ByteArray, max: Int = 20_000): List<String>? {
        if (bytes.take(4096).any { it.toInt() == 0 }) return null
        return String(bytes, Charsets.UTF_8).lineSequence().take(max).map { it.removeSuffix("\r") }.toList()
            .let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    }
}

/**
 * Code 风格的文件面板（M4d，规格表 §3.12「文件」）：放在右侧卡片里。
 * 左边文件树宽 239.2、右缘细线：顶上过滤框（高 24、细线框、左右 8），行步距 24、四周内缩 4，图标 #95938D（中心距树左 15.2），
 * 名字 14 主色起于 +28.8，选中行 #E6E6E6 底；目录点了就地展开（第一次展开时才去读）。
 * 右边是选中文件的预览：头部次色文件名 + [actions]，下面一条细线；文本带行号（右对齐到距预览左 40，#6C6C6C），代码在 +50.4 起、等宽 12 / 16.8、不折行；
 * 图片照原样画；二进制和读失败给一行说明。参照端有语法着色和光标行，Yxi 没有光标、先不着色。
 */
@Composable
internal fun CodeFilesView(key: Any, source: CodeFileSource, actions: @Composable RowScope.(path: String, bytes: ByteArray?) -> Unit,
    treeActions: (@Composable RowScope.(dir: String, reload: (String) -> Unit) -> Unit)? = null) {
    val t = Tokens.current
    val scope = rememberCoroutineScope()
    val line = if (t.dark) t.border else Color(0xFFE6E6E6)
    // 状态跟着「这个会话 + 起点目录」：同一个会话 cd 到别处后重选，树要从新目录重读（交叉审查 10-15）
    @Suppress("NAME_SHADOWING") val key = remember(key, source.root) { key to source.root }
    val children = remember(key) { mutableStateMapOf<String, List<FileTreeRow>>() }
    val expanded = remember(key) { mutableStateListOf<String>() }
    var filter by remember(key) { mutableStateOf("") }
    var selected by remember(key) { mutableStateOf<String?>(null) }   // 高亮的那一行（目录、文件都行）
    var file by remember(key) { mutableStateOf<String?>(null) }       // 右边在预览的文件
    var error by remember(key) { mutableStateOf("") }
    var loading by remember(key) { mutableStateOf(true) }

    suspend fun load(dir: String, depth: Int) {
        try {
            // ⚠️ 不在列目录时逐个 stat 符号链接（服务器上 node_modules/.bin 这种目录会一条条串行地问，几十秒），同经典：点的时候再判
            children[dir] = source.list(dir).map { e -> FileTreeRow(source.join(dir, e.name), e.name, depth, e.isDir, link = e.isLink && !e.isDir) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = source.explain(e) }
    }
    LaunchedEffect(key) { loading = true; error = ""; load(source.root, 0); loading = false }
    // 选中的目录（选的是文件就取它所在的目录，没选就是起点）：给 [treeActions] 用，比如服务器的「上传到这个目录」
    fun selectedDir(): String {
        val path = selected ?: return source.root
        val (dir, rows) = children.entries.firstOrNull { (_, rows) -> rows.any { it.path == path } }?.toPair() ?: return source.root
        return if (rows.first { it.path == path }.dir) path else dir
    }
    fun reload(dir: String) = scope.launch {
        val depth = if (dir == source.root) 0 else children.values.flatten().firstOrNull { it.path == dir }?.depth?.plus(1) ?: 0
        error = ""; load(dir, depth)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(239.2.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(24.dp).border(hairline(), line, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (filter.isEmpty()) Text("筛选文件", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                    BasicTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, cursorBrush = SolidColor(t.textPrimary),
                        textStyle = TextStyle(color = t.textPrimary, fontSize = 13.sp, lineHeight = 18.sp))
                }
                if (treeActions != null) { Spacer(Modifier.width(4.dp)); treeActions(selectedDir()) { reload(it) } }
            }
            if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = t.danger, fontSize = 12.sp, lineHeight = 17.sp)
            val rows = FileTree.rows(source.root, children, expanded.toSet(), filter)
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                rows.isEmpty() && error.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (filter.isBlank()) "这个目录是空的" else "没有匹配的文件", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows.size, key = { rows[it].path }) { index ->
                        val row = rows[index]
                        val open = row.path in expanded
                        Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 4.dp)
                            .background(if (row.path == selected) line else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable {
                                selected = row.path
                                if (row.link) scope.launch {
                                    val parent = children.entries.firstOrNull { (_, rows) -> rows.any { it.path == row.path } }?.key
                                    // 问不到（断线、stat 失败）就留着它是没判过的链接，下次还能再点（交叉审查 10-15）
                                    val dir = try { source.isDir(row.path) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = source.explain(e); return@launch }
                                    if (parent != null) children[parent] = children[parent].orEmpty().map { if (it.path == row.path) it.copy(dir = dir, link = false) else it }
                                    if (!dir) file = row.path
                                    else { expanded.add(row.path); load(row.path, row.depth + 1) }
                                } else if (!row.dir) file = row.path
                                else {
                                    if (open) expanded.remove(row.path) else {
                                        expanded.add(row.path)
                                        if (row.path !in children) scope.launch { load(row.path, row.depth + 1) }
                                    }
                                }
                            }.padding(start = (3.2 + row.depth * 12).dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (!row.dir) Icons.AutoMirrored.Outlined.InsertDriveFile else if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                null, Modifier.size(16.dp), tint = Color(0xFF95938D))
                            Spacer(Modifier.width(5.6.dp))
                            Text(row.name, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Box(Modifier.width(hairline()).fillMaxHeight().background(line))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val current = file
            if (current == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("从左边选一个文件预览", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
            } else CodeFilePreview(current, source, line, actions)
        }
    }
}

@Composable
private fun CodeFilePreview(path: String, source: CodeFileSource, line: Color, actions: @Composable RowScope.(path: String, bytes: ByteArray?) -> Unit) {
    val t = Tokens.current
    val ext = path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
    val image = ext in PREVIEW_IMAGES
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var error by remember(path) { mutableStateOf("") }
    LaunchedEffect(path) {
        try { bytes = source.read(path, if (image) 8 shl 20 else 1 shl 20) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = source.explain(e) }
    }
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.8.sp)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(31.2.dp).padding(start = 12.8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(path.substringAfterLast('/').substringAfterLast('\\'), Modifier.weight(1f), color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions(path, bytes)
        }
        Box(Modifier.fillMaxWidth().height(hairline()).background(line))
        val data = bytes
        when {
            error.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error, color = t.danger, fontSize = 13.sp, lineHeight = 18.sp) }
            data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            image -> ImageBody(data)   // 同经典预览（FilesPane.kt）
            else -> {
                val lines = remember(data) { FileTree.lines(data) }
                if (lines == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这是个二进制文件，预览不了", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                } else SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.8.dp, bottom = 12.dp)) {
                        items(lines.size) { i ->
                            Row(Modifier.fillMaxWidth().height(16.8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DisableSelection {   // 复制时只带代码，不带行号（交叉审查 10-15）
                                    Text((i + 1).toString(), Modifier.width(40.dp), style = mono, color = if (t.dark) t.textMuted else Color(0xFF6C6C6C),
                                        textAlign = TextAlign.End, maxLines = 1)
                                }
                                Spacer(Modifier.width(10.4.dp))
                                Text(lines[i].ifEmpty { " " }, style = mono, color = t.textPrimary, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
