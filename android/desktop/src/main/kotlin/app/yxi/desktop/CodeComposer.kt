package app.yxi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Code 风格的输入卡与 chips（design/ui-style-spec.md §3.2、§3.8）。
 * 只管外观和按键；发什么、发给谁由调用方决定，和经典 Composer 共用同一套语义：
 * 输入法组词时 Enter 归输入法，Enter 发送，Shift+Enter 换行。
 */

/** chip / 页脚文字：规格 ≈13～14，取 13。 */
private val ChipText = 13.sp
private val ChipLine = 18.sp

/**
 * 输入卡：768 宽由调用方给，最矮 44，圆角 10；外环聚焦深一档，底下一层很淡的阴影。
 * 右端是回车图元（聚焦深、失焦浅，和有没有字无关）；[running] 时换成停止方块。
 * [onKey] 最先处理（和经典 Composer 里 mentions.handle 同位，按下、抬起都会收到），返回 true 表示已处理。
 * [top] 画在输入行上方、卡片里面（附件托盘、@ 列表）；空着时卡片和原来一样。
 */
@Composable
internal fun CodeComposerCard(
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    placeholder: String,
    focus: FocusRequester,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    onStop: () -> Unit = {},
    readOnly: Boolean = false,
    onKey: (KeyEvent) -> Boolean = { false },
    top: @Composable ColumnScope.() -> Unit = {},
) {
    val t = Tokens.current
    val p = CodePalette.current
    val shape = RoundedCornerShape(RadiusComposer)
    var focused by remember { mutableStateOf(false) }
    val sendSource = remember { MutableInteractionSource() }
    Column(
        modifier.codeShadow(RadiusComposer, ComposerShadow).clip(shape).background(t.composer)
            .border(hairline(), if (focused) t.composerRingFocused else t.composerRing, shape),
    ) {
        top()
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = draft, onValueChange = onDraft, readOnly = readOnly,
                textStyle = BodyStyle.copy(color = t.textPrimary), cursorBrush = SolidColor(t.textPrimary),
                maxLines = 8,
                modifier = Modifier.weight(1f).padding(start = 10.dp, top = 12.dp, bottom = 12.dp)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            onKey(e) -> true
                            e.type != KeyEventType.KeyDown || draft.composition != null -> false   // 中文输入法正在组词时 Enter / Esc 归输入法
                            enter && !e.isShiftPressed -> { if (canSend && !readOnly) onSend(); true }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box { if (draft.text.isEmpty()) Text(placeholder, style = BodyStyle, color = t.textMuted, maxLines = 1); inner() }
                },
            )
            // 图元 10.4 宽、中心离卡片右缘 ≈22：24 的点击区 + 右边 10；44 高时和文字行同在正中
            Box(
                Modifier.padding(start = 8.dp, end = 10.dp, bottom = 10.dp).size(24.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(interactionSource = sendSource, indication = null, enabled = if (running) true else canSend && !readOnly, role = Role.Button, onClick = if (running) onStop else onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (running) CodeStopGlyph() else CodeEnterGlyph(if (focused) p.enterFocused else p.enterBlurred)
            }
        }
    }
}
/** 回车图元：竖线下行、左拐、箭头朝左，10.4×8.8（Yxi 自绘）。 */
@Composable
internal fun CodeEnterGlyph(color: Color) {
    Canvas(Modifier.size(10.4.dp, 8.8.dp)) {
        val w = 1.2.dp.toPx()
        val h = w / 2
        val bend = size.height * 0.62f
        val head = 3.dp.toPx()
        val path = Path().apply {
            moveTo(size.width - h, h)
            lineTo(size.width - h, bend)
            lineTo(h, bend)
            moveTo(h + head, bend - head)
            lineTo(h, bend)
            lineTo(h + head, bend + head)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 运行中 / 排队时的停止方块：10.4×9.6，次色。 */
@Composable
internal fun CodeStopGlyph() {
    val c = Tokens.current.textSecondary
    Canvas(Modifier.size(10.4.dp, 9.6.dp)) { drawRoundRect(c, cornerRadius = CornerRadius(1.5.dp.toPx())) }
}

/**
 * 输入卡上方的 chip：高 24、圆角 5、细线描边、透明底；hover 或下拉开着时铺一层底，图标和字变主色。
 * [icon] 为空时只放 [trailing]（例如只有图标的 chip 由调用方自己画）。
 */
@Composable
internal fun CodeChip(
    label: String?,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    iconTint: Color = Tokens.current.textSecondary,
    endPadding: Dp = 7.dp,
    trailing: @Composable RowScope.(Boolean) -> Unit = {},
) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lit = held || (onClick != null && hovered)
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.height(24.dp).clip(shape).background(if (lit) p.chipHeld else Color.Transparent).border(hairline(), p.chipBorder, shape)
            .then(if (onClick == null) Modifier else Modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick))
            .padding(start = 7.dp, end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = if (lit) t.textPrimary else iconTint)
        if (label != null) {
            if (icon != null) Spacer(Modifier.width(7.6.dp))
            Text(label, color = if (lit) t.textPrimary else t.textSecondary, fontSize = ChipText, lineHeight = ChipLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing(lit)
    }
}
/** worktree 勾选框：12×12、圆角 2.5；没勾是细线框，勾上是蓝底白勾。 */
@Composable
internal fun CodeCheckbox(checked: Boolean, held: Boolean = false) {
    val p = CodePalette.current
    val shape = RoundedCornerShape(2.5.dp)
    Box(
        Modifier.size(12.dp).clip(shape)
            .then(if (checked) Modifier.background(p.focusBlue) else Modifier.border(hairline(), if (held) p.checkRingHeld else p.checkRing, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, Modifier.size(10.dp), tint = Color.White)
    }
}

/** 上下文环：12×12，灰轨 + 蓝弧（用量高时换琥珀），不转动；[fraction] 为空（新会话）时整环灰。 */
@Composable
internal fun CodeContextRing(fraction: Float?) {
    val p = CodePalette.current
    Canvas(Modifier.size(12.dp)) {
        val w = 1.6.dp.toPx()
        val inset = w / 2
        val arc = Size(size.width - w, size.height - w)
        drawArc(p.ringTrack, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
        val f = fraction?.coerceIn(0f, 1f) ?: return@Canvas
        if (f > 0f) drawArc(if (f >= 0.8f) p.ringHigh else p.focusBlue, -90f, 360f * f, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** 页脚的文字按钮（权限、运行器、模型…）：高 28、左右 6.4、圆角 5；hover 和浮层开着时铺浅底（开着时看着深一些，是浮层阴影压的，§1.4）。 */
@Composable
internal fun CodeFooterChip(label: String, onClick: () -> Unit, color: Color = Tokens.current.textPrimary, held: Boolean = false, enabled: Boolean = true) {
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill = when {
        held -> p.chipHeld
        enabled && hovered -> p.iconHover
        else -> Color.Transparent
    }
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(5.dp)).background(fill).hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) color else p.disabledGlyph, fontSize = ChipText, lineHeight = ChipLine, maxLines = 1)
    }
}

/**
 * 对话页的输入区（规格 §2、§3.2）：输入卡 + 下面 28 高的页脚，宽度随停靠区（768）。
 * 由经典 [Composer] 在 Code 风格下调用，按键 [onKey] 是它传进来的同一个处理（发送 / 贴图 / 审批），行为不分叉。
 * 页脚左：权限、「+」（附件、历史、搜索、语音、线路）、状态字；右：模型、上下文环（用量写在悬停提示里）。
 * 运行中不画停止方块：对话页没有中断动作（PRD 有意偏差）。
 */
@Composable
internal fun CodeChatComposer(
    attachments: List<DraftAttach>, onRemoveAttachment: (DraftAttach) -> Unit, mentions: AttachmentMentions,
    draft: TextFieldValue, onDraft: (TextFieldValue) -> Unit, focus: FocusRequester, hint: String,
    canSend: Boolean, onSend: () -> Unit, onKey: (KeyEvent) -> Boolean,
    canAct: Boolean, planMode: Boolean, tokens: Long,
    onAttach: () -> Unit, onHistory: () -> Unit, onSearch: (() -> Unit)?, onVoice: () -> Unit, onRoutes: (() -> Unit)?,
    permissionControl: @Composable () -> Unit, modelControl: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        CodeComposerCard(draft, onDraft, hint, focus, canSend, onSend, Modifier.fillMaxWidth(), onKey = onKey) {
            DraftAttachmentTray(attachments, onRemoveAttachment)
            AttachmentMentionList(mentions)
        }
        Spacer(Modifier.height(2.4.dp))
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            permissionControl()
            Spacer(Modifier.width(4.dp))
            CodeComposerPlus(onAttach, onHistory, onSearch, onVoice, onRoutes)
            if (!canAct) CodeFooterNote("重新连接后可操作")
            if (planMode) CodeFooterNote("计划模式", Tokens.current.warning)
            Spacer(Modifier.weight(1f))
            modelControl()
            Spacer(Modifier.width(9.6.dp))
            CodeHint(if (tokens > 0) "上下文 " + kShort(tokens) else "上下文用量") { CodeContextRing(null) }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}
/** 页脚里的状态字（不可点）：和 [CodeFooterChip] 同字号、同左右留白。 */
@Composable
internal fun CodeFooterNote(label: String, color: Color = Tokens.current.textSecondary) {
    Text(label, Modifier.padding(horizontal = 6.4.dp), color = color, fontSize = ChipText, lineHeight = ChipLine, maxLines = 1)
}

/**
 * 页脚的「+」：规格 #61605D、宽 11.2，离权限标签 ≈16.8（24 的点击区 + 左边 4）。点开向上展开，收着经典功能行里的
 * 附件、输入历史、搜索、语音和线路 chip（入口不少，只是换了位置）。
 */
@Composable
private fun CodeComposerPlus(onAttach: () -> Unit, onHistory: () -> Unit, onSearch: (() -> Unit)?, onVoice: () -> Unit, onRoutes: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box {
        CodeHint("附件、输入历史、搜索、语音") {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (open || hovered) p.iconHover else Color.Transparent).hoverable(source)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { open = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, "附件、输入历史、搜索、语音", Modifier.size(19.2.dp), tint = p.cardPlus) }
        }
        if (open) CodePopup(close, side = PopupSide.Above, gap = 2.4.dp, width = 240.dp) {
            CodeMenuItem("添加附件（截图可直接 Ctrl+V）", { close(); onAttach() })
            CodeMenuItem("输入历史", { close(); onHistory() })
            if (onSearch != null) CodeMenuItem("搜索当前对话", { close(); onSearch() })
            CodeMenuItem("语音输入", { close(); onVoice() })
            if (onRoutes != null) {
                CodeMenuDivider()
                CodeMenuItem("线路", { close(); onRoutes() })
            }
        }
    }
}
