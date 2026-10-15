package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TaskSwitcherDialog(state: AppState) {
    var query by remember { mutableStateOf("") }
    var includeArchived by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matches = state.conns.flatMap { c -> c.sessions.map { c to it } }
        .filter { (c, s) ->
            val key = taskNavigationKey(c.host, s)
            val searchable = listOf(state.navigation.title(key).orEmpty(), s.name, s.cwd, c.host.label, c.host.region).joinToString(" ")
            (includeArchived || !state.navigation.archived(key)) && terms.all { searchable.contains(it, ignoreCase = true) }
        }
        .sortedByDescending { (c, s) -> state.navigation.pinned(taskNavigationKey(c.host, s)) }
    // Code 风格（规格表 §3.6）：遮罩 + 顶在 264 的 672 宽搜索框，结果行 36、方向键选、回车打开；数据和经典同一份
    if (LocalThemeSpec.current.style == UiStyle.Code) {
        CodeTaskSwitcher(state, query, { query = it }, includeArchived, { includeArchived = it }, matches, focus)
        return
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    WorkbenchDialog(
        onDismissRequest = { state.showTaskSwitcher = false },
        title = { Text("切换任务") },
        confirmButton = { TextButton({ state.showTaskSwitcher = false }) { Text("关闭") } },
        text = {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("任务、项目路径、服务器或地区") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && matches.isNotEmpty()) {
                            val (c, s) = matches.first()
                            state.select(c, s); state.showTaskSwitcher = false
                            true
                        } else false
                    })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${matches.size} 个任务", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Checkbox(includeArchived, { includeArchived = it })
                    Text("包含归档", style = MaterialTheme.typography.bodySmall)
                }
                if (matches.isEmpty()) Text("暂无匹配任务，请连接服务器或调整搜索。", color = Tokens.current.textMuted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matches) { (c, s) ->
                        val key = taskNavigationKey(c.host, s)
                        OutlinedCard(Modifier.fillMaxWidth().clickable {
                            state.select(c, s); state.showTaskSwitcher = false
                        }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(state.navigation.title(key) ?: s.short, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(listOf(c.host.label, c.host.region, s.runtimeId,
                                    if (state.navigation.archived(key)) "已归档" else "",
                                    if (state.navigation.pinned(key)) "置顶" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
                                Text(s.cwd, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = Tokens.current.textSecondary)
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Code 风格的切换任务（规格表 §3.6）：黑 40% 遮罩（参照端还有 ≈2 的模糊，不做）；面板宽 672、水平居中、顶 264、圆角 12、浮层底和阴影；
 * 输入区高 64、字 16，下面一条细线；结果行高 36、左右内缩 10.4、圆角 8，当前行 #F3F3F3（深色用浅填充）；结果区最高 236（面板总高 ≈301）。
 * ↑ / ↓ 换当前行，回车打开，Esc 或点遮罩关掉。「包含归档」放在输入区右端。
 */
@Composable
private fun CodeTaskSwitcher(state: AppState, query: String, onQuery: (String) -> Unit, includeArchived: Boolean, onArchived: (Boolean) -> Unit,
    matches: List<Pair<Conn, app.yxi.agent.Session>>, focus: FocusRequester) {
    val t = Tokens.current
    val p = CodePalette.current
    var current by remember(query, includeArchived) { mutableStateOf(0) }
    val list = rememberLazyListState()
    fun close() { state.showTaskSwitcher = false }
    fun pick(i: Int) { matches.getOrNull(i)?.let { (c, s) -> state.select(c, s); close() } }
    LaunchedEffect(current) { if (matches.isNotEmpty()) list.animateScrollToItem(current.coerceIn(0, matches.size - 1)) }
    // 打开就能打字。截图夹具的时钟不走（tick = false）时不抢焦点：对话框是单独的一层、拿着焦点光标会一直闪，画面稳不下来
    if (LocalStatusClock.current.tick) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    NativeOverlay()
    Dialog(::close, DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Black.copy(alpha = 0.4f))) {
        val outside = remember { MutableInteractionSource() }
        val inside = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize().clickable(outside, indication = null) { close() }) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.align(Alignment.TopCenter).padding(top = 264.dp).width(672.dp).codeShadow(12.dp, PopupShadow)
                .background(p.popup, shape).border(hairline(), p.popupRing, shape).clip(shape).clickable(inside, indication = null) {}) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = t.textMuted)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("搜索任务、项目路径、服务器或地区", color = t.textMuted, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1)
                        BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.DirectionDown -> { if (matches.isNotEmpty()) current = (current + 1) % matches.size; true }
                                Key.DirectionUp -> { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size; true }
                                Key.Enter, Key.NumPadEnter -> { pick(current); true }
                                Key.Escape -> { close(); true }
                                else -> false
                            }
                        }, singleLine = true, cursorBrush = SolidColor(t.textPrimary), textStyle = TextStyle(color = t.textPrimary, fontSize = 16.sp, lineHeight = 22.sp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("包含归档", Modifier.clip(RoundedCornerShape(5.dp)).clickable { onArchived(!includeArchived) }
                        .background(if (includeArchived) p.activeFill else Color.Transparent).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (includeArchived) p.activeGlyph else t.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(if (t.dark) t.border else Color(0xFFE6E6E6)))
                if (matches.isEmpty()) Text(if (state.conns.isEmpty()) "还没有连接的服务器" else "没有匹配的任务", Modifier.padding(20.dp, 14.dp),
                    color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 236.dp), state = list, contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(matches) { i, (c, s) ->
                        val key = taskNavigationKey(c.host, s)
                        val hover = remember { MutableInteractionSource() }
                        val hovered by hover.collectIsHoveredAsState()
                        LaunchedEffect(hovered) { if (hovered) current = i }
                        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.4.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (i == current) p.menuHover else Color.Transparent).hoverable(hover).clickable { pick(i) }
                            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.navigation.title(key) ?: s.short, Modifier.weight(1f, fill = false), color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(10.dp))
                            Text(listOf(c.host.label, if (state.navigation.archived(key)) "已归档" else "", s.cwd).filter { it.isNotBlank() }.joinToString(" · "),
                                Modifier.weight(1f), color = t.textMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
