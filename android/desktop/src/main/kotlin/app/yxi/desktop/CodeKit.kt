package app.yxi.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.floor
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/**
 * Code 风格专用的颜色（Tokens 之外，取值见 design/ui-style-spec.md §1）。
 * 浅色是实测值；深色里标「推」的是推定值，等深色参考图补齐后再核。
 */
internal class CodePalette(
    val iconHover: Color,
    val activeFill: Color,
    val activeGlyph: Color,
    val disabledGlyph: Color,
    val groupPlusHover: Color,
    val navSelected: Color,
    val listSelected: Color,
    val plusCircle: Color,
    val plusCircleHover: Color,
    val primary: Color,
    val primaryDisabled: Color,
    val focusBlue: Color,
    val menuHover: Color,
    val sunken: Color,
    val scrollbar: Color,
    val dotWaiting: Color,
    val dotWaitingEdge: Color,
    val dotWorking: Color,
    val dotDone: Color,
    val dotIdle: Color,
    val dotIdleSelected: Color,
    val manual: Color,
    val titleArrow: Color,
    val cardPlus: Color,
    val dropArrow: Color,
    val chipBorder: Color,
    val menuDivider: Color,
    val enterFocused: Color,
    val enterBlurred: Color,
    val popup: Color,
    val popupRing: Color,
    val tooltip: Color,
    val windowGlyph: Color,
    val closeFill: Color,
    val chipHeld: Color,
    val checkRing: Color,
    val checkRingHeld: Color,
    val branchGlyph: Color,
    val ringTrack: Color,
    val ringHigh: Color,
    val actionDisabled: Color,
) {
    companion object {
        val current: CodePalette @Composable get() = if (Tokens.current.dark) CodeDark else CodeLight
    }
}
private val CodeLight = CodePalette(
    iconHover = Color(0xFFF0F0EF), activeFill = Color(0xFFCDE2FB), activeGlyph = Color(0xFF184F95),
    disabledGlyph = Color(0xFF828281), groupPlusHover = Color(0xFFE2E2E1), navSelected = Color(0xFFE3E3E2),
    listSelected = Color(0xFFE6E6E6), plusCircle = Color(0xFFEAE9E4), plusCircleHover = Color(0xFFD6D5D1),
    primary = Color(0xFF0B0B0B), primaryDisabled = Color(0xFF9D9D9D), focusBlue = Color(0xFF2A78D6),
    menuHover = Color(0xFFF3F3F3), sunken = Color(0xFFF0F0EF), scrollbar = Color(0xFFAEAEAD),
    // 会话状态点：语义和配色照 PRD §5.2（等你琥珀 / 干活中青 / 刚跑完铜 / 闲置灰），只借参考端的尺寸和实心 / 空心；
    // 琥珀和边色、闲置环是参考端实测，青取手机端 Teal（参考端运行中是灰 #C0BEBA，不用），铜是 Yxi 品牌铜
    dotWaiting = Color(0xFFFAB219), dotWaitingEdge = Color(0xFFF7BB3A), dotWorking = Color(0xFF0B8043), dotDone = Color(0xFFE08B57),
    dotIdle = Color(0xFFCCCBC7), dotIdleSelected = Color(0xFFC4C2BD),
    manual = Color(0xFF734500), titleArrow = Color(0xFF575653), cardPlus = Color(0xFF61605D),
    dropArrow = Color(0xFF8C8A84), chipBorder = Color(0xFFE4E4E3), menuDivider = Color(0xFFE6E6E6),
    enterFocused = Color(0xFFAAAAAA), enterBlurred = Color(0xFFCCCCCC),
    popup = Color(0xFFFFFFFF), popupRing = Color.Black.copy(alpha = 0.10f), tooltip = Color(0xFF0B0B0B),
    windowGlyph = Color(0xFF3D3D3A), closeFill = Color(0xFFC42B1C), // closeFill 推定（Win11 标准关闭红）
    // chipHeld 用 §1.4 的推算值：实测 ≈#E9E9E8 是被浮层阴影压暗后的，照抄会被我们的阴影再压一次
    chipHeld = Color(0xFFF0F0EF), checkRing = Color(0xFFCCCCCB), checkRingHeld = Color(0xFFC0C0BF),
    branchGlyph = Color(0xFF585754), ringTrack = Color(0xFFE2E2E1), ringHigh = Color(0xFFFAB219),
    actionDisabled = Color(0xFFCCCCCC), // 消息操作行里不可用的图标（§3.4 重试 #CCCCCC）
)

