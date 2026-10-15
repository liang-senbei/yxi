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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed

/*
 * Code 风格对话区的小件（规格 §3.4）。M2 先给排队用（InstructionStrip），M3 画用户消息和消息操作行时接着用。
 */

/**
 * 用户消息气泡：#F0F0EF 底、圆角 10、左右 12 / 上下 8，单行高 36，字主色 14 / 20；
 * 最宽 627.2（参考端见到的最宽值，列宽的 ≈82%；对话宽度换档时按同一比例，见 [ChatWidth.bubbleMax]）。可点时点气泡就是 [onClick]。
 */
@Composable
internal fun CodeUserBubble(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, onClick: (() -> Unit)? = null) {
    val t = Tokens.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val lines = remember { intArrayOf(1) }   // 上次排版的行数：测量里读，不触发重组
    Box(
        modifier.widthIn(max = ChatWidth.current.bubbleMax).clip(shape).background(t.userBubble)
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
        is TranscriptItem.User -> CodeMessageWithActions(item.text, alignEnd = true) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (item.text.isNotBlank()) SelectionContainer { CodeUserBubble(item.text) }
                if (item.images.isNotEmpty()) images(item.images)
            }
        }
        is TranscriptItem.AssistantText -> CodeMessageWithActions(item.text, alignEnd = false) { AssistantBody(item.text) }
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
internal fun CodeTranscriptLine(row: TranscriptRow, images: @Composable (List<InstructionAttachment>) -> Unit = {}, onPath: ((String) -> Unit)? = null,
    view: TranscriptView = TranscriptView.Normal) {
    when (row) {
        is TranscriptRow.Single -> CodeTranscriptRow(row.item, images)
        // 详细档：工具组卡片和每个条目一开始就展开（M3g）；思考的显示与否由面板先按 Transcripts.visible 过滤
        is TranscriptRow.Tools -> CodeToolGroup(row.tools, onPath, startOpen = view == TranscriptView.Detailed)
    }
}

/** Ctrl+O（macOS 上 ⌘O 也算）：轮换对话视图（M3g，规格表 §3.4）。各 Code 对话页在输入框的按键处理里接它。 */
internal fun isTranscriptViewKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean =
    e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.O && (e.isCtrlPressed || e.isMetaPressed) && !e.isShiftPressed

/**
 * 工具摘要行 + 工具组卡片（M3c，规格表 §3.4）：一个回合里连着的几次调用合成一行（[Transcripts.summary] 的短语），弱色 14 / 20，
 * 后跟箭头（间隔 6.4；收起 ≈#8F8E88，点开换成向下的、#605F5C，字变次色）。点开后下面是撑满列宽的卡片：透明底、细线外框、圆角 8，
 * 条目之间贯通的细线；每个条目收起时高 36、字起于卡片左 +10.4，点开看详情（[CodeToolEntry]）。进行中 / 失败在短语后面写一句。
 */
