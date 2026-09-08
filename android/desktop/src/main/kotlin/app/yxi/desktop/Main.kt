package app.yxi.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

/** Yxi 桌面版入口。第一步只是一个能跑起来的窗口；主机 / 会话 / 对话 / 终端逐个往里搬。 */
fun main(args: Array<String>) = application {
    // --smoke：开窗口 3 秒就退并打印 smoke ok —— CI / Mac 上证明 Compose + Skia 在那个平台起得来（application 退出时 exitProcess(0)）
    if ("--smoke" in args) LaunchedEffect(Unit) { delay(3000); println("smoke ok"); exitApplication() }
    // 记住上次的窗口大小 / 位置（Store.dir/window.json）；主题跟系统（Claude Desktop 也是跟系统）
    val state = rememberWindowState(width = 1200.dp, height = 800.dp).also { Store.loadWindow(it) }
    Window(
        onCloseRequest = { Store.saveWindow(state); exitApplication() },
        title = "Yxi", state = state, icon = painterResource("icon.png"),
    ) {
        window.minimumSize = java.awt.Dimension(800, 520)
        YxiTheme {
            App()
        }
    }
}
