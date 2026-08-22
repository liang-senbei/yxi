package app.yxi.term

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator

/**
 * 真终端的**画面**：ConnectBot `termlib` 的 Compose 终端控件。
 *
 * ⚠️ **这里只负责画。** 连接、shell 通道、读循环、tmux attach 全都在
 * [app.yxi.ui.Workspace] 里 —— 因为切换到对话/文件模式时这个 composable 会被销毁，
 * 而**终端不能因此断线**（那是「切换不断连」的全部意义）。
 * 仿真器活在上层，切回来时原样还在：滚动位置、半行没敲完的命令都还在。
 *
 * 选 termlib 的理由（PRD 附录 B）：Maven Central 拿得到、Apache-2.0、Compose 原生、
 * 自带 IME 处理（中文输入那个坑）、无障碍、选区、URL 检测，四个 ABI 预编译好。
 */
@Composable
fun TerminalView(
    emulator: TerminalEmulator,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Terminal(
            terminalEmulator = emulator,
            modifier = Modifier.fillMaxSize(),
            focusRequester = focus,
        )
    }
}
