package app.yxi

import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.platform.LocalContext
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
private enum class Tab(private val zh: String, val ico: app.yxi.ui.Ico) {
    Sessions("会话", app.yxi.ui.Ico.Chat), Hosts("主机", app.yxi.ui.Ico.Server),
    Config("配置", app.yxi.ui.Ico.Sliders),
    // ⚠️ 用户 2026-09-04：「设置改为我的（Profile）」—— 这一栏现在是个人主页：
    //    资料 + 会员 + 分组过的设置（学 QQ）。
    Settings("我的", app.yxi.ui.Ico.Person);

    // ⚠️ **label 必须是 get() 而不是构造参数。** enum 常量的参数在**类初始化时求值一次**，
    // 之后换语言它不会跟着变 —— 现象是底部导航栏 / 模式切换条永远停在启动时那种语言，
    // 而同一屏别的字都变了。get() 每次读都重新查表，还能被 Compose 当成状态读取。
    val label: String get() = t(zh)
}

/**
 * 盖在标签页之上的**整页**。加一页就往这儿加一个值，再去 MainActivity 那个 `when` 里加一支 ——
 * ⚠️ **没有第三处要同步**（这正是「点底部导航纹丝不动」那个 bug 复发三次的根）。
 */
private enum class Page { Member, Prefs, Tickets, Trend, Mail, Wish, Activity, Wallet, Shop }

/** 工作区。它是**盖在标签页之上的整屏**，不是第四个标签 —— 见 D22。 */
private data class Work(val host: Host, val session: String?, val cwd: String, val mode: Mode?)

class MainActivity : ComponentActivity() {

    private val jump = mutableStateOf<Triple<String, String, String>?>(null)

