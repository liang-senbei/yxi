package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PreferencesLayout(title: String, sections: List<String>, selected: String, select: (String) -> Unit, back: () -> Unit, content: @Composable () -> Unit) {
    var search by remember(title) { mutableStateOf("") }
    val t = Tokens.current
    BoxWithConstraints(Modifier.fillMaxSize().background(t.surface0)) {
        // maxWidth 需在嵌套 Row/Column 作用域外读出，隐式接收者规则不允许内层直接调用
        val maxW = maxWidth
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(if (maxW < 720.dp) 184.dp else 244.dp).fillMaxHeight().background(t.surface1).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TextButton(back, colors = ButtonDefaults.textButtonColors(contentColor = t.textSecondary)) { Icon(Icons.Outlined.ArrowBack, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("返回工作台") }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(t.surface3.copy(alpha = 0.6f), RoundedCornerShape(9.dp)).padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = t.textMuted)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(search, { search = it }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = t.textPrimary), modifier = Modifier.weight(1f), decorationBox = { inner -> Box { if (search.isBlank()) Text("搜索", style = MaterialTheme.typography.bodySmall, color = t.textMuted); inner() } })
                }
                Text(title, Modifier.padding(10.dp, 16.dp), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
                sections.filter { it.contains(search.trim(), ignoreCase = true) }.forEach { label ->
                    val icon = when (label) {
                        "个人资料", "账号" -> Icons.Outlined.Person
                        "账号与连接", "模型与线路" -> Icons.Outlined.Link
                        "钱包与订单", "商城", "兑换码" -> Icons.Outlined.AccountBalanceWallet
                        "信箱" -> Icons.Outlined.MailOutline
                        "工单" -> Icons.Outlined.HelpOutline
                        "外观" -> Icons.Outlined.Palette
                        "通知" -> Icons.Outlined.NotificationsNone
                        "输入历史" -> Icons.Outlined.History
                        else -> Icons.Outlined.Settings
                    }
                    Row(Modifier.fillMaxWidth().background(if (label == selected) t.surface2 else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(9.dp)).clickable { select(label) }.padding(12.dp, 11.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Icon(icon, null, Modifier.size(18.dp), tint = if (label == selected) t.textPrimary else t.textMuted)
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (label == selected) t.textPrimary else t.textSecondary, fontWeight = if (label == selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal)
                    }
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }
}
