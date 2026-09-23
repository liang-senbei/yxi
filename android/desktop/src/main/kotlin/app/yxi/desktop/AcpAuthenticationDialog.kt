package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.jediterm.terminal.ui.JediTermWidget
import kotlinx.coroutines.CancellationException

@Composable internal fun AcpAuthenticationDialog(plan: AcpTerminalAuthPlan, finish: (Int?, String?) -> Unit) {
    var terminal by remember(plan) { mutableStateOf<LocalAuthenticationTerminal?>(null) }
    var widget by remember(plan) { mutableStateOf<JediTermWidget?>(null) }
    DisposableEffect(plan) { onDispose { widget?.close(); terminal?.close() } }
    DialogWindow(onCloseRequest = { finish(null, "认证已取消") }, title = "运行器认证", state = rememberDialogState(width = 920.dp, height = 640.dp)) {
        Surface { Column(Modifier.fillMaxSize()) {
            Text("请在终端中完成运行器配置。关闭窗口会取消本次认证。", Modifier.padding(12.dp))
            val current = widget
            if (current == null) Text("正在打开本地终端…", Modifier.padding(16.dp))
            else SwingPanel(factory = { current }, modifier = Modifier.weight(1f).fillMaxWidth())
        } }
        LaunchedEffect(plan) {
            try {
                val owned = LocalAuthenticationTerminal.start(plan)
                terminal = owned
                widget = JediTermWidget(120, 30, TermSettings()).apply { setTtyConnector(owned); start() }
                finish(owned.awaitExit(), null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { finish(null, e.message ?: "无法打开认证终端") }
        }
    }
}
