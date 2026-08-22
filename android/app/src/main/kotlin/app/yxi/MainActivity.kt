package app.yxi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.term.TerminalScreen
import app.yxi.ui.HostsScreen
import app.yxi.ui.SessionsScreen
import app.yxi.ui.theme.YxiTheme

/** 主机列表 → 会话看板 → {终端 | 对话 | 文件}。返回键逐层退。G8 会把后三者做成同层切换。 */
private sealed interface Nav {
    data object Hosts : Nav
    data class Sessions(val host: Host) : Nav
    data class Terminal(val host: Host, val attachTo: String?) : Nav
    data class Chat(val host: Host, val session: String, val cwd: String) : Nav
    data class Files(val host: Host, val dir: String) : Nav
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = HostStore(applicationContext)
        val keys = KeyManager(applicationContext)

        setContent {
            YxiTheme {
                var nav by remember { mutableStateOf<Nav>(Nav.Hosts) }
                BackHandler(enabled = nav !is Nav.Hosts) {
                    nav = when (val n = nav) {
                        is Nav.Terminal -> Nav.Sessions(n.host)
                        is Nav.Chat -> Nav.Sessions(n.host)
                        is Nav.Files -> Nav.Sessions(n.host)
                        else -> Nav.Hosts
                    }
                }
                Scaffold { p ->
                    val m = Modifier.padding(p)
                    when (val n = nav) {
                        is Nav.Hosts -> HostsScreen(store, keys, onOpen = { nav = Nav.Sessions(it) }, modifier = m)
                        is Nav.Sessions -> SessionsScreen(
                            store, keys, n.host,
                            onOpenTerminal = { target -> nav = Nav.Terminal(n.host, target) },
                            onOpenChat = { name, cwd -> nav = Nav.Chat(n.host, name, cwd) },
                            onOpenFiles = { nav = Nav.Files(n.host, ".") },
                            modifier = m,
                        )
                        is Nav.Chat -> app.yxi.ui.ChatScreen(
                            store, keys, n.host, n.session, n.cwd,
                            onOpenFiles = { nav = Nav.Files(n.host, n.cwd) },   // 起点就是这个会话的 cwd
                            modifier = m,
                        )
                        is Nav.Files -> app.yxi.ui.FilesScreen(store, keys, n.host, n.dir, modifier = m)
                        is Nav.Terminal -> TerminalScreen(store, keys, n.host, n.attachTo, modifier = m)
                    }
                }
            }
        }
    }
}
