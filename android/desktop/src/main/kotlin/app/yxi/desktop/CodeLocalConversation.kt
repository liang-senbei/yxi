package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 本机对话（Claude / ACP / OpenCode / 本机 Codex）的 Code 外观部件：会话头、输入区和页脚菜单，量值同 SSH 对话页（PRD §5.1）。
 * 只管外观，发送 / 停止 / 切换设置的逻辑留在各面板；消息、工具行、审批卡仍是现有组件（M3 统一）。
 */

/**
 * 本机会话头：高 48、start 17.6 / end 14；笔记本图标 16 + 标题 14/20（目录放 tooltip），运行器名在连接状态的位置、弱色。
 * 服务器上的 ACP / OpenCode 对话也用它（[host] 给主机名）：图标换成服务器、描述为主机名，同 SSH 会话头，差别只在环境标签（PRD G3）；不显示主机名文字。
 * 有意偏差：不放「返回」（侧栏列着这些对话，「新会话」和 Ctrl+N 已覆盖；服务器对话也一样）；不加标题菜单（本机记录没有改名 / 置顶 / 归档，
 * 经典本机也没有逐行菜单；服务器对话的改名 / 置顶在侧栏行的 ⋮ 里）。
 * 本机和 ACP / OpenCode 不传 [end]，右侧不放图标；服务器上的 Codex 对话传 [end]，放改动、网页预览、更多三个图标（同 SSH 会话头：
 * 左段收进占满的 Row、图标间距 5.6；「文件」在更多里）。不传时布局和原来逐像素相同。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CodeLocalSessionHeader(title: String, directory: String, runner: String, host: String? = null, end: (@Composable RowScope.() -> Unit)? = null) {
    val t = Tokens.current
    val start: @Composable RowScope.() -> Unit = {
        Icon(if (host == null) Icons.Outlined.Laptop else Icons.Outlined.Dns, host ?: "本机", Modifier.size(16.dp), tint = t.textSecondary)
        Spacer(Modifier.width(7.2.dp))
        TooltipArea(
            tooltip = { CodeTooltip(directory) },
            modifier = Modifier.weight(1f, fill = false),
            delayMillis = 500,
            tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.BottomStart, Alignment.BottomEnd, DpOffset(0.dp, 5.dp)),
        ) {
            Text(title, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(runner, Modifier.padding(start = 12.dp), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 17.6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (end == null) start()
        else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { start() }
            Row(horizontalArrangement = Arrangement.spacedBy(5.6.dp), verticalAlignment = Alignment.CenterVertically) { end() }
        }
    }
}

/**
 * 本机对话的输入区：输入卡 + 28 高页脚，量值同 [CodeChatComposer]。和 SSH 对话页不同：运行中右端是停止方块（本机有中断动作）；
 * 页脚没有上下文用量环（本机对话拿不到用量）。页脚左右两段由面板给：[start] 在「+」的位置，[end] 靠右。
 * 服务器上的 Codex 对话也用它：附件走 SFTP，托盘显示上传状态（[showTransferStatus]），输入 @ 可以引用附件（[mentions]）。
 * [slashCommands] 不为空时（本机 Claude、ACP）草稿打「/」弹斜杠菜单（M4k），按键先归菜单；为空时和原来一样。
 */
