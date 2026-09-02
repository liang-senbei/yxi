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
import app.yxi.ui.t
import app.yxi.ui.Workspace
import app.yxi.ui.theme.YxiTheme

/** 底部导航的三格。⚠️ 只有 app 级的平级目的地能进来（决策 D22）。 */
private enum class Tab(private val zh: String, val icon: String) {
    Sessions("会话", "◫"), Hosts("主机", "▤"), Config("配置", "❖"), Settings("设置", "⚙");

    // ⚠️ **label 必须是 get() 而不是构造参数。** enum 常量的参数在**类初始化时求值一次**，
    // 之后换语言它不会跟着变 —— 现象是底部导航栏 / 模式切换条永远停在启动时那种语言，
    // 而同一屏别的字都变了。get() 每次读都重新查表，还能被 Compose 当成状态读取。
    val label: String get() = t(zh)
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
        app.yxi.ui.I18n.load(this)
        app.yxi.ui.Skin.load(this)
        readJump(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

        app.yxi.watch.EventService.sync(this, store.hosts.value.any { it.watch })

        setContent {
            androidx.compose.foundation.layout.Box {

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
                // ⚠️ **第二条：给工作区预热。** 实测点开一个会话要新建 2 次 SSH 认证，
                // 回环上就是「1 秒空屏、2 秒才出内容」，手机上更久（#86）。
                // 提前建好，点进去直接用。
                // **刻意不复用上面那条** —— 终端通道出事会把看板一起拖死（#16）。
                // 代价是每台主机多一条闲着的连接，心跳 15 秒一次，可以接受。
                val warm = rememberHostSession(store, keys, host)
                // ⚠️ **会话列表也挂在这一层。** 跟连接同一个理由：存在 SessionsScreen 里的话，
                // 切走再切回来是空列表，要等一次往返才有内容 —— 那一下就是「骨架屏闪光」。
                var sessionList by remember(host?.id) {
                    mutableStateOf<List<app.yxi.agent.Session>>(emptyList())
                }

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
                        // ⚠️ **必须按目标 key 一下。** [Workspace] 里的 `sessionName` / `mode`
                        // 都是 `remember(host.id)` —— 同一台主机上换个会话，key 没变，
                        // 那两个状态原样留着。后果：**工作区已经开着的时候点通知跳会话，
                        // 界面纹丝不动**（还停在上一个会话、上一个模式）。
                        // 只有从标签页进去（work 从 null 变过来）才碰巧是对的。
                        androidx.compose.runtime.key(w.host.id, w.session, w.cwd, w.mode) {
                            Workspace(
                                store, keys, w.host, w.session, w.cwd, w.mode,
                                preconnected = warm.session,
                                modifier = Modifier.padding(p),
                            )
                        }
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
                            EmptyHint(t("还没有主机"), t("去「主机」那一栏加一台"), m)
                        } else {
                            SessionsScreen(
                                store, keys, host,
                                ssh = shared.session,
                                sessions = sessionList,
                                onSessions = { sessionList = it },
                                connectError = shared.error,
                                onRetry = shared.retry,
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
                        Tab.Config -> if (host == null) {
                            EmptyHint(t("还没有主机"), t("去「主机」那一栏加一台"), m)
                        } else {
                            app.yxi.ui.ConfigScreen(shared.session, host, hosts,
                                onPickHost = { picked -> hostId = picked.id }, modifier = m)
                        }
                        Tab.Settings -> SettingsScreen(store, keys, host, shared.session, shared.error, modifier = m)
                    }
                }
            }
        
            // 冷启动的开屏动效（实验室里挑中的那个）盖在最上面，播完让开
            YxiTheme { app.yxi.ui.splash.SplashGate {} }
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
