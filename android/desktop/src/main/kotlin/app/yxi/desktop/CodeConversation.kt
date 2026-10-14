package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * Code 风格对话区的小件（规格 §3.4）。M2 先给排队用（InstructionStrip），M3 画用户消息和消息操作行时接着用。
 */

/**
 * 用户消息气泡：#F0F0EF 底、圆角 10、左右 12 / 上下 8，单行高 36，字主色 14 / 20；
 * 最宽 627.2（参考端见到的最宽值，列宽的 ≈82%）。可点时点气泡就是 [onClick]。
 */
@Composable
internal fun CodeUserBubble(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, onClick: (() -> Unit)? = null) {
    val t = Tokens.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val lines = remember { intArrayOf(1) }   // 上次排版的行数：测量里读，不触发重组
    Box(
        modifier.widthIn(max = 627.2.dp).clip(shape).background(t.userBubble)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Skia 排中文（回退字体）每行比行高多 ≈0.6px（125% 下 25.6 而不是 25），逐行累加；参考端（Chromium）每行正好 20。
        // 报给气泡的高度取「行数 × 行高」，多出的零点几 px 落进下内边距，字形位置不变
        Text(text, Modifier.layout { m, c ->
            val p = m.measure(c)
            layout(p.width, (lines[0] * 20.sp.toPx()).roundToInt().coerceIn(c.minHeight, c.maxHeight)) { p.place(0, 0) }
        }, color = t.userBubbleText, fontSize = 14.sp, lineHeight = 20.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { lines[0] = it.lineCount })
    }
}
/**
 * 消息操作行里的文字动作（排队的「发送」「调整方向」，参考端的「立即发送」）：高 24、左右 6.4、字 12 / 16；
 * 右对齐放时字的右缘正好在列右内 6.4（§3.4）。平时弱色，hover 铺浅底；不可用时用操作行里不可用图标的灰。
 */
@Composable
internal fun CodeTextAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.height(24.dp).clip(RoundedCornerShape(5.dp)).background(if (enabled && hovered) p.iconHover else Color.Transparent)
            .hoverable(source).clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) t.textMuted else p.actionDisabled, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}

