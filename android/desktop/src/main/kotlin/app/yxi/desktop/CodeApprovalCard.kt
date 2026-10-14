package app.yxi.desktop

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 审批卡上的一个按钮（M3d-2）：[primary] 是黑底主按钮，其余是描边次按钮。 */
internal class CodeApprovalAction(val label: String, val primary: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Code 风格的审批卡（M3d-2，规格表 §3.3）：停靠在输入卡上方、和输入卡同宽；白底（深色用浮层色）、圆角 10、浮层的外环和阴影、内边距 12；
 * 标题 14 粗主色，[body] 是正文（次色）和命令块；最后一行是按钮：[deny]（拒绝类）靠左，[actions] 靠右、主按钮放最右，间隔 8。
 * 键帽不画：规格要求只显示 Yxi 实际生效的键，这几种卡现在没有键盘快捷键（PRD §5.4）。动作仍走各自的控制器，这里只管外观。
 */
@Composable
internal fun CodeApprovalCard(title: String, deny: CodeApprovalAction?, actions: List<CodeApprovalAction>, modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit = {}) {
    val t = Tokens.current
    val p = CodePalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().codeShadow(10.dp, PopupShadow).background(p.popup, shape).border(hairline(), p.popupRing, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        body()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            deny?.let { CodeApprovalButton(it) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                (actions.filter { !it.primary } + actions.filter { it.primary }).forEach { CodeApprovalButton(it) }
            }
        }
    }
}

/** 正文一段：14 / 20 次色，可选中。 */
@Composable
internal fun CodeApprovalText(text: String) {
    SelectionContainer { Text(text, color = Tokens.current.textSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
}

/** 命令块（规格表 §3.3）：#F3F3F3 底（深色用浅填充）、圆角 5、没有边框、内边距左右 12 上下 8、等宽 12.5 / 16.8；太长时最高 220 可滚。 */
@Composable
internal fun CodeCommandBlock(text: String) {
    val t = Tokens.current
    val p = CodePalette.current
    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).background(p.menuHover, RoundedCornerShape(5.dp)).verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        SelectionContainer { Text(text, style = CodeStyle.copy(fontSize = 12.5.sp, lineHeight = 16.8.sp), color = t.textPrimary) }
    }
}

/**
 * 审批卡按钮（规格表 §3.3）：高 24、字 14。次按钮：卡片底色、≈#E6E6E6 细线描边、圆角 5、左右 8、主色字；
 * 主按钮：#0B0B0B 底（深色反过来用主字色作底）、圆角 6、左右 9、白色半粗字；不可用时 #9D9D9D 底。hover 时次按钮铺浅底、主按钮略淡。
 */
@Composable
internal fun CodeApprovalButton(action: CodeApprovalAction) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val primaryFill = if (t.dark) t.textPrimary else Color(0xFF0B0B0B)
    val primaryText = if (t.dark) Color(0xFF0B0B0B) else Color.White
    val shape = RoundedCornerShape(if (action.primary) 6.dp else 5.dp)
    val base = Modifier.height(24.dp).hoverable(source).clickable(interactionSource = source, indication = null, enabled = action.enabled, role = Role.Button, onClick = action.onClick)
    if (action.primary) Box(base.background(if (action.enabled) primaryFill else Color(0xFF9D9D9D), shape).alpha(if (hovered && action.enabled) 0.88f else 1f)
        .padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
        Text(action.label, color = primaryText, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    else Box(base.background(if (hovered && action.enabled) p.menuHover else p.popup, shape)
        .border(hairline(), if (t.dark) Color(0xFF3A3A39) else Color(0xFFE6E6E6), shape).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(action.label, color = if (action.enabled) t.textPrimary else p.actionDisabled, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
    }
}
