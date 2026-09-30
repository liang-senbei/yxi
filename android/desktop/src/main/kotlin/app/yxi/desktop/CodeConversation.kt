package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

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
