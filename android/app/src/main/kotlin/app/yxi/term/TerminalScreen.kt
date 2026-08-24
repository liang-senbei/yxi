package app.yxi.term

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator

/**
 * 真终端的**画面**：ConnectBot `termlib` 的 Compose 终端控件。
 *
 * ⚠️ **`keyboardEnabled` 必须显式传 `true`。** 它默认是 `false`，而控件内部
 * `AndroidView { ImeInputView(...) }` 的创建就包在这个标志里 ——
 * **默认配置下整条 IME 通路根本不存在**，软键盘不弹、中文更打不进去。
 * 这不是模拟器的毛病，是我们一直漏了一个参数。
 *
 * ⚠️ **compose mode 现在默认开着**（在 [app.yxi.ui.Workspace] 里启的）。
 * 非 compose 时 `EditorInfo.inputType` 是 `NO_SUGGESTIONS | VISIBLE_PASSWORD`
 * （不含 `TYPE_CLASS_TEXT`）—— 输入法见到这种「像密码框」的类型就**不给候选词**，
 * 中文根本打不出来。那个值写死在 `ImeInputView.onCreateInputConnection` 里，
 * 库没有参数能改，**compose mode 是唯一的拨杆**。
 * 代价是「攒一行、回车整行提交」；要逐键交互（vim / less / y-n）用工具条上那个键关掉。
 *
 * ⚠️ 「软键盘弹出时自动滚到底」**做不了**：滚动控制器只有 `TerminalWithAccessibility`
 * 才给，而那个函数和 `ScrollController` 在 Kotlin 层都是 **internal**，外部拿不到。
 * （从 JVM 字节码上看它们是 public —— 很容易误判，以 Kotlin 编译器为准。）
 *
 * ⚠️ 连接、shell 通道、读循环、tmux attach 全在 [app.yxi.ui.Workspace] 里：
 * 切到对话/文件模式时这个 composable 会被销毁，而**终端不能因此断线**。
 */
@Composable
fun TerminalView(
    emulator: TerminalEmulator,
    focus: FocusRequester,
    modifiers: StickyModifiers,
    showKeyboard: Boolean,
    onKeyboardVisible: (Boolean) -> Unit,
    onComposeController: (ComposeController) -> Unit,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Terminal(
            terminalEmulator = emulator,
            modifier = Modifier.fillMaxSize(),
            backgroundColor = bg,
            foregroundColor = fg,
            keyboardEnabled = true,          // ⚠️ 见上：不传 true 就没有 IME
            showSoftKeyboard = showKeyboard,
            focusRequester = focus,
            modifierManager = modifiers,
            onImeVisibilityChanged = onKeyboardVisible,
            onComposeControllerAvailable = onComposeController,
        )
    }
}