private val CodeDark = CodePalette(
    iconHover = Color(0xFF2A2A29), activeFill = Color(0xFF1F3A5C), activeGlyph = Color(0xFF6DA7EC), // 推
    disabledGlyph = Color(0xFF5D5C5A), groupPlusHover = Color(0xFF343434), navSelected = Color(0xFF343434), // 前两项推
    listSelected = Color(0xFF343434), plusCircle = Color(0xFF2E2E2D), plusCircleHover = Color(0xFF3A3A39), // 圆底推
    primary = Color(0xFFF0EFEC), primaryDisabled = Color(0xFF5D5C5A), focusBlue = Color(0xFF2A78D6), // 推
    menuHover = Color(0xFF2A2A29), sunken = Color(0xFF212121), scrollbar = Color(0xFF5D5C5A),
    dotWaiting = Color(0xFFFAB219), dotWaitingEdge = Color(0xFFF7BB3A), dotWorking = Color(0xFF8FD8C6), dotDone = Color(0xFFE08B57),
    dotIdle = Color(0xFF5A5955), dotIdleSelected = Color(0xFF65645F), // 闲置环推定
    manual = Color(0xFFDB9300), titleArrow = Color(0xFFA8A69F), cardPlus = Color(0xFFA8A69F), // 后两项推
    dropArrow = Color(0xFF8C8A84), chipBorder = Color(0xFF313131), menuDivider = Color(0xFF313131), // 推
    enterFocused = Color(0xFF6B6A66), enterBlurred = Color(0xFF4A4A48), // 推
    popup = Color(0xFF20201F), popupRing = Color.White.copy(alpha = 0.10f), tooltip = Color(0xFF0B0B0B),
    windowGlyph = Color(0xFFC2C0B6), closeFill = Color(0xFFC42B1C),
    chipHeld = Color(0xFF2E2E2D), checkRing = Color(0xFF5D5C5A), checkRingHeld = Color(0xFF6B6A66), // 推
    branchGlyph = Color(0xFFA8A69F), ringTrack = Color(0xFF3A3A39), ringHigh = Color(0xFFFAB219), // 推
    actionDisabled = Color(0xFF4A4A48), // 推
)

/**
 * 细线宽：取整到整数个设备像素（至少 1 个），与 Chromium 画 1px 边框的取整一致。
 * 125% 时是 0.8dp（1 设备像素），Retina 下是 1dp。
 */
@Composable
internal fun hairline(): Dp {
    val d = LocalDensity.current.density
    return (maxOf(1f, floor(d)) / d).dp
}

/** 近似 CSS box-shadow 的一层：纵向偏移、模糊半径、颜色（不支持扩散和横向偏移，规格里也没有）。 */
internal class ShadowLayer(val dy: Dp, val blur: Dp, val color: Color)

/** 浮层 / 菜单：0 1.5 6 rgba(0,0,0,.067) + 0 8 24 rgba(0,0,0,.126)。 */
internal val PopupShadow = listOf(
    ShadowLayer(1.5.dp, 6.dp, Color.Black.copy(alpha = 0.067f)),
    ShadowLayer(8.dp, 24.dp, Color.Black.copy(alpha = 0.126f)),
)

/** 输入卡：0 7 16 rgba(0,0,0,.034)。 */
internal val ComposerShadow = listOf(ShadowLayer(7.dp, 16.dp, Color.Black.copy(alpha = 0.034f)))
/** 在内容背后画阴影（高斯模糊的圆角矩形，sigma ≈ CSS blur / 2）。要挂在 background 之前。 */
internal fun Modifier.codeShadow(radius: Dp, layers: List<ShadowLayer>): Modifier = drawBehind {
    val r = radius.toPx()
    drawIntoCanvas { canvas ->
        for (layer in layers) {
            val paint = Paint().apply { color = layer.color }
            paint.skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, layer.blur.toPx() / 2f)
            val dy = layer.dy.toPx()
            canvas.drawRoundRect(0f, dy, size.width, size.height + dy, r, r, paint)
        }
    }
}