    private val askNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒了就静悄悄 */ }

    /** 浏览器登录完会跳回 `io.yxi.app://callback?code=…`（manifest 里那条 intent-filter） */
    private fun readAuth(i: AndroidIntent?) {
        i?.data?.let { if (it.scheme == "io.yxi.app") app.yxi.agent.Account.pendingCallback = it }
    }

    override fun onNewIntent(intent: AndroidIntent) {
        super.onNewIntent(intent); readJump(intent); readAuth(intent)
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
        app.yxi.agent.Tz.load(this)
        app.yxi.ui.Skin.load(this)
        app.yxi.agent.Account.load(this)
        // ⚠️ 只挡**多任务卡片**的系统快照（它把对话内容存进 /data/system_ce/，我们清不掉）。
        //    故意不加 FLAG_SECURE：用户录屏是我们主要的 bug 输入来源，加了他只会说「录不了」。
        if (Build.VERSION.SDK_INT >= 33) runCatching { setRecentsScreenshotEnabled(false) }
        readJump(intent)
        readAuth(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

        app.yxi.watch.EventService.sync(this, store.hosts.value.any { it.watch })

        setContent {
            // 已登录就在进 App 时拉一次资料。
            // ⚠️ 以前只有打开「会员中心」才拉 —— 结果刚登录完，「我的」和侧边栏都写着「未登录」。
            //    刷新令牌是串行的（见 Account.refreshLock），这里多一次调用不会并发轮换。
            LaunchedEffect(app.yxi.agent.Account.signedIn) {
                if (app.yxi.agent.Account.signedIn) {
                    app.yxi.agent.Account.refresh(this@MainActivity)
                    // 装扮归属也同步一次。⚠️ [app.yxi.ui.Cosmetics.refresh] **只有真拿到才覆盖缓存** ——
                    //    没网就原样保留，不然一次断网就把人的皮肤全下架。
                    app.yxi.ui.Cosmetics.refresh(this@MainActivity)
                }
            }
            // 登录回调：拿 code 换 token，成功了顺手把资料拉回来
            val authUri = app.yxi.agent.Account.pendingCallback
            LaunchedEffect(authUri) {
                val u = authUri ?: return@LaunchedEffect
                app.yxi.agent.Account.pendingCallback = null
                val err = app.yxi.agent.Account.finishLogin(this@MainActivity, u)
                if (err == null) app.yxi.agent.Account.refresh(this@MainActivity)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    err ?: t("登录好了"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
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

                /**
                 * 盖在主界面上的那一页。⚠️⚠️ **一个状态，不是一堆布尔量。**
                 *
                 * 原来是 7 个 `var xxx by remember { false }` + 一个手写的 `overlay =  a || b || …`
                 * + 一个手写的 `closeOverlays()`。**同一个 bug 因此复发了三次**
                 * （「进了某一页，点底部导航纹丝不动」）—— 每加一页要记得改三处，
                 * 漏一处就复发，而且漏的那一页自己看起来完全正常。
                 *
                 * 现在加一页 = 往 [Page] 里加一个枚举值 + 在下面 `when` 里加一支，**没有第二处要同步**：
                 * 「盖着东西没有」是 `page != null`，「收掉」是 `page = null`，编译器帮着数。
                 */
                var page by remember { mutableStateOf<Page?>(null) }
                // ⚠️ **「设置」和「我的」是两页**（用户 2026-09-04）：底部导航那一栏是「我的」（人），
                //    侧边栏最底下那颗齿轮打开的才是「设置」（App）。工单中心也从设置里搬出来单独一页。
                var editMe by remember { mutableStateOf(false) }
                val overlay = page != null
                BackHandler(enabled = work != null || page != null || tab != Tab.Sessions) {
                    when {
                        work != null -> work = null      // 工作区 → 回标签页
                        page != null -> page = null      // 任何一个整页 → 回去
                        else -> tab = Tab.Sessions       // 非默认标签 → 回会话
                    }
                }
                if (editMe) app.yxi.ui.MeDialog { editMe = false }
                // 临时会话：开成了直接跳进对话页；冷启动后第一次连上就把上次留下的收掉
                var tempDlg by remember { mutableStateOf(false) }
                if (tempDlg) host?.let { h ->
                    app.yxi.ui.TempSessionDialog(
                        shared.session, h.id,
                        onOpen = { name, cwd -> tempDlg = false; work = Work(h, name, cwd, Mode.Chat) },
                        onDismiss = { tempDlg = false },
                    )
                }
                LaunchedEffect(shared.session, host?.id) {
                    val s = shared.session ?: return@LaunchedEffect
                    val h = host ?: return@LaunchedEffect
                    app.yxi.agent.TempSessions.sweep(this@MainActivity, s, h.id)
                }

                // ── 侧边栏（学 Threads：左上角 ☰ 或从左边缘划，抽屉从左滑入，主页面被推向右）──
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()
                val drawerWidth = 300.dp
                val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
                val openDrawer = { drawerScope.launch { drawer.open() }; Unit }

                // ⚠️ 工作区**整屏**，没有底部栏：终端最缺竖向空间，
                // 而软键盘弹起时底部栏会和键盘工具条、系统手势条挤成四层（D22）
                // ⚠️ **但它要待在抽屉里面**：会话里也要能拉侧边栏（用户 2026-09-04）。
                //    边缘手势在会话里关掉 —— 那个动作归系统的「右滑返回」，抢了就两个都不好使。
                work?.let { w ->
                    ModalNavigationDrawer(
                        drawerState = drawer,
                        // ⚠️⚠️ **开着的时候必须放开手势，否则关不掉**（用户 2026-09-04 截图报的：
                        //    「在会话里点开侧边栏就关不掉，得退回首页才能关」）。
                        //    Material3 里**遮罩层的「点外面关闭」是跟 gesturesEnabled 绑在一起的** ——
                        //    一律 false 的话，点外面、往左滑，两条关闭路径同时没了，只剩返回键。
                        //    当初关掉是为了**开**：会话里从左边缘右滑归系统的「右滑返回」，抢了两个都不好使。
                        //    那条理由只针对「关着的时候」。所以按状态给：关着禁、开着放。
                        gesturesEnabled = drawer.isOpen,
                        scrimColor = Color.Black.copy(alpha = 0.12f),
                        drawerContent = {
                            ModalDrawerSheet(Modifier.width(drawerWidth), drawerShape = RoundedCornerShape(0.dp, 28.dp, 28.dp, 0.dp)) {
                                YxiDrawer(
                                    hosts = hosts, current = host,
                                    onPickHost = { hostId = it.id; work = null; tab = Tab.Sessions; drawerScope.launch { drawer.close() } },
                                    onTab = { tab = it; work = null; drawerScope.launch { drawer.close() } },
                                    onMember = { page = Page.Member; work = null; drawerScope.launch { drawer.close() } },
                                    onEditMe = { editMe = true; drawerScope.launch { drawer.close() } },
                                    onPrefs = { page = Page.Prefs; work = null; drawerScope.launch { drawer.close() } },
                                    onTickets = { page = Page.Tickets; work = null; drawerScope.launch { drawer.close() } },
                                    onTemp = { tempDlg = true; drawerScope.launch { drawer.close() } },
                                )
                            }
                        },
                    ) {
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
                                // ⚠️ 只吃底部的 inset，不吃状态栏那段（学 Gemini：状态栏跟 App 融为一体，光晕铺到最顶上，
                                //    内容滑到状态栏下面时用一层淡渐变压一下）。页眉自己 statusBarsPadding。
                                onMenu = openDrawer,
                                modifier = Modifier.padding(bottom = p.calculateBottomPadding()),
                            )
                        }
                    }
                    }   // ModalNavigationDrawer（工作区那层）
                    return@YxiTheme
                }

                ModalNavigationDrawer(
                    drawerState = drawer,
                    scrimColor = Color.Black.copy(alpha = 0.12f),
                    drawerContent = {
                        ModalDrawerSheet(Modifier.width(drawerWidth), drawerShape = RoundedCornerShape(0.dp, 28.dp, 28.dp, 0.dp)) {
                            YxiDrawer(
                                hosts = hosts, current = host,
                                onPickHost = { hostId = it.id; tab = Tab.Sessions; drawerScope.launch { drawer.close() } },
                                onTab = { tab = it; drawerScope.launch { drawer.close() } },
                                onMember = { page = Page.Member; drawerScope.launch { drawer.close() } },
                                onEditMe = { editMe = true; drawerScope.launch { drawer.close() } },
                                onPrefs = { page = Page.Prefs; drawerScope.launch { drawer.close() } },
                                onTickets = { page = Page.Tickets; drawerScope.launch { drawer.close() } },
                                onTemp = { tempDlg = true; drawerScope.launch { drawer.close() } },
                            )
                        }
                    },
                ) {
                // 主页面跟着抽屉一起被推开（Threads 那种「推」，不是盖在上面）：偏移 = 抽屉已滑出的宽度 × 0.85
                val push = drawer.currentOffset.let { if (it.isNaN()) -drawerWidthPx else it }
                Scaffold(
                    modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(((drawerWidthPx + push).coerceAtLeast(0f) * 0.85f).roundToInt(), 0) },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t && !overlay,
                                    // ⚠️⚠️ **点底部导航 = 无条件回到那个标签页。**
                                    //    盖着的整页收掉、工作区也退出 —— 用户 2026-09-05 第三次报
                                    //    「在某一页里点下面的导航不跳转」。收成一句赋值，不再有「漏了哪一个」。
                                    onClick = { page = null; work = null; tab = t },
                                    icon = { app.yxi.ui.YxiIcon(t.ico, size = 22.dp) },
                                    label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                    },
                ) { p ->
                    val m = Modifier.padding(p)
                    // ⚠️ 一页一支，编译器会替你数（[Page] 加了值不处理这里就报错）
                    when (page) {
                        Page.Member -> { app.yxi.ui.MemberScreen(onBack = { page = null }, modifier = m); return@Scaffold }
                        Page.Prefs -> {
                            SettingsScreen(
                                store, keys, host, shared.session, shared.error,
                                onMember = { page = Page.Member }, mine = false, modifier = m,
                            )
                            return@Scaffold
                        }
                        Page.Tickets -> { app.yxi.ui.TicketsScreen(modifier = m); return@Scaffold }
                        Page.Trend -> { app.yxi.ui.TrendScreen(shared.session, modifier = m); return@Scaffold }
                        Page.Mail -> { app.yxi.ui.MailScreen(modifier = m); return@Scaffold }
                        Page.Wallet -> { app.yxi.ui.WalletScreen(onShop = { page = Page.Shop }, onRedeem = { page = Page.Member }, modifier = m); return@Scaffold }
                        Page.Shop -> { app.yxi.ui.ShopScreen(onRedeem = { page = Page.Member }, modifier = m); return@Scaffold }
                        Page.Wish -> { app.yxi.ui.WishScreen(modifier = m); return@Scaffold }
                        Page.Activity -> { app.yxi.ui.ActivityScreen(modifier = m); return@Scaffold }
                        null -> Unit
                    }
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
                                onMenu = { drawerScope.launch { drawer.open() } },
                                modifier = m,
                            )
                        }
                        Tab.Hosts -> HostsScreen(
                            store, keys,
                            current = host?.id,
                            onOpen = { hostId = it.id; tab = Tab.Sessions },
                            modifier = m,
                        )
                        Tab.Config -> if (host == null) {
                            EmptyHint(t("还没有主机"), t("去「主机」那一栏加一台"), m)
                        } else {
                            app.yxi.ui.ConfigScreen(
                                shared.session, host, hosts,
                                // 「线路」那一栏要知道谁在干活才好排队切 —— 复用看板已经拉回来的这份，
                                // 不为它再起一次 SSH 往返（状态源见 SessionProbe，~/.claude/sessions/*.json）
                                sessions = sessionList,
                                onPickHost = { picked -> hostId = picked.id }, modifier = m,
                            )
                        }
                        Tab.Settings -> SettingsScreen(
                            store, keys, host, shared.session, shared.error,
                            onMember = { page = Page.Member }, mine = true,
                            onPrefs = { page = Page.Prefs }, onTickets = { page = Page.Tickets },
                            onTrend = { page = Page.Trend }, onMail = { page = Page.Mail }, onWallet = { page = Page.Wallet },
                            onWish = { page = Page.Wish }, onActivity = { page = Page.Activity }, modifier = m,
                        )
                    }
                }
                }   // ModalNavigationDrawer
            }
        
            // 没登录就把整个 App 挡住（用户 2026-09-04：「没登录的不允许使用」）。
            // ⚠️ 顺序：内容 → 登录门禁 → 开屏。开屏在最上面照播，播完落到登录页。
            YxiTheme { app.yxi.ui.LoginGate() }
            // 冷启动的开屏动效（按主题挑：深色显影 / 浅色随机翻面或聚合）盖在最上面，播完让开
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

