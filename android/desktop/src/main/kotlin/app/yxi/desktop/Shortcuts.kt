package app.yxi.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 快捷键表（Ctrl+/）。实现在 Shell.kt 的 shortcut()，这里只是给人看的清单；两边一起改。 */
private val shortcuts = listOf(
    "Ctrl+N" to "新建会话",
    "Ctrl+B" to "切换侧边栏",
    "Ctrl+J" to "切换对话 / 终端",
    "Ctrl+Tab · Ctrl+Shift+Tab" to "下一个 / 上一个会话",
    "Ctrl+1 … 9" to "转到会话 N",
    "Ctrl+Alt+A" to "下一个需要处理的会话",
    "F5" to "刷新会话列表",
    "Ctrl+= · Ctrl+- · Ctrl+0" to "放大 / 缩小 / 实际大小",
    "Ctrl+," to "设置…",
    "Ctrl+/" to "键盘快捷键",
)

@Composable
fun ShortcutsDialog(state: AppState) {
    AlertDialog(
        onDismissRequest = { state.showShortcuts = false },
        confirmButton = { TextButton({ state.showShortcuts = false }) { Text("关闭") } },
        title = { Text("键盘快捷键") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shortcuts.forEach { (keys, what) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(what, Modifier.weight(1f))
                        Text(keys, fontFamily = Mono, color = Tokens.current.textSecondary)
                    }
                }
            }
        },
    )
}