@Composable
internal fun CodeToolGroup(tools: List<TranscriptItem.Tool>, onPath: ((String) -> Unit)? = null,
    expanded: Boolean? = null, onToggle: (() -> Unit)? = null, detail: (@Composable (TranscriptItem.Tool) -> Unit)? = null, startOpen: Boolean = false) {
    val t = Tokens.current
    // 开合状态默认自己记；SSH 对话页（M3e）传 [expanded] / [onToggle]，用它自己的状态（「跳到某个工具」要能展开对应的组）
    var own by remember(tools.first().key, startOpen) { mutableStateOf(startOpen) }
    val open = expanded ?: own
    fun toggle() { if (onToggle != null) onToggle() else own = !own }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val running = tools.any { it.status in setOf("pending", "in_progress", "inProgress", "running") }
    val failed = tools.any { it.status in setOf("failed", "error", "declined") }
    val strong = open || hovered
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button) { toggle() },
            verticalAlignment = Alignment.CenterVertically) {
            Text(Transcripts.summary(tools) + if (running) " · 进行中" else if (failed) " · 有失败" else "",
                // M3h-3：进行中的摘要行用主色（规格表 §3.4：最深像素到 #0B0B0B）；参照端推测的流光不做（PRD §14.1，不要循环动效）
                color = if (running) t.textPrimary else if (strong) t.textSecondary else t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                    CodeToolEntry(tool, onPath, detail, startOpen)
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
private fun CodeToolEntry(tool: TranscriptItem.Tool, onPath: ((String) -> Unit)?, detail: (@Composable (TranscriptItem.Tool) -> Unit)? = null, startOpen: Boolean = false) {
    val t = Tokens.current
    var open by remember(tool.key, startOpen) { mutableStateOf(startOpen) }
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
        // [detail] 给了就用调用方自己的详情（SSH 对话页用富渲染的 ToolCard：结构化 diff、分开的 stdout / stderr 等）
        if (open && detail != null) Box(Modifier.fillMaxWidth().padding(start = 9.6.dp, end = 8.8.dp, bottom = 11.2.dp)) { detail(tool) }
        else if (open) Column(Modifier.fillMaxWidth().padding(start = 9.6.dp, end = 8.8.dp, bottom = 11.2.dp)) {
            val line = hairline()
            if (tool.kind == ToolKind.Command && tool.command.isNotBlank()) {
                Row(Modifier.fillMaxWidth().border(line, t.border, RoundedCornerShape(8.dp)).background(t.surface2, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("$ ", style = mono, color = t.textSecondary)
                    SelectionContainer { Text(tool.command, style = mono, color = t.textPrimary) }
                }
                val output = clipLines(tool.detail)
                if (output.isNotBlank()) { Spacer(Modifier.height(13.6.dp)); SelectionContainer { Text(output, style = mono, color = t.textSecondary) } }
            } else if (tool.kind == ToolKind.Read && Transcripts.numberedLines(tool.detail).isNotEmpty()) {
                // M3h：读文件条目（规格表 §3.4）：路径行（次色）+ 带行号的内容框（行号列 #616161，代码主色，等宽、行步距 17）
                tool.paths.firstOrNull()?.let { path ->
                    Text(path, Modifier.then(if (onPath != null) Modifier.clickable { onPath(path) } else Modifier), color = t.textSecondary,
                        fontSize = 12.8.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.8.dp))
                }
                CodeNumberedBox(Transcripts.numberedLines(tool.detail))
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

/**
 * 消息下面的操作行（M3d-3，规格表 §3.4）：在消息下方 8、高 24；鼠标停在消息上才显示，不显示时位置照留，所以消息之间的距离不随它变
 * （下面再留 4，加上列表的间距 10，上一条消息底到下一条顶 = 8 + 24 + 4 + 10 = 46，规格 ≈46～48）。
 * 只放 Yxi 真能做的「复制」：你的消息靠右（同参照端的位置），助手的回复靠左。参照端的重试、分叉、置顶和相对时间 Yxi 没有对应的功能或数据，不放。
 */
@Composable
internal fun CodeMessageWithActions(copyText: String, alignEnd: Boolean, onEdit: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().hoverable(source)) {
        content()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically) {
            if (hovered && copyText.isNotBlank()) CodeCopyAction(copyText)
            // 「编辑」（SSH 对话页的回退改写，M3e）：同一行、在复制后面
            if (hovered && onEdit != null) CodeIconButton(Icons.Outlined.Edit, "编辑消息", iconSize = 12.8.dp, tint = Tokens.current.textMuted, tipAbove = true, onClick = onEdit)
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 操作行里的「复制」：24×24 图标按钮（图标 12.8，弱色；hover 底 #F0F0EF、字形变深，同规格表 §3.4），点了写剪贴板，1.5 秒内显示成对勾。 */
@Composable
private fun CodeCopyAction(text: String) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { kotlinx.coroutines.delay(1500); done = false } }
    CodeIconButton(if (done) Icons.Filled.Check else Icons.Outlined.ContentCopy, if (done) "已复制" else "复制", iconSize = 12.8.dp, tint = Tokens.current.textMuted, tipAbove = true) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }.onSuccess { done = true }
    }
}

/** 状态行用的时钟（M3f）：生产是系统时间、每秒刷新一次；截图夹具换成固定时间、不刷新（画面要连续几帧一样才算稳定）。 */
internal class StatusClock(val now: () -> Long, val tick: Boolean)
internal val LocalStatusClock = staticCompositionLocalOf { StatusClock({ System.currentTimeMillis() }, tick = true) }

/** 本轮从什么时候开始跑（M3f）：[running] 变成 true 那一刻记下，变回 false 清零；放在面板这一层记，列表滚动时状态行重画也不会从 0 算起。 */
@Composable
internal fun rememberRunningSince(running: Boolean): Long {
    val clock = LocalStatusClock.current
    return remember(running) { if (running) clock.now() else 0L }
}

/**
 * 运行中的状态行（M3f，规格表 §3.4）：在对话末尾、上一条消息的操作行下面；12 的图标（x 列左 +4）+ 次色字（列左 +34.4），形如「正在运行 · 9s」。
 * 参照端是循环动画的品牌图标，Yxi 用静态图标（PRD §2-5）；秒数是真实计时，每秒刷新。
 * 思考行（M3h-4，规格表 §3.4）：[thinkingSince] > 0（对话最后一项是思考）时换成「9s · 思考中…」，秒数从这段思考开始算；
 * 参照端中间还有「18 tokens」，各运行器都不给思考的 token 数，不写。
 */
@Composable
internal fun CodeStatusLine(label: String, since: Long, thinkingSince: Long = 0L) {
    val t = Tokens.current
    val clock = LocalStatusClock.current
    var now by remember(since) { mutableStateOf(clock.now()) }
    if (clock.tick) LaunchedEffect(since) { while (true) { kotlinx.coroutines.delay(1000); now = clock.now() } }
    fun seconds(from: Long) = if (from <= 0L) 0L else ((now - from) / 1000).coerceAtLeast(0L)
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.Autorenew, null, Modifier.size(12.dp), tint = t.textMuted)
        Spacer(Modifier.width(18.4.dp))
        Text(if (thinkingSince > 0L) "${seconds(thinkingSince)}s · 思考中…" else "$label · ${seconds(since)}s", color = t.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
    }
}

/** 带行号的文件内容框（M3h，规格表 §3.4「读文件条目展开」）：同命令框的面（卡片底、细线边、圆角 8），左边行号列（#616161，右对齐到最宽的号），
 *  代码在号后 17.6 起、等宽、行步距 17；内容可选中（只选代码，不带行号）。 */
@Composable
internal fun CodeNumberedBox(lines: List<Pair<Int, String>>) {
    val t = Tokens.current
    val mono = CodeStyle.copy(fontSize = 12.sp, lineHeight = 17.sp)
    val numberColor = if (t.dark) t.textMuted else Color(0xFF616161)
    val digits = lines.maxOfOrNull { it.first.toString().length } ?: 1
    Row(Modifier.fillMaxWidth().border(hairline(), t.border, RoundedCornerShape(8.dp)).background(t.surface2, RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.End) { lines.forEach { (n, _) -> Text(n.toString().padStart(digits), style = mono, color = numberColor, maxLines = 1) } }
        Spacer(Modifier.width(17.6.dp))
        SelectionContainer { Column { lines.forEach { (_, code) -> Text(code.ifEmpty { " " }, style = mono, color = t.textPrimary, maxLines = 1, softWrap = false) } } }
    }
}

