package app.yxi

import android.Manifest
import android.content.Intent as AndroidIntent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ui.HostsScreen
import app.yxi.ui.Mode
import app.yxi.ui.SessionsScreen
import app.yxi.ui.rememberHostSession
import app.yxi.ui.SettingsScreen
import app.yxi.ui.Workspace
import app.yxi.ui.theme.YxiTheme

/** 底部导航的三格。⚠️ 只有 app 级的平级目的地能进来（决策 D22）。 */
private enum class Tab(val label: String, val icon: String) {
    Sessions("会话", "◫"), Hosts("主机", "▤"), Settings("设置", "⚙")
}

/** 工作区。它是**盖在标签页之上的整屏**，不是第四个标签 —— 见 D22。 */
private data class Work(val host: Host, val session: String?, val cwd: String, val mode: Mode?)

class MainActivity : ComponentActivity() {

    private val jump = mutableStateOf<Triple<String, String, String>?>(null)

    private val askNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒了就静悄悄 */ }

    override fun onNewIntent(intent: AndroidIntent) {
        super.onNewIntent(intent); readJump(intent)
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
        val prefs = getSharedPreferences("yxi", MODE_PRIVATE)
        readJump(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

        app.yxi.watch.EventService.sync(this, store.hosts.value.any { it.watch })

        setContent {
            YxiTheme {
                val hosts by store.hosts.collectAsState()
                var tab by remember { mutableStateOf(Tab.Sessions) }
                var work by remember { mutableStateOf<Work?>(null) }
                // 「当前主机」是 app 级状态 —— 换主机不用退出去（D22）
                var hostId by remember { mutableStateOf(prefs.getString("host", null)) }
                val host = hosts.firstOrNull { it.id == hostId } ?: hosts.firstOrNull()
                LaunchedEffect(host?.id) { host?.id?.let { prefs.edit().putString("host", it).apply() } }

                // 点通知 → 直达那个会话
                val target by jump
                LaunchedEffect(target) {
                    val (hid, session, cwd) = target ?: return@LaunchedEffect
                    val h = hosts.firstOrNull { it.id == hid } ?: return@LaunchedEffect
                    hostId = hid
                    work = Work(h, session.ifBlank { null }, cwd.ifBlank { "." }, Mode.Chat)
                    jump.value = null
                }

                // ⚠️ **连接放在 tab 切换之上。** 放在 SessionsScreen 里的话，
                // 切到「设置」再切回「会话」时整个 composable 重建，
                // 连接跟着从头来一遍（TCP + 握手 + ed25519 认证，实测 ~3 秒）——
                // 用户的原话是「切一次就要重新连接一次，有一点麻烦」。
                // 跟 D21 里「换会话不重连」是同一条原则：连接跟着**主机**活，不跟着界面活。
                val shared = rememberHostSession(store, keys, host)

                BackHandler(enabled = work != null || tab != Tab.Sessions) {
                    when {
                        work != null -> work = null      // 工作区 → 回标签页
                        else -> tab = Tab.Sessions       // 非默认标签 → 回会话
                    }
                }

                // ⚠️ 工作区**整屏**，没有底部栏：终端最缺竖向空间，
                // 而软键盘弹起时底部栏会和键盘工具条、系统手势条挤成四层（D22）
                work?.let { w ->
                    Scaffold { p ->
                        Workspace(
                            store, keys, w.host, w.session, w.cwd, w.mode,
                            modifier = Modifier.padding(p),
                        )
                    }
                    return@YxiTheme
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Text(t.icon, style = MaterialTheme.typography.titleMedium) },
                                    label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                    },
                ) { p ->
                    val m = Modifier.padding(p)
                    when (tab) {
                        Tab.Sessions -> if (host == null) {
                            EmptyHint("还没有主机", "去「主机」那一栏加一台", m)
                        } else {
                            SessionsScreen(
                                store, keys, host,
                                ssh = shared.session,
                                connectError = shared.error,
                                hosts = hosts,
                                onPickHost = { picked -> hostId = picked.id },
                                onOpenTerminal = { sn, cwd -> work = Work(host, sn, cwd, Mode.Terminal) },
                                onOpenChat = { sn, cwd -> work = Work(host, sn, cwd, null) },
                                onOpenFiles = { work = Work(host, null, ".", Mode.Files) },
                                modifier = m,
                            )
                        }
                        Tab.Hosts -> HostsScreen(
                            store, keys,
                            onOpen = { hostId = it.id; tab = Tab.Sessions },
                            modifier = m,
                        )
                        Tab.Settings -> SettingsScreen(store, keys, host, shared.session, modifier = m)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, sub: String, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.then(Modifier.padding(40.dp)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = app.yxi.ui.theme.Dim)
        }
    }
}
