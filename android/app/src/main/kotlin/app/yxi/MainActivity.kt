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
import app.yxi.ui.theme.YxiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = HostStore(applicationContext)
        val keys = KeyManager(applicationContext)

        setContent {
            YxiTheme {
                // 先用最朴素的状态导航。会话看板（G4）落地时再看要不要上 navigation
                var open by remember { mutableStateOf<Host?>(null) }
                BackHandler(enabled = open != null) { open = null }

                Scaffold { p ->
                    val m = Modifier.padding(p)
                    val h = open
                    if (h == null) {
                        HostsScreen(store, keys, onOpen = { open = it }, modifier = m)
                    } else {
                        TerminalScreen(store = store, keys = keys, host = h, attachTo = null, modifier = m)
                    }
                }
            }
        }
    }
}
