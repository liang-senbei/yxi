package app.yxi.desktop

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session

/**
 * 桌面版的总布局（像 Claude Desktop：左边栏 = 主机 + 会话列表，右边 = 当前会话的对话 / 终端）。
 * 各面板分文件：HostsPane（主机）、SessionsPane（会话）、ChatPane（对话）、TermPane（终端）。
 */
class AppState {
    var conn by mutableStateOf<Conn?>(null)          // 当前连着的主机（一次只连一台；ponytail：多主机同时连以后再说）
    var session by mutableStateOf<Session?>(null)    // 当前选中的 cc 会话
    var tab by mutableStateOf(0)                     // 0 对话 1 终端
}

@Composable
fun App() {
    val state = remember { AppState() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            HostsPane(state)
            state.conn?.let { SessionsPane(state, it) }
        }
        VerticalDivider()
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
