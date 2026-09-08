package app.yxi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 桌面版的总布局（像 Claude Desktop：左边栏 = 主机 + 会话列表，右边 = 当前会话的对话 / 终端），标题栏在外面（Main.kt）。
 * 各面板分文件：HostsPane（主机）、SessionsPane（会话）、ChatPane（对话）、TermPane（终端）；设置 / 快捷键表两个弹窗也挂在这。
 */
@Composable
fun App(state: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 窗口 < 700dp 自动收起侧栏（Claude 的 narrowViewportMaxWidth）；变宽只把自动收起的还回去，用户自己 Ctrl+B 关掉的不动
        val narrow = maxWidth < 700.dp
        val wasOpen = remember { mutableStateOf(true) }
        // Ctrl+N / 托盘「新建会话」时侧栏若收着，先展开——「新建会话」弹窗住在 Sidebar 里，收起时它根本没组合出来
        LaunchedEffect(state.newSessionRequest) { if (state.newSessionRequest > 0 && !state.sidebarOpen) state.sidebarOpen = true }
        LaunchedEffect(narrow) { if (narrow) { wasOpen.value = state.sidebarOpen; state.sidebarOpen = false } else if (wasOpen.value) state.sidebarOpen = true }
        Row(Modifier.fillMaxSize()) {
            if (state.sidebarOpen) { Sidebar(state, Modifier.width(288.dp).fillMaxHeight()); VerticalDivider() }
            Column(Modifier.fillMaxSize()) {
                val conn = state.conn; val sess = state.session
                if (conn == null || sess == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("左边选一台主机，连上后选一个会话") }
                } else {
                    TabRow(selectedTabIndex = state.tab) {
                        Tab(selected = state.tab == 0, onClick = { state.tab = 0 }, text = { Text("对话") })
                        Tab(selected = state.tab == 1, onClick = { state.tab = 1 }, text = { Text("终端") })
                    }
                    if (state.tab == 0) ChatPane(conn, sess) else TermPane(conn, sess)
                }
            }
        }
    }
    if (state.showSettings) SettingsDialog(state)
    if (state.showShortcuts) ShortcutsDialog(state)
}