/**
 * 图标按钮（会话头、分组标题、图标行）：透明底，hover 浅灰底，激活态蓝底蓝字形。
 * [held]：对应的浮层开着时保持 hover 底。不可用时 tooltip 照出。[tipAbove]：贴着窗口底边的（停靠区里的）把 tooltip 放上方。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CodeIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    box: Dp = 24.dp,
    iconSize: Dp = 16.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    held: Boolean = false,
    tint: Color = Tokens.current.textSecondary,
    hoverFill: Color = CodePalette.current.iconHover,
    shortcut: String? = null,
    tipAbove: Boolean = false,
    onClick: () -> Unit,
) {
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill = when {
        active -> p.activeFill
        enabled && (held || hovered) -> hoverFill
        else -> Color.Transparent
    }
    TooltipArea(
        tooltip = { CodeTooltip(label, shortcut) },
        modifier = modifier,
        delayMillis = 500,
        tooltipPlacement = if (tipAbove) TooltipPlacement.ComponentRect(Alignment.TopCenter, Alignment.TopCenter, DpOffset(0.dp, (-5).dp))
            else TooltipPlacement.ComponentRect(Alignment.BottomCenter, Alignment.BottomCenter, DpOffset(0.dp, 5.dp)),
    ) {
        Box(
            Modifier.size(box).clip(RoundedCornerShape(6.dp)).background(fill).hoverable(source)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(iconSize), tint = if (!enabled) p.disabledGlyph else if (active) p.activeGlyph else tint)
        }
    }
}
/** 反色 tooltip：深底白字，快捷键用弱色。 */
@Composable
internal fun CodeTooltip(label: String, shortcut: String? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(CodePalette.current.tooltip).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
        if (shortcut != null) Text(shortcut, color = Tokens.current.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}

internal enum class PopupSide { Below, Above, Right }

/** 贴着触发控件放：下方 / 上方（左对齐或右对齐），或右侧（子菜单）；超出窗口时夹回窗口内。 */
private class CodePopupPosition(val side: PopupSide, val alignEnd: Boolean, val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val x = when {
            side == PopupSide.Right -> anchorBounds.right + gap
            alignEnd -> anchorBounds.right - w
            else -> anchorBounds.left
        }
        val y = when (side) {
            PopupSide.Below -> anchorBounds.bottom + gap
            PopupSide.Above -> anchorBounds.top - gap - h
            PopupSide.Right -> anchorBounds.top
        }
        return IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - w)), y.coerceIn(0, maxOf(0, windowSize.height - h)))
    }
}
/**
 * Code 风格浮层：白底 r10、内边距 4、1dp 外环 + 两层阴影；Esc 或点外面关闭。
 * 要放在包着触发控件的 Box 里（锚点就是那个 Box）；NativeOverlay 由持有者调用。[width] 为 null 时按内容宽；
 * 给了 [maxWidth] 就在 [width]..[maxWidth] 之间按内容宽（条目文案和经典共用、长短不定的菜单）。
 */
@Composable
internal fun CodePopup(
    onDismiss: () -> Unit,
    side: PopupSide = PopupSide.Below,
    alignEnd: Boolean = false,
    gap: Dp = 6.dp,
    width: Dp? = 200.dp,
    maxWidth: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    val shape = RoundedCornerShape(10.dp)
    val p = CodePalette.current
    Popup(
        popupPositionProvider = remember(side, alignEnd, gapPx) { CodePopupPosition(side, alignEnd, gapPx) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            (if (width != null && maxWidth != null) Modifier.widthIn(width, maxWidth).width(IntrinsicSize.Max)
            else if (width != null) Modifier.width(width) else Modifier.width(IntrinsicSize.Max))
                .codeShadow(10.dp, PopupShadow).background(p.popup, shape).border(1.dp, p.popupRing, shape).padding(4.dp)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
            content = content,
        )
    }
}
/** 侧栏里右对齐 ⋮ 的浮层按内容宽的上限：⋮ 右缘在 x ≈274–276，再宽左缘就出窗，会被夹回、脱离 ⋮；264 时左边留 ≈12，和侧栏行的右内边距对称。 */
internal val CodeSidebarMenuMax = 264.dp
/** 浮层到 [CodeSidebarMenuMax] 时条目的字宽：减去浮层内边距 4 × 2 和条目内边距 8.8 + 8（路径项不带勾）。 */
private val CodeSidebarMenuTextMax = CodeSidebarMenuMax - 24.8.dp