/** 操作行里的状态字（不可点，如「本轮结束后自动执行」、参考端的相对时间）：和 [CodeTextAction] 同字号、同留白，放不下时截断。 */
@Composable
internal fun CodeActionNote(text: String, modifier: Modifier = Modifier, color: Color = Tokens.current.textMuted) {
    Text(text, modifier.padding(horizontal = 6.4.dp), color = color, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Code 风格的一项对话（M3b，PRD §5.5 / 规格表 §3.4），只认 [TranscriptItem]：
 * - 你的消息：右对齐的 [CodeUserBubble]（字可选中复制），随消息发的图片画在气泡下面（[images] 槽，各运行器自己画）；
 * - 助手正文：没有作者名的 Markdown（参照端就是这样）；
 * - 思考：弱色斜体 14 / 20，没有标题和框；
 * - 工具和认不出的项交给 [other]，默认是 [CodeToolFallback]（M3c 换成摘要行和工具组卡片之前的过渡外观）。
 */
@Composable
internal fun CodeTranscriptRow(item: TranscriptItem, images: @Composable (List<InstructionAttachment>) -> Unit = {},
    other: @Composable (TranscriptItem) -> Unit = { CodeToolFallback(it) }) {
    val t = Tokens.current
    when (item) {
        is TranscriptItem.User -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (item.text.isNotBlank()) SelectionContainer { CodeUserBubble(item.text) }
            if (item.images.isNotEmpty()) images(item.images)
        }
        is TranscriptItem.AssistantText -> AssistantBody(item.text)
        is TranscriptItem.Thinking -> SelectionContainer {
            Text(item.text, color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic)
        }
        else -> other(item)
    }
}

/** 工具 / 认不出的项的过渡外观（M3c 之前）：一行弱色标题（带状态），下面是可选中的原文（等宽小字）。 */
@Composable
internal fun CodeToolFallback(item: TranscriptItem) {
    val t = Tokens.current
    Column(Modifier.fillMaxWidth()) {
        when (item) {
            is TranscriptItem.Tool -> {
                Text(item.title + if (item.status.isBlank()) "" else " · " + toolStatusName(item.status), color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.detail.isNotBlank()) SelectionContainer { Text(item.detail, style = CodeStyle, color = t.textSecondary, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            }
            is TranscriptItem.Notice -> SelectionContainer { Text(item.text, style = CodeStyle, color = t.textSecondary, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            else -> {}
        }
    }
}

/** 运行器给的工具状态换成中文（ACP：pending / in_progress / completed / failed；Codex：inProgress / completed / failed / declined；OpenCode：pending / running / completed / error）；认不出的原样给。 */
internal fun toolStatusName(status: String) = when (status) {
    "pending" -> "等待中"; "in_progress", "inProgress", "running" -> "进行中"; "completed" -> "已完成"
    "failed", "error" -> "失败"; "declined" -> "已拒绝"; else -> status
}

/** 对话里的一行（M3c）：单独一项走 [CodeTranscriptRow]，连着的工具调用走 [CodeToolGroup]。[onPath] 给文件改动里的路径用（本机复制路径，服务器打开文件）。 */
@Composable
internal fun CodeTranscriptLine(row: TranscriptRow, images: @Composable (List<InstructionAttachment>) -> Unit = {}, onPath: ((String) -> Unit)? = null) {
    when (row) {
        is TranscriptRow.Single -> CodeTranscriptRow(row.item, images)
        is TranscriptRow.Tools -> CodeToolGroup(row.tools, onPath)
    }
}

/**
 * 工具摘要行 + 工具组卡片（M3c，规格表 §3.4）：一个回合里连着的几次调用合成一行（[Transcripts.summary] 的短语），弱色 14 / 20，
 * 后跟箭头（间隔 6.4；收起 ≈#8F8E88，点开换成向下的、#605F5C，字变次色）。点开后下面是撑满列宽的卡片：透明底、细线外框、圆角 8，
 * 条目之间贯通的细线；每个条目收起时高 36、字起于卡片左 +10.4，点开看详情（[CodeToolEntry]）。进行中 / 失败在短语后面写一句。
 */
@Composable
internal fun CodeToolGroup(tools: List<TranscriptItem.Tool>, onPath: ((String) -> Unit)? = null) {
    val t = Tokens.current
    var open by remember(tools.first().key) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val running = tools.any { it.status in setOf("pending", "in_progress", "inProgress", "running") }
    val failed = tools.any { it.status in setOf("failed", "error", "declined") }
    val strong = open || hovered
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically) {
            Text(Transcripts.summary(tools) + if (running) " · 进行中" else if (failed) " · 有失败" else "",
                color = if (strong) t.textSecondary else t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.4.dp))
            CodeToolArrow(open, strong)
        }
        if (open) {
            Spacer(Modifier.height(8.8.dp))   // 卡片顶在摘要行基线下 13.6：行高 20、基线 ≈15，所以行底下 ≈8.8
            val line = hairline()
            Column(Modifier.fillMaxWidth().border(line, t.border, RoundedCornerShape(8.dp))) {
                tools.forEachIndexed { index, tool ->
                    if (index > 0) HorizontalDivider(thickness = line, color = t.border)
                    CodeToolEntry(tool, onPath)
                }
            }
        }
    }
}

/** 摘要行和条目后面的箭头：收起「>」、点开「∨」；颜色照规格表（浅色 #8F8E88 / #605F5C，深色用弱色 / 次色）。 */
@Composable
private fun CodeToolArrow(open: Boolean, strong: Boolean) {
    val t = Tokens.current
    val color = if (t.dark) (if (strong) t.textSecondary else t.textMuted) else if (open || strong) Color(0xFF605F5C) else Color(0xFF8F8E88)
    Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, if (open) "收起" else "展开", Modifier.size(14.dp), tint = color)
}

/**
 * 工具组卡片里的一个条目（规格表 §3.4）：标题一行（读文件 / 改文件 = 弱色动词 + 主色文件名；命令 = 命令本身；其他 = 工具标题），
 * 点开：命令是命令框（底同卡片面、细线边、圆角 8、行首「$」次色、等宽）+ 下面的输出（等宽次色 12 / 17，没有框）；其他是装在框里的原文。
 * 文件改动的路径可点（[onPath]）。详情最多画 80 行，长的写明省略了多少。
 */
@Composable
private fun CodeToolEntry(tool: TranscriptItem.Tool, onPath: ((String) -> Unit)?) {
    val t = Tokens.current
    var open by remember(tool.key) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 17.sp)
    val names = tool.paths.map { it.substringAfterLast('/').substringAfterLast('\\') }.filter { it.isNotBlank() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open }
            .padding(start = 10.4.dp, end = 10.4.dp), verticalAlignment = Alignment.CenterVertically) {
            val verb = when (tool.kind) { ToolKind.Read -> "读取"; ToolKind.Edit -> "修改"; else -> null }
            if (verb != null && names.isNotEmpty()) {
                Text(verb + " ", color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
                Text(names.joinToString("、"), color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            } else Text(if (tool.kind == ToolKind.Command && tool.command.isNotBlank()) tool.command else tool.title,
                color = if (open || hovered) t.textSecondary else t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.4.dp))
            CodeToolArrow(open, open || hovered)
            Spacer(Modifier.weight(1f))
            if (tool.status.isNotBlank() && tool.status != "completed") Text(toolStatusName(tool.status), color = t.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 9.6.dp, end = 8.8.dp, bottom = 11.2.dp)) {
            val line = hairline()
            if (tool.kind == ToolKind.Command && tool.command.isNotBlank()) {
                Row(Modifier.fillMaxWidth().border(line, t.border, RoundedCornerShape(8.dp)).background(t.surface2, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("$ ", style = mono, color = t.textSecondary)
                    SelectionContainer { Text(tool.command, style = mono, color = t.textPrimary) }
                }
                val output = clipLines(tool.detail)
                if (output.isNotBlank()) { Spacer(Modifier.height(13.6.dp)); SelectionContainer { Text(output, style = mono, color = t.textSecondary) } }
            } else {
                if (onPath != null && tool.paths.isNotEmpty()) tool.paths.forEach { path ->
                    Text(path, Modifier.clickable { onPath(path) }.padding(vertical = 2.dp), style = mono, color = t.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val body = clipLines(tool.detail)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(8.8.dp))
                    Box(Modifier.fillMaxWidth().border(line, t.border, RoundedCornerShape(8.dp)).background(t.surface2, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        SelectionContainer { Text(body, style = mono, color = t.textPrimary) }
                    }
                }
            }
        }
    }
}

/** 详情最多 80 行，多出来的写一句（工具输出可能很长，整段排版会卡）。 */
private fun clipLines(text: String, max: Int = 80): String {
    val lines = text.trimEnd().lines()
    return if (lines.size <= max) lines.joinToString("\n") else lines.take(max).joinToString("\n") + "\n…（还有 ${lines.size - max} 行未显示）"
}

