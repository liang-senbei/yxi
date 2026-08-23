package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.ui.theme.*

private val Pill = RoundedCornerShape(100.dp)

/**
 * 终端键盘工具条：手机软键盘上没有、但终端里离不开的那些键。
 *
 * ⚠️ **`Ctrl` 是粘滞的，不是立刻发一个字节。** 点一下 `Ctrl` 再打 `c` 才是 `^C`。
 * 实现不用碰 termlib 的内部 —— `onKeyboardInput` 那个回调本来就在我们手里，
 * 在那儿把下一个字节 `and 0x1f` 就行（见 [Workspace]）。这样**任何输入法都适用**。
 *
 * `^B` 单独列出来是因为它是 tmux 前缀 —— 在终端里管 tmux（换窗口、分屏）全靠它，
 * 而软键盘上根本打不出来。
 */
@Composable
fun KeyBar(
    ctrlArmed: Boolean,
    onCtrl: () -> Unit,
    /** 软键盘开关。⚠️ 终端控件不会自己弹键盘 —— 得有人点 */
    onKeyboard: () -> Unit,
    /** compose mode（中文输入的退路，见 TerminalView 类注释） */
    composing: Boolean,
    onCompose: () -> Unit,
    /** 语音。⚠️ 终端模式下识别结果要先确认，见 Workspace 里那个对话框 */
    onVoice: () -> Unit,
    send: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp, 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cap("⌨", false, onKeyboard)
        Cap("Ctrl", ctrlArmed, onCtrl)
        // 「中」= compose mode。直接打中文不出候选词时点它
        Cap("中", composing, onCompose)
        Cap("🎤", false, onVoice)
        listOf(
            "esc" to byteArrayOf(27),
            "tab" to byteArrayOf(9),
            "⇧tab" to byteArrayOf(27, 91, 90),
            "^C" to byteArrayOf(3),
            "^D" to byteArrayOf(4),
            "^Z" to byteArrayOf(26),
            "^L" to byteArrayOf(12),
            "^R" to byteArrayOf(18),
            "^B" to byteArrayOf(2),          // tmux 前缀
            "←" to byteArrayOf(27, 91, 68),
            "↓" to byteArrayOf(27, 91, 66),
            "↑" to byteArrayOf(27, 91, 65),
            "→" to byteArrayOf(27, 91, 67),
            "home" to byteArrayOf(27, 91, 72),
            "end" to byteArrayOf(27, 91, 70),
            "pgup" to byteArrayOf(27, 91, 53, 126),
            "pgdn" to byteArrayOf(27, 91, 54, 126),
            "|" to byteArrayOf(124),          // 竖线在很多手机输入法里要翻两页
            "/" to byteArrayOf(47),
            "~" to byteArrayOf(126),
        ).forEach { (label, bytes) -> Cap(label, false) { send(bytes) } }
    }
}

@Composable
private fun Cap(label: String, on: Boolean, onTap: () -> Unit) {
    Surface(
        color = if (on) CopperContainer else SurfaceContainerHigh,
        shape = Pill,
        modifier = Modifier.height(38.dp).clickable(onClick = onTap),
    ) {
        Box(Modifier.padding(horizontal = 13.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (on) OnCopperContainer else OnSurfaceVariant,
            )
        }
    }
}