/**
 * [text] 放不进 [maxWidth] 时从前面截、补「…」，留住能放下的最长尾段。
 * 桌面端的 TextOverflow.StartEllipsis 实测按末尾省略，末段照样丢，所以自己量。
 */
@Composable
private fun startEllipsized(text: String, style: TextStyle, maxWidth: Dp): String {
    val measurer = rememberTextMeasurer()
    val limit = with(LocalDensity.current) { maxWidth.roundToPx() }
    return remember(text, style, limit) {
        val fits = { s: String -> measurer.measure(s, style, softWrap = false, maxLines = 1).size.width <= limit }
        if (fits(text)) return@remember text
        var lo = 1
        var hi = text.length   // 找最小的 lo：「…」+ text.substring(lo) 放得下；hi = text.length 时只剩「…」
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (fits("…" + text.substring(mid))) hi = mid else lo = mid + 1
        }
        if (lo < text.length && text[lo].isLowSurrogate()) lo++   // 别切开代理对
        "…" + text.substring(lo)
    }
}
/**
 * 浮层里的单行项：高 24、字 14；字墨起于填充左 +12.8（外层 4 + 自身 8.8）；
 * 右侧数值 / 快捷键距右 12，勾 / 子菜单箭头中心距右 16。[held]：子菜单开着时保持 hover 底。
 * [path]：文案是目录路径，不在「/」处折行，放不下时从前面省略、留住末段（能认出是哪个目录）。
 */
@Composable
internal fun CodeMenuItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: String? = null,
    checked: Boolean = false,
    submenu: Boolean = false,
    held: Boolean = false,
    color: Color = Tokens.current.textPrimary,
    path: Boolean = false,
) {
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val icon = checked || submenu
    Row(
        Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp))
            .background(if (enabled && (hovered || held)) p.menuHover else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 8.8.dp, end = if (icon) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 路径项按侧栏 ⋮ 浮层的字宽截（路径项只出现在那里，见 CodeMenuEntries）；字号同下面的 Text
        val shown = if (path) startEllipsized(label, LocalTextStyle.current.merge(TextStyle(fontSize = 14.sp, lineHeight = 20.sp)), CodeSidebarMenuTextMax) else label
        Text(
            shown, Modifier.weight(1f), color = if (enabled) color else p.disabledGlyph,
            fontSize = 14.sp, lineHeight = 20.sp, softWrap = !path, maxLines = 1, overflow = TextOverflow.Clip,
        )
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            Text(trailing, color = Tokens.current.textMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
        }
        if (checked) Icon(Icons.Filled.Check, null, Modifier.padding(start = 8.dp).size(14.dp), tint = p.focusBlue)
        else if (submenu) Icon(Icons.Filled.ChevronRight, null, Modifier.padding(start = 8.dp).size(14.dp), tint = p.dropArrow)
    }
}

/** 带说明的两行项（模型、权限模式…）：步距 40.6，主行 14 主色、说明 12 弱色；勾的中心距右 16（规格 §3.1）。 */
@Composable
internal fun CodeMenuOption(
    label: String, detail: String, onClick: () -> Unit, checked: Boolean = false, color: Color = Tokens.current.textPrimary, enabled: Boolean = true,
) {
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(40.6.dp).clip(RoundedCornerShape(6.dp))
            .background(if (enabled && hovered) p.menuHover else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 8.8.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) color else p.disabledGlyph, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Clip)
            Text(detail, color = Tokens.current.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Icon(Icons.Filled.Check, null, Modifier.padding(start = 8.dp).size(14.dp), tint = p.focusBlue)
    }
}

/** 分组线：左右内缩 12（外层 4 + 自身 8），连同上下间距共 9 高。 */
@Composable
internal fun CodeMenuDivider() {
    Box(Modifier.fillMaxWidth().height(9.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(hairline()).background(CodePalette.current.menuDivider))
    }
}
/** 浮层顶部的说明块（账号菜单头）：不可点。 */
@Composable
internal fun CodeMenuHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 8.8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        Text(title, color = Tokens.current.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = Tokens.current.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
/** 浮层里的说明 / 状态小字（加载中、出错、提示）：可换行、不可点，字墨与单行项对齐。 */
@Composable
internal fun CodeMenuNote(text: String, color: Color = Tokens.current.textMuted) {
    Text(
        text, Modifier.fillMaxWidth().padding(start = 8.8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        color = color, fontSize = 12.sp, lineHeight = 17.sp,
    )
}
