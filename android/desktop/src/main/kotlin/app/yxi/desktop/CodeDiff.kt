package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    /**
     * 把 `git diff` 的输出拆成行（M4c）：第一个 @@ 之前和每段 `diff --git` 起的文件头是 Meta；@@ 行是 Hunk，从它读出旧 / 新的起始行号；
     * 段内 + / − / 空格开头的分别是新增 / 删除 / 上下文，按行号往下数；`\ No newline at end of file` 是 Note。最多 [max] 行。
     */
    fun parse(diff: String, max: Int = 6000): List<DiffLine> {
        var old = 0; var new = 0; var inHunk = false
        val lines = diff.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        return lines.asSequence().take(max).map { line ->
            val head = hunk.matchEntire(line)
            when {
                head != null -> { inHunk = true; old = head.groupValues[1].toInt(); new = head.groupValues[2].toInt(); DiffLine(DiffLine.Kind.Hunk, null, null, line) }
                line.startsWith("diff --git ") -> { inHunk = false; DiffLine(DiffLine.Kind.Meta, null, null, line) }
                !inHunk -> DiffLine(DiffLine.Kind.Meta, null, null, line)
                line.startsWith("+") -> DiffLine(DiffLine.Kind.Add, null, new++, line.substring(1))
                line.startsWith("-") -> DiffLine(DiffLine.Kind.Del, old++, null, line.substring(1))
                line.startsWith("\\") -> DiffLine(DiffLine.Kind.Note, null, null, line)
                else -> DiffLine(DiffLine.Kind.Context, old++, new++, line.removePrefix(" "))
            }
        }.toList()
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
internal fun CodeDiffRow(line: DiffLine) {
    val t = Tokens.current
    val p = CodePalette.current
    val c = diffColors()
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.8.sp)
    val row = when (line.kind) { DiffLine.Kind.Add -> c.addRow; DiffLine.Kind.Del -> c.delRow; DiffLine.Kind.Hunk -> p.menuHover; else -> Color.Transparent }
    val gutter = when (line.kind) { DiffLine.Kind.Add -> c.addGutter; DiffLine.Kind.Del -> c.delGutter; else -> Color.Transparent }
    Row(Modifier.fillMaxWidth().height(16.8.dp).background(row), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.4.dp).fillMaxHeight().background(if (line.kind == DiffLine.Kind.Add || line.kind == DiffLine.Kind.Del) c.bar(line.kind) else Color.Transparent))
        Box(Modifier.width(35.2.dp).fillMaxHeight().background(gutter).padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
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
internal fun CodeGitChanges(snapshot: JSONObject?, staged: Boolean, file: String, busy: Boolean, error: String, files: (JSONObject) -> List<JSONObject>,
    onStaged: (Boolean) -> Unit, onFile: (String) -> Unit, onRefresh: () -> Unit, openFile: (JSONObject) -> Unit, ask: (JSONObject, String) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val rowFill = if (t.dark) p.menuHover else Color(0xFFF2F2F2)
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.8.sp)
    var scopeMenu by remember { mutableStateOf(false) }
    NativeOverlay(scopeMenu)
    val list = snapshot?.let(files).orEmpty()
    val diff = snapshot?.optString("diff").orEmpty()
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
                        if (diff.isNotBlank()) {
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
            if (open && snapshot != null) {
                if (snapshot.optBoolean("clipped")) item(key = "clipped") {
                    Text("差异较长，只显示前 256 KiB", Modifier.padding(horizontal = 12.8.dp, vertical = 4.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (diff.isBlank()) item(key = "nodiff") {
                    Text("当前范围无差异", Modifier.padding(horizontal = 12.8.dp, vertical = 8.dp), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                else items(lines.size, key = { "line:$it" }) { CodeDiffRow(lines[it]) }
            }
        }
    }
}