@Composable
internal fun CodeLocalComposer(
    draft: TextFieldValue, onDraft: (TextFieldValue) -> Unit, hint: String, focus: FocusRequester,
    canSend: Boolean, onSend: () -> Unit, running: Boolean, onStop: () -> Unit,
    attachments: List<DraftAttach> = emptyList(), onRemoveAttachment: (DraftAttach) -> Unit = {},
    onKey: (KeyEvent) -> Boolean = { false },
    showTransferStatus: Boolean = false, mentions: AttachmentMentions? = null,
    slashCommands: List<SlashCommand> = emptyList(),
    start: @Composable RowScope.() -> Unit = {}, end: @Composable RowScope.() -> Unit = {},
) {
    var inputFocused by remember { mutableStateOf(false) }
    val slash = rememberSlashMenu(slashCommands, draft, onDraft, active = inputFocused)
    CodeInputArea(focus, Modifier.fillMaxWidth()) {
        CodeComposerCard(draft, onDraft, hint, focus, canSend, onSend, Modifier.fillMaxWidth(), running = running, onStop = onStop,
            onKey = { e -> slash.handle(e) || onKey(e) }, onFocus = { inputFocused = it }) {
            CodeSlashMenu(slash, focus)
            DraftAttachmentTray(attachments, onRemoveAttachment, showTransferStatus = showTransferStatus)
            mentions?.let { AttachmentMentionList(it) }
        }
        Spacer(Modifier.height(2.4.dp))
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            start()
            Spacer(Modifier.weight(1f))
            end()
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 本机只能加图片：「+」直接开选择框，不开浮层。框和图标的尺寸同 SSH 输入区的「+」（24 / 19.2）。 */
@Composable
internal fun CodeLocalAttachButton(enabled: Boolean, capturing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CodeIconButton(Icons.Filled.Add, if (capturing) "正在读取图片…" else MacChrome.label("添加图片（最多 4 张，截图可直接 Ctrl+V）"), modifier,
        iconSize = 19.2.dp, enabled = enabled, tint = CodePalette.current.cardPlus, tipAbove = true, onClick = onClick)
}

/**
 * 页脚右侧的模型 + 思考强度：两个 chip 开同一个 300 宽浮层（右缘对齐、向上展开，同 SSH 对话页）。语义照经典：
 * 模型点了立即切换；强度先选再点「应用思考强度」。[enabled] 为 false 时（运行中、待审批、未连接）模型 chip、模型项和「应用」置灰，
 * 强度 chip 仍能点开看（经典的滑块一直能拖）。
 */
@Composable
internal fun CodeLocalModelMenu(
    taskKey: String, model: String, models: List<String>, effort: String?, levels: List<String>,
    enabled: Boolean, onModel: (String) -> Unit, onEffort: (String) -> Unit,
) {
    var open by remember(taskKey) { mutableStateOf(false) }
    var fromEffort by remember(taskKey) { mutableStateOf(false) }
    var chosen by remember(taskKey, model, effort, levels) { mutableStateOf(effort) }
    NativeOverlay(open)
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (models.isNotEmpty()) CodeFooterChip(model.ifBlank { "选择模型" }, { fromEffort = false; open = true }, held = open && !fromEffort, enabled = enabled)
            else if (model.isNotBlank()) CodeFooterNote(model)
            if (levels.isNotEmpty()) CodeFooterChip(effort?.let(::effortName) ?: "思考强度", { fromEffort = true; open = true }, held = open && fromEffort)
        }
        if (open) CodePopup({ open = false }, side = PopupSide.Above, alignEnd = true, gap = 2.4.dp, width = 300.dp) {
            if (models.isNotEmpty()) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                models.forEach { m -> CodeMenuItem(m, { open = false; onModel(m) }, enabled = enabled, checked = m == model) }
            }
            if (models.isNotEmpty() && levels.isNotEmpty()) CodeMenuDivider()
            if (levels.isNotEmpty()) {
                Box(Modifier.padding(start = 8.8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) { EffortControl(model, levels, chosen) { chosen = it } }
                CodeMenuItem("应用思考强度", { open = false; chosen?.let(onEffort) }, enabled = enabled && chosen != null && chosen != effort)
            }
        }
    }
}

/**
 * 页脚的单选（ACP 的模式 / 模型 / 其他配置项）：chip 只写当前值，点开向上展开的浮层，顶部弱色一行写配置名，点选项立即切换（语义同经典的下拉，没有「应用」）。
 * 浮层按内容宽（200–300），内容最高 240、可滚动；[alignEnd] 为真时右缘对齐（页脚右段，同模型 chip），否则左缘对齐（左段，同 SSH 的权限 chip）。
 * chip 的字：当前值的名字 → 当前值的原始 id → 配置名。没有可选项时 chip 置灰；[enabled] 为 false 时（运行中、待审批、切换中、已断开）chip 和选项都置灰。
 */
