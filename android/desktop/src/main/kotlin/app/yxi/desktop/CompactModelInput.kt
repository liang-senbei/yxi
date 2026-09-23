package app.yxi.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal val compactControlHeight: Dp
    @Composable get() = maxOf(36.dp, (28 * LocalDensity.current.fontScale).dp)

/** A true compact text control; no clipped Material minimum-height workaround. */
@Composable internal fun CompactTextInput(value: String, change: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, placeholder: String = "", height: Dp = compactControlHeight,
    focus: FocusRequester = remember { FocusRequester() }, trailing: (@Composable () -> Unit)? = null) {
    val t = Tokens.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, change, modifier.heightIn(min = height).focusRequester(focus)
        .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }, singleLine = true,
        textStyle = TextStyle(color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(t.accent),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = height).background(t.surface2, RoundedCornerShape(6.dp))
                .border(1.dp, if (focused) t.accent else t.border, RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = t.textMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                trailing?.invoke()
            }
        })
}

@Composable internal fun CompactModelInput(value: String, models: List<ProviderModels.Model>, change: (String) -> Unit,
    label: String, modifier: Modifier = Modifier, height: Dp = compactControlHeight) {
    var open by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(models.isEmpty()) { if (models.isEmpty()) open = false }
    val t = Tokens.current
    Box(modifier) {
        CompactTextInput(value, change, label, Modifier.fillMaxWidth().testTag("model:$label"),
            placeholder = "留空使用默认", height = height, focus = fieldFocus,
            trailing = if (models.isEmpty()) null else ({
                Box(Modifier.width(32.dp).height(height).clickable(onClickLabel = "选择$label") { open = true }
                    .testTag("picker:$label"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ExpandMore, "选择$label", Modifier.size(16.dp), tint = t.textMuted)
                }
            }))
        if (open) ModelSearchPopup(models, value, { open = false; fieldFocus.requestFocus() }) { chosen ->
            change(chosen); open = false; fieldFocus.requestFocus()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ModelSearchPopup(models: List<ProviderModels.Model>, value: String, dismiss: () -> Unit, pick: (String) -> Unit) {
    val t = Tokens.current
    var query by remember { mutableStateOf(TextFieldValue()) }
    val shown = remember(models, query.text) { models.filter { it.id.contains(query.text.trim(), true) || it.owner?.contains(query.text.trim(), true) == true } }
    var index by remember(shown) { mutableStateOf(shown.indexOfFirst { it.id == value }.coerceAtLeast(0)) }
    val searchFocus = remember { FocusRequester() }
    val scroll = rememberLazyListState()
    val density = LocalDensity.current
    val position = remember(density) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val gap = with(density) { 4.dp.roundToPx() }
            val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
            val below = anchorBounds.bottom + gap
            val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
            return IntOffset(x, y)
        }
    } }
    Popup(popupPositionProvider = position, onDismissRequest = dismiss, properties = PopupProperties(focusable = true)) {
        Surface(Modifier.width(320.dp).heightIn(max = 320.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || query.composition != null) false else when (event.key) {
                Key.DirectionDown -> { index = (index + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0)); true }
                Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); true }
                Key.Enter, Key.NumPadEnter -> { shown.getOrNull(index)?.let { pick(it.id) }; true }
                Key.Escape -> { dismiss(); true }
                else -> false
            }
        }, shape = RoundedCornerShape(8.dp), color = t.surface2, shadowElevation = 8.dp, border = BorderStroke(1.dp, t.border)) {
            Column {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = t.textMuted)
                    BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(searchFocus).testTag("model-search"),
                        singleLine = true, textStyle = TextStyle(color = t.textPrimary, fontSize = 14.sp), cursorBrush = SolidColor(t.accent),
                        decorationBox = { inner -> Box { if (query.text.isEmpty()) Text("搜索模型…", color = t.textMuted, fontSize = 14.sp); inner() } })
                }
                HorizontalDivider(color = t.border)
                if (shown.isEmpty()) Text("没有匹配的模型", Modifier.padding(16.dp), color = t.textMuted, fontSize = 13.sp)
                else LazyColumn(Modifier.heightIn(max = 264.dp).padding(4.dp), state = scroll) {
                    itemsIndexed(shown) { row, model ->
                        val interaction = remember { MutableInteractionSource() }
                        val hover by interaction.collectIsHoveredAsState()
                        TooltipArea(tooltip = {
                            Surface(Modifier.widthIn(max = 420.dp), color = t.surface3, shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
                                Text(model.id, Modifier.padding(10.dp), color = t.textPrimary, fontSize = 13.sp)
                            }
                        }) {
                        Row(Modifier.fillMaxWidth().background(if (row == index || hover) t.hover else t.surface2, RoundedCornerShape(5.dp))
                            .hoverable(interaction).clickable { pick(model.id) }.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(model.id, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                model.owner?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = t.textMuted, maxLines = 1) }
                            }
                            if (model.id == value) Icon(Icons.Outlined.Check, "当前模型", Modifier.size(16.dp), tint = t.accent)
                        }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        LaunchedEffect(index, shown) { if (shown.isNotEmpty()) scroll.animateScrollToItem(index) }
    }
}
