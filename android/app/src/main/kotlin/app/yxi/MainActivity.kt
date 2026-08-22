package app.yxi

import android.Manifest
import android.content.Intent as AndroidIntent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
import app.yxi.ui.HostsScreen
import app.yxi.ui.Mode
import app.yxi.ui.SessionsScreen
import app.yxi.ui.Workspace
import app.yxi.ui.theme.YxiTheme

/**
 * 三层：**主机列表 → 会话看板 → 工作区**。返回键逐层退。
 *
 * 终端 / 对话 / 文件不是三个页面，而是**工作区里的三种模式** —— 它们共用同一条
 * SSH 连接，切换只换画面不断连（见 [Workspace]）。
 */
private sealed interface Nav {
    data object Hosts : Nav
    data class Sessions(val host: Host) : Nav
    /** [session] 为 null = 不针对某个 tmux 会话（从主机层直接进终端或文件） */
    data class Work(val host: Host, val session: String?, val cwd: String, val mode: Mode?) : Nav
}

class MainActivity : ComponentActivity() {

    /** 点通知要跳到哪个会话。`singleTask` 下再次点通知走 [onNewIntent]，所以做成可变的 */
    private val jump = mutableStateOf<Triple<String, String, String>?>(null)

    private val askNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒了就静悄悄，不纠缠 */ }

    override fun onNewIntent(intent: AndroidIntent) {
        super.onNewIntent(intent)
        readJump(intent)
    }

    private fun readJump(i: AndroidIntent?) {
        val host = i?.getStringExtra("hostId") ?: return
        jump.value = Triple(host, i.getStringExtra("session").orEmpty(), i.getStringExtra("cwd").orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = HostStore(applicationContext)
        val keys = KeyManager(applicationContext)
        readJump(intent)

        // ⚠️ 没有这个权限，前台服务照跑但**一条通知都发不出来** —— 而那正是它唯一的用处
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

        app.yxi.watch.EventService.sync(this, store.hosts.value.any { it.watch })

        setContent {
            YxiTheme {
                var nav by remember { mutableStateOf<Nav>(Nav.Hosts) }

                // 点通知 → 直达那个会话的对话界面
                val target by jump
                LaunchedEffect(target) {
                    val (hostId, session, cwd) = target ?: return@LaunchedEffect
                    val h = store.hosts.value.firstOrNull { it.id == hostId } ?: return@LaunchedEffect
                    nav = Nav.Work(h, session.ifBlank { null }, cwd.ifBlank { "." }, Mode.Chat)
                    jump.value = null
                }
                BackHandler(enabled = nav !is Nav.Hosts) {
                    nav = when (val n = nav) {
                        is Nav.Work -> Nav.Sessions(n.host)
                        else -> Nav.Hosts
                    }
                }
                Scaffold { p ->
                    val m = Modifier.padding(p)
                    when (val n = nav) {
                        is Nav.Hosts -> HostsScreen(store, keys, onOpen = { nav = Nav.Sessions(it) }, modifier = m)
                        is Nav.Sessions -> SessionsScreen(
                            store, keys, n.host,
                            onOpenTerminal = { target, cwd -> nav = Nav.Work(n.host, target, cwd, Mode.Terminal) },
                            // 点卡片 = 「打开这个会话」，用它上次的偏好；不是「我要对话模式」
                            onOpenChat = { name, cwd -> nav = Nav.Work(n.host, name, cwd, null) },
                            onOpenFiles = { nav = Nav.Work(n.host, null, ".", Mode.Files) },
                            modifier = m,
                        )
                        is Nav.Work -> Workspace(store, keys, n.host, n.session, n.cwd, n.mode, modifier = m)
                    }
                }
            }
        }
    }
}