@Composable
internal fun CodeFooterSelect(
    taskKey: String, title: String, current: String, options: List<AcpConfigValue>,
    enabled: Boolean, alignEnd: Boolean = false, onSelect: (String) -> Unit,
) {
    var open by remember(taskKey) { mutableStateOf(false) }
    NativeOverlay(open)
    val label = options.firstOrNull { it.id == current }?.name ?: current.ifBlank { title }
    Box {
        CodeFooterChip(label, { open = true }, held = open, enabled = enabled && options.isNotEmpty())
        if (open) CodePopup({ open = false }, side = PopupSide.Above, alignEnd = alignEnd, gap = 2.4.dp, width = 200.dp, maxWidth = 300.dp) {
            CodeMenuCaption(title)
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                options.forEach { o -> CodeMenuItem(o.name, { open = false; onSelect(o.id) }, enabled = enabled, checked = o.id == current) }
            }
        }
    }
}

/** 本机对话会话头右端的工具（本机对齐 P6）：「改动」（P6a，同服务器 Codex 页的图标）、「文件」（P6b，服务器页收在 ⋮ 里，本机没有 ⋮ 就直接放图标）。
 *  两个互斥：开着时对话列换成 [CodeLocalChanges] / [LocalFilesPane]，停靠区不变。 */
@Composable
internal fun CodeLocalToolButtons(tool: String?, directory: String, onTool: (String?) -> Unit) {
    CodeIconButton(Icons.Outlined.Difference, "改动", active = tool == "changes") { onTool(if (tool == "changes") null else "changes") }
    CodeIconButton(Icons.Outlined.FolderOpen, "文件", active = tool == "files") { onTool(if (tool == "files") null else "files") }
    CodeOpenInButton(directory)   // M4m：在编辑器 / 访达中打开这个会话的目录
}

/** 本机 Code 页的「改动」「文件」（M4b）：开在右侧卡片里（[CodePanelLayout]，同 SSH 会话页），对话和停靠区留在左边；[tool] 为空时只画主区。 */
@Composable
internal fun CodeLocalToolPanel(state: AppState, record: LocalCodexTaskRecord, tool: String?, onClose: () -> Unit, main: @Composable () -> Unit) {
    CodePanelLayout(when (tool) { "changes" -> "改动"; "files" -> "文件"; else -> null }, onClose, panel = {
        if (tool == "changes") CodeLocalChanges(state, record, onClose) else LocalFilesPane(record.key, record.directory)
    }, main = main)
}

/**
 * 本机对话的 Git 改动（本机对齐 P6a）：同服务器 Codex 页的改动视图（[GitChangesView]，两种风格、本机服务器共用），数据来自本机的 [LocalGit]，只读。
 * 「打开文件」改成复制绝对路径（本机对齐的原则：模型给的路径不调 Desktop.open，本机文件面板留给 P6b）；「针对这份差异提要求」把摘录接到这条对话的草稿末尾，然后收起改动。
 */
@Composable
internal fun CodeLocalChanges(state: AppState, record: LocalCodexTaskRecord, close: () -> Unit) {
    val t = Tokens.current
    var copied by remember(record.key) { mutableStateOf("") }
    LaunchedEffect(copied) { if (copied.isNotBlank()) { kotlinx.coroutines.delay(3000); copied = "" } }
    Column(Modifier.fillMaxSize()) {
        if (copied.isNotBlank()) Note("已复制路径：$copied", t.textMuted)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GitChangesView(record.key, record.directory, "本机", { file, staged -> LocalGit.run(record.directory, file, staged) },
                openFile = { path ->
                    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(path), null) }
                        .onSuccess { copied = path }
                },
                onQuote = { quote ->
                    val draft = state.chatDrafts.getOrPut(record.key) { mutableStateOf(TextFieldValue()) }
                    val text = draft.value.text.let { it + if (it.isBlank()) "" else "\n\n" } + quote + "\n"
                    draft.value = TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
                    close()
                })
        }
    }
}
