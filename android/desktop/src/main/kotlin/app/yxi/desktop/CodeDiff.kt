package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** unified diff 的一行（M4c）：[old] / [new] 是旧 / 新文件里的行号（只有对应的一侧有）。 */
internal data class DiffLine(val kind: Kind, val old: Int?, val new: Int?, val text: String) {
    enum class Kind { Meta, Hunk, Context, Add, Del, Note }
}

internal object CodeDiff {
    private val hunk = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*$""")
    /** 合并冲突的组合 diff（`diff --cc`）：`@@@ -a -b +c @@@`，每行前面有「父提交数」个标记列。 */
    private val combinedHunk = Regex("""^(@@@+) .* @@@+.*$""")

    /**
     * 把 `git diff` 的输出拆成行（M4c）：第一个 @@ 之前和每段 `diff --git` 起的文件头是 Meta；@@ 行是 Hunk，从它读出旧 / 新的起始行号；
     * 段内 + / − / 空格开头的分别是新增 / 删除 / 上下文，按行号往下数；`\ No newline at end of file` 是 Note。最多 [max] 行。
     */
    const val MAX_LINES = 6000
    /** [diff] 的行数超过 [MAX_LINES]（[parse] 截掉了后面的）。 */
    fun clipped(diff: String): Boolean = diff.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.size - 1 else it.size } > MAX_LINES

    fun parse(diff: String, max: Int = MAX_LINES): List<DiffLine> {
        var old = 0; var new = 0; var inHunk = false; var marks = 0   // marks > 0：在组合 diff 的段里（不写行号，按标记列着色）
        val lines = diff.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        return lines.asSequence().take(max).map { line ->
            val head = hunk.matchEntire(line)
            val combined = if (head == null) combinedHunk.matchEntire(line) else null
            when {
                head != null -> { inHunk = true; marks = 0; old = head.groupValues[1].toInt(); new = head.groupValues[2].toInt(); DiffLine(DiffLine.Kind.Hunk, null, null, line) }
                combined != null -> { inHunk = true; marks = combined.groupValues[1].length - 1; DiffLine(DiffLine.Kind.Hunk, null, null, line) }
                line.startsWith("diff --git ") || line.startsWith("diff --cc ") || line.startsWith("diff --combined ") -> { inHunk = false; marks = 0; DiffLine(DiffLine.Kind.Meta, null, null, line) }
                !inHunk -> DiffLine(DiffLine.Kind.Meta, null, null, line)
                marks > 0 -> {
                    val col = line.take(marks)
                    val kind = when { line.startsWith("\\") -> DiffLine.Kind.Note; '+' in col -> DiffLine.Kind.Add; '-' in col -> DiffLine.Kind.Del; else -> DiffLine.Kind.Context }
                    DiffLine(kind, null, null, if (kind == DiffLine.Kind.Note) line else line.drop(marks))
                }
                line.startsWith("+") -> DiffLine(DiffLine.Kind.Add, null, new++, line.substring(1))
                line.startsWith("-") -> DiffLine(DiffLine.Kind.Del, old++, null, line.substring(1))
                line.startsWith("\\") -> DiffLine(DiffLine.Kind.Note, null, null, line)
                else -> DiffLine(DiffLine.Kind.Context, old++, new++, line.removePrefix(" "))
            }
        }.toList()
    }
}

/** diff 某一行上的评论（M4j）：[file] / [staged] 是哪个文件、哪个范围，[old] / [new] 是那一行的旧 / 新行号，[code] 是那一行的内容。 */
internal data class DiffComment(val file: String, val staged: Boolean, val old: Int?, val new: Int?, val code: String, val text: String) {
    val key: String get() = "$staged\u0000$file\u0000$old\u0000$new"

    companion object {
        fun keyOf(file: String, staged: Boolean, line: DiffLine) = "$staged\u0000$file\u0000${line.old}\u0000${line.new}"

        /**
         * 一次提交的所有评论拼成一段放进对话草稿的引用（M4j，参照端「逐行评论、Ctrl+Enter 一次提交」）：
         * 头上是来历（同「针对这份差异提要求」：来源、仓库、分支、提交），按文件、行号排，每条是「文件 第 N 行」+ 那一行的代码（引用）+ 评论。
         */
        fun quote(sourceLine: String, root: String, branch: String, head: String, comments: List<DiffComment>): String = buildString {
            append("Git 差异逐行评论\n").append(sourceLine).append("\n仓库：").append(root)
            append("\n分支：").append(branch.ifBlank { "分离 HEAD" }).append("\n提交：").append(head.ifBlank { "尚无提交" })
            for (c in comments.sortedWith(compareBy({ it.file }, { it.staged }, { it.new ?: it.old ?: 0 }))) {
                val where = if (c.new != null) "第 ${c.new} 行" else "删除的第 ${c.old} 行"
                append("\n\n").append(c.file).append(if (c.staged) "（已暂存）" else "").append(" ").append(where).append("：\n> ").append(c.code)
                append("\n").append(c.text.trim())
            }
        }
    }
}

/** 改动面板 / 审批卡的 diff 色（规格表 §1.5，浅 / 深两套）。 */
private class DiffColors(val delRow: Color, val delGutter: Color, val addRow: Color, val addGutter: Color, val delNumber: Color, val addNumber: Color, val bar: (DiffLine.Kind) -> Color)

@Composable
private fun diffColors(): DiffColors {
    val t = Tokens.current
    return if (t.dark) DiffColors(Color(0xFF442424), Color(0xFF392221), Color(0xFF263B24), Color(0xFF233221), Color(0xFFB92944), Color(0xFF31D54A)) {
        if (it == DiffLine.Kind.Add) Color(0xFF1E9E3C) else Color(0xFFCD2054)
    } else DiffColors(Color(0xFFFDE7E9), Color(0xFFFEEDEE), Color(0xFFE7F4E7), Color(0xFFEDF6ED), Color(0xFFCD2054), Color(0xFF1E9E3C)) {
        if (it == DiffLine.Kind.Add) Color(0xFF1E9E3C) else Color(0xFFCD2054)
    }
}

/**
 * diff 的一行（M4c，规格表 §3.12 / §1.5）：高 16.8；行首 2.4 宽的色条（增 / 删才有）、行号区 35.2（右对齐，增删行有自己的底和字色，
 * 删除行写旧行号、其余写新行号）、再空 8 是等宽 12 的代码，不折行。@@ 行弱色、浅填充底；Note 弱色。Meta 不画（调用方先滤掉）。
 */
@Composable
internal fun CodeDiffRow(line: DiffLine, commented: Boolean = false, onGutter: (() -> Unit)? = null) {
    val t = Tokens.current
    val p = CodePalette.current
    val c = diffColors()
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.8.sp)
    val row = when (line.kind) { DiffLine.Kind.Add -> c.addRow; DiffLine.Kind.Del -> c.delRow; DiffLine.Kind.Hunk -> p.menuHover; else -> Color.Transparent }
    val gutter = when (line.kind) { DiffLine.Kind.Add -> c.addGutter; DiffLine.Kind.Del -> c.delGutter; else -> Color.Transparent }
    Row(Modifier.fillMaxWidth().height(16.8.dp).background(row), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.4.dp).fillMaxHeight().background(if (line.kind == DiffLine.Kind.Add || line.kind == DiffLine.Kind.Del) c.bar(line.kind) else Color.Transparent))
        Box(Modifier.width(35.2.dp).fillMaxHeight().background(gutter).then(if (onGutter != null) Modifier.clickable(onClick = onGutter) else Modifier)
            .padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
            if (commented) Box(Modifier.align(Alignment.CenterStart).padding(start = 3.dp).size(5.dp).background(p.focusBlue, androidx.compose.foundation.shape.CircleShape))
            val number = if (line.kind == DiffLine.Kind.Del) line.old else line.new
            if (number != null) Text(number.toString(), style = mono.copy(fontSize = 11.sp), textAlign = TextAlign.End, maxLines = 1,
                color = when (line.kind) { DiffLine.Kind.Add -> c.addNumber; DiffLine.Kind.Del -> c.delNumber; else -> t.textMuted })
        }
        Spacer(Modifier.width(8.dp))
        Text(line.text.ifEmpty { " " }, style = mono, maxLines = 1, softWrap = false,
            color = if (line.kind == DiffLine.Kind.Hunk || line.kind == DiffLine.Kind.Note) t.textMuted else t.textPrimary)
    }
}

/**
 * Code 风格的改动面板（M4c，规格表 §3.12）：放在右侧卡片里（卡片头部写「改动」）。
 * 第一行「分支 → 工作区 / 已暂存 ∨」（次色 13，点开选范围）+ 刷新；下面一行弱色的仓库路径和 HEAD。
 * 每个文件一行：#F2F2F2 底（深色用浅填充）、高 32、次色字；点开就在下面画这个文件的 diff（[CodeDiffRow]），再点收起；
 * 点开的那行右端有 +N / −N 和「针对这份差异提要求」「打开文件」两个图标按钮。没有文件时面板中间一行弱色「没有可显示的改动」。
 * 数据、读取、修改意见对话框都是 [GitChangesView] 的（两种风格共用），这里只管外观。
 */
@Composable
internal fun CodeGitChanges(snapshot: JSONObject?, staged: Boolean, file: String, busy: Boolean, error: String, files: (JSONObject) -> List<JSONObject>, diffReady: Boolean,
    comments: List<DiffComment>, onComment: (key: String, comment: DiffComment?) -> Unit, onSubmitComments: () -> Unit,
    onStaged: (Boolean) -> Unit, onFile: (String) -> Unit, onRefresh: () -> Unit, openFile: (JSONObject) -> Unit, ask: (JSONObject, String) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val rowFill = if (t.dark) p.menuHover else Color(0xFFF2F2F2)
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.8.sp)
    var scopeMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }   // 正在写评论的那一行（DiffComment.keyOf）
    NativeOverlay(scopeMenu)
    val list = snapshot?.let(files).orEmpty()
    // 换了文件、新快照还没回来时，快照里的 diff 还是上一个文件的：不画（交叉审查 10-15），那一行下面写「正在读取」
    val diff = if (diffReady) snapshot?.optString("diff").orEmpty() else ""
    val lines = remember(diff) { CodeDiff.parse(diff).filter { it.kind != DiffLine.Kind.Meta } }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 12.8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(Modifier.clickable { scopeMenu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${snapshot?.optString("branch")?.ifBlank { "分离 HEAD" } ?: "…"} → ${if (staged) "已暂存" else "工作区"}",
                            color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = t.textSecondary)
                    }
                    if (scopeMenu) CodePopup({ scopeMenu = false }, width = 200.dp) {
                        CodeMenuItem("工作区（未暂存）", { scopeMenu = false; onStaged(false) }, checked = !staged)
                        CodeMenuItem("已暂存", { scopeMenu = false; onStaged(true) }, checked = staged)
                    }
                }
                Spacer(Modifier.weight(1f))
                CodeIconButton(Icons.Outlined.Refresh, "刷新", enabled = !busy, onClick = onRefresh)
            }
            snapshot?.let { data ->
                Text(data.getString("root") + " · " + data.optString("head").take(12).ifBlank { "尚无提交" }, Modifier.padding(horizontal = 12.8.dp).padding(bottom = 6.dp),
                    color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (comments.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${comments.size} 条评论（${comments.map { it.file }.distinct().size} 个文件）", Modifier.weight(1f), color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                CodeApprovalButton(CodeApprovalAction(MacChrome.label("加入对话 · Ctrl+Enter"), primary = true, onClick = onSubmitComments))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 12.8.dp, vertical = 6.dp), color = t.danger, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (snapshot != null && list.isEmpty()) item(key = "empty") {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("没有可显示的改动", color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        for (entry in list) {
            val path = entry.getString("path")
            val open = path == file
            item(key = "file:$path") {
                Row(Modifier.fillMaxWidth().height(32.dp).background(rowFill).clickable { onFile(if (open) "" else path) }.padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = t.textMuted)
                    Spacer(Modifier.width(4.8.dp))
                    Text(entry.getString("code").trim(), style = mono, color = t.textMuted, modifier = Modifier.width(22.dp), maxLines = 1)
                    Text(path, Modifier.weight(1f), color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (open && snapshot != null) {
                        val added = lines.count { it.kind == DiffLine.Kind.Add }
                        val removed = lines.count { it.kind == DiffLine.Kind.Del }
                        if (added + removed > 0) {
                            Text("+$added", color = t.diffAdd, fontSize = 12.sp, lineHeight = 17.sp)
                            Spacer(Modifier.width(4.dp))
                            Text("−$removed", color = t.diffDel, fontSize = 12.sp, lineHeight = 17.sp)
                            Spacer(Modifier.width(6.dp))
                        }
                        CodeIconButton(Icons.Outlined.RateReview, "针对这份差异提要求", enabled = !busy && diff.isNotBlank()) { ask(snapshot, diff) }
                        CodeIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "打开文件") { openFile(snapshot) }
                    }
                }
            }
            if (open && snapshot != null && !diffReady) item(key = "loading") {
                Text("正在读取差异…", Modifier.padding(horizontal = 12.8.dp, vertical = 8.dp), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            else if (open && snapshot != null) {
                if (snapshot.optBoolean("clipped")) item(key = "clipped") {
                    Text("差异较长，只显示前 256 KiB", Modifier.padding(horizontal = 12.8.dp, vertical = 4.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (diff.isBlank()) item(key = "nodiff") {
                    Text("当前范围无差异", Modifier.padding(horizontal = 12.8.dp, vertical = 8.dp), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                // 没有 @@ 段的（未跟踪文件的说明、Binary files differ、只改了权限）：原文照画，不是空着的「+0 −0」（交叉审查 10-15）
                else if (lines.isEmpty()) item(key = "raw") {
                    Text(diff.trim(), Modifier.padding(horizontal = 12.8.dp, vertical = 8.dp), style = mono, color = t.textSecondary)
                }
                else {
                    for ((i, line) in lines.withIndex()) {
                        val numbered = line.old != null || line.new != null
                        val key = DiffComment.keyOf(file, staged, line)
                        val saved = if (numbered) comments.firstOrNull { it.key == key } else null
                        item(key = "line:$i") {
                            CodeDiffRow(line, commented = saved != null, onGutter = if (!numbered) null else ({ editing = if (editing == key) null else key }))
                        }
                        if (numbered && (editing == key || saved != null)) item(key = "comment:$i") {
                            CodeDiffCommentBox(saved?.text.orEmpty(), editing == key,
                                onEdit = { editing = key },
                                onSave = { text -> onComment(key, if (text.isBlank()) null else DiffComment(file, staged, line.old, line.new, line.text, text)); editing = null },
                                onCancel = { editing = null },
                                onSubmitAll = { text -> if (text.isNotBlank()) onComment(key, DiffComment(file, staged, line.old, line.new, line.text, text)); editing = null; onSubmitComments() })
                        }
                    }
                    if (CodeDiff.clipped(diff)) item(key = "cap") {
                        Text("差异太长，只画了前 ${CodeDiff.MAX_LINES} 行", Modifier.padding(horizontal = 12.8.dp, vertical = 6.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

/**
 * diff 行下面的评论（M4j）：写的时候是一个细线框的多行输入（回车换行，Ctrl+Enter 存下并把所有评论加入对话，Esc 取消），
 * 下面「取消」「保存」；存下的评论画成浅底的小块（次色字），点它再改，清空保存就删掉。左边和代码对齐（行号区 2.4 + 35.2 + 8）。
 */
@Composable
private fun CodeDiffCommentBox(text: String, editing: Boolean, onEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onSubmitAll: (String) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth().padding(start = 45.6.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)) {
        if (!editing) Text(text, Modifier.fillMaxWidth().background(p.menuHover, shape).clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 6.dp),
            color = t.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        else {
            var draft by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text)) }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            if (LocalStatusClock.current.tick) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().border(hairline(), p.popupRing, shape).background(p.popup, shape).padding(8.dp)) {
                Box {
                    if (draft.text.isEmpty()) Text("对这一行的意见…", color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    androidx.compose.foundation.text.BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 36.dp).focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                            else if (e.key == androidx.compose.ui.input.key.Key.Enter && MacChrome.primary(e)) { onSubmitAll(draft.text); true }
                            else if (e.key == androidx.compose.ui.input.key.Key.Escape) { onCancel(); true }
                            else false
                        }, textStyle = androidx.compose.ui.text.TextStyle(color = t.textPrimary, fontSize = 13.sp, lineHeight = 18.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(t.textPrimary))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End)) {
                    CodeApprovalButton(CodeApprovalAction("取消", onClick = onCancel))
                    CodeApprovalButton(CodeApprovalAction(if (text.isNotEmpty() && draft.text.isBlank()) "删除" else "保存", primary = true, onClick = { onSave(draft.text) }))
                }
            }
        }
    }
}
