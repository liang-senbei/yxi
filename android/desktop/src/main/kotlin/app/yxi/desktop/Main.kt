package app.yxi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Yxi 桌面版入口。第一步只是一个能跑起来的窗口；主机 / 会话 / 对话 / 终端逐个往里搬。 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Yxi", state = rememberWindowState(width = 1200.dp, height = 800.dp)) {
        MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Yxi Desktop · 骨架已起，主机 / 会话 / 对话 / 终端逐个接入") }
        }
    }
}