/**
 * 侧边栏内容。**排布学 QQ**（用户 2026-09-04 给了张 QQ 的截图）：
 * 顶上是「我」（头像 / 昵称 / 个性签名，点开能改），中间是功能入口，**设置在最底下那一行**。
 *
 * ⚠️ 不做钱包、不做相册（用户明确说不要）。会员中心要做 —— pro / ultra 两档，见 [app.yxi.ui.MemberScreen]。
 */
@Composable
private fun YxiDrawer(
    hosts: List<Host>, current: Host?,
    onPickHost: (Host) -> Unit, onTab: (Tab) -> Unit, onMember: () -> Unit, onEditMe: () -> Unit,
    onPrefs: () -> Unit, onTickets: () -> Unit,
    /** 「临时会话」：在服务器 /tmp 里开一个不保存的对话（见 TempSessions） */
    onTemp: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val rev = app.yxi.ui.Me.rev.intValue                     // 改了昵称/头像要重画
    Column(Modifier.fillMaxSize().padding(0.dp, 26.dp, 0.dp, 10.dp)) {
        // ── 「我」：头像 + 昵称 + 个性签名（点整块进编辑）
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEditMe).padding(18.dp, 8.dp, 16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            app.yxi.ui.MeAvatar(56.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    app.yxi.ui.Me.name(ctx).ifBlank {
                        if (app.yxi.agent.Account.signedIn) t("点这里起个名") else t("点这里登录")
                    },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    app.yxi.ui.Me.sign(ctx).ifBlank { t("写句个性签名") },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 18.dp))
        // ── 主机切换
        Text(
            t("主机"), Modifier.padding(18.dp, 14.dp, 18.dp, 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
        )
        hosts.forEach { h ->
            val on = h.id == current?.id
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onPickHost(h) }.padding(12.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(app.yxi.ui.hostColor(h.id)))
                Text(h.alias, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (on) Text("✓", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 18.dp))
        Spacer(Modifier.height(6.dp))
        // ── 功能入口（QQ 那种：彩色细线图标 + 标题 + 右边一个 ›）
        DrawerRow(app.yxi.ui.Ico.Bolt, t("临时会话"), Color(0xFFE8912D), tail = t("不保存")) { onTemp() }
        DrawerRow(app.yxi.ui.Ico.Server, t("主机"), Color(0xFF4C8DF6)) { onTab(Tab.Hosts) }
        DrawerRow(app.yxi.ui.Ico.Sliders, t("配置"), Color(0xFF35B6A0)) { onTab(Tab.Config) }
        DrawerRow(
            app.yxi.ui.Ico.Crown, t("会员中心"), Color(0xFFE8912D),
            // 登录了就把档位摆出来，没登录写「未登录」—— 这一行是账号状态最显眼的地方。
            // ⚠️ 登录了但资料还没拉回来时写「已登录」，**不能写 Free**（那是猜的，猜错就是骗人），
            //    更不能写「未登录」（人明明登着）。
            tail = when {
                !app.yxi.agent.Account.signedIn -> t("未登录")
                else -> app.yxi.agent.Account.me?.tier?.name ?: t("已登录")
            },
            onClick = onMember,
        )
        // 工单中心 —— 你和开发之间唯一那条通道，别埋在设置第三层里
        DrawerRow(app.yxi.ui.Ico.Chat, t("工单中心"), Color(0xFF7A69E8)) { onTickets() }
        Spacer(Modifier.weight(1f))
        // ── 最底下那一行：设置 / 夜间（学 QQ）
        androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 18.dp))
        Row(
            Modifier.fillMaxWidth().padding(8.dp, 8.dp, 8.dp, 0.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BottomAction(app.yxi.ui.Ico.Gear, t("设置"), Modifier.weight(1f)) { onPrefs() }
            val dark = app.yxi.ui.Skin.style == app.yxi.ui.Skin.Style.Dark
            BottomAction(app.yxi.ui.Ico.Moon, if (dark) t("浅色") else t("夜间"), Modifier.weight(1f)) {
                app.yxi.ui.Skin.set(ctx, if (dark) app.yxi.ui.Skin.Style.Light else app.yxi.ui.Skin.Style.Dark)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                Text("Yxi", style = MaterialTheme.typography.labelLarge)
                Text(
                    "v" + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun DrawerRow(ico: app.yxi.ui.Ico, label: String, tint: Color, tail: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp, 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        app.yxi.ui.YxiIcon(ico, size = 22.dp, tint = tint)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (tail.isNotEmpty()) Text(
            tail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/** 抽屉最底下那排小按钮（图标在上、字在下），跟 QQ 的「设置 / 夜间」一个形制 */
@Composable
private fun BottomAction(ico: app.yxi.ui.Ico, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        app.yxi.ui.YxiIcon(ico, size = 21.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
