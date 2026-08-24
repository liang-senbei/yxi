package app.yxi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import app.yxi.ssh.Host
import app.yxi.ssh.HostConfig
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.KnownHosts
import app.yxi.ssh.SshSession
import app.yxi.ssh.TrustPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * 连接一台主机，并在需要时弹出**首次信任确认**。
 *
 * 抽出来共用，因为**每个要连 SSH 的界面都需要它**（终端、会话看板、装公钥、将来的文件模式）。
 * 各自实现一遍的下场我已经踩过两次：忘了传 prompt，于是所有连接都被
 * 「reject HostKey」挡掉——而这个失败长得**跟真的中间人攻击一模一样**，极易误判。
 *
 * ⚠️ **所以 UI 层不许直接 `SshSession(...)`。** 想连就从这儿拿。
 * 认证方式不同（装公钥那次要用密码）不是绕开它的理由——传 [Connect.invoke] 的 auth 参数。
 */
class Connector(
    val session: SshSession,
    val known: KnownHosts,
)

/**
 * 建连接的入口。[auth] 传了就覆盖这台主机存着的认证方式，其余（指纹校验）一模一样。
 * 用普通 interface 而不是 `fun interface`：后者的抽象方法不许带默认值。
 */
interface Connect {
    operator fun invoke(auth: HostConfig.Auth? = null): Connector?
}

/** 弹窗状态：主机名、指纹、答复回调。 */
private data class Ask(val host: String, val fingerprint: String, val answer: (Boolean) -> Unit)

/**
 * 返回一个「建连接」的函数 + 一个要挂在界面上的弹窗。
 * 用法：
 * ```
 * val connect = rememberSshConnector(store, keys, host)
 * LaunchedEffect(Unit) { val c = connect() ?: return@LaunchedEffect; c.session.connect() }
 * ```
 */
@Composable
fun rememberSshConnector(
    store: HostStore,
    keys: KeyManager,
    host: Host,
    /** 心跳间隔，见 [SshSession] 的构造参数。常驻的连接要给大值 */
    aliveIntervalMs: Int = 2_000,
): Connect {
    var ask by remember { mutableStateOf<Ask?>(null) }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { a.answer(false); ask = null },
            title = { Text(t("第一次连这台主机")) },
            text = {
                Text(
                    a.host + t("\n\n指纹\n") + a.fingerprint +
                        t("\n\n请核对它跟服务器上 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub ") +
                        t("的输出一致。不一致就别连。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton({ a.answer(true); ask = null }) { Text(t("指纹对得上，连")) } },
            dismissButton = { TextButton({ a.answer(false); ask = null }) { Text(t("取消")) } },
        )
    }

    return remember(host.id) {
        object : Connect {
            override fun invoke(auth: HostConfig.Auth?): Connector? {
                val cfg = if (auth != null)
                    HostConfig(host.alias, host.hostname, host.port, host.username, auth)
                else store.configFor(host, keys) ?: return null

                val known = KnownHosts(store, host.id, object : TrustPrompt {
                    override fun confirmNewHost(host: String, keyType: String, fingerprint: String): Boolean {
                        val done = CompletableDeferred<Boolean>()
                        // jsch 在自己的线程上同步调用，这里把问题抛给 UI 再阻塞等答复
                        ask = Ask(host, fingerprint) { done.complete(it) }
                        return runBlocking { done.await() }
                    }
                })
                return Connector(SshSession(cfg, known, aliveIntervalMs), known)
            }
        }
    }
}

/**
 * 把 SSH 的异常翻成人话 —— **三个界面都要显示错误，翻译只该有一份。**
 * jsch 把真正的原因裹在 `JSchException` 里，光看 `message` 常常只有个类名。
 */
fun Connector.explain(e: Throwable): String {
    // ⚠️ 所有界面的连接失败都汇到这一个函数 —— 记日志就记在这儿，
    // 别处再记一遍只会漏。开发者模式靠它才有东西可看
    DevMode.logError("connect ${session.hostLabel}", e)
    if (known.changedDetected) return t("⚠️ 主机指纹变了，已拒绝连接。真是重装了就把这台主机删掉重加。")
    var c: Throwable? = e
    while (c != null) {
        when (c) {
            is java.net.UnknownHostException -> {
                val h = c.message.orEmpty().substringBefore(':').trim()
                val bad = app.yxi.ssh.HostInput.suspiciousChar(h)
                return t("地址解析不了：「%s」\n").format(h) +
                    // ⚠️ 只说「解析不了」等于没说 —— 用户看着那个地址觉得它是对的。
                    // 全角句点和半角句点长得几乎一样，必须把那个字符指出来。
                    if (bad != null) t("里面有个连不上的字符 %s —— 多半是中文输入法打的，删掉用英文键盘重打。").format(bad)
                    else t("这一栏要填 IP 或真实域名。手机上没有 ~/.ssh/config，") +
                        t("SSH 别名（station 之类）在这儿用不了；也别带 http:// 或路径。")
            }
            is java.net.SocketTimeoutException, is java.net.ConnectException ->
                return t("连不上 %s：%s\n检查 IP、端口，以及服务器是否开着。").format(session.hostLabel, c.message)
            else -> Unit
        }
        c = c.cause.takeIf { it !== c }
    }
    val m = e.message.orEmpty()
    return when {
        "Auth fail" in m || "Auth cancel" in m -> t("认证被拒：密码不对，或这台机器的 authorized_keys 里没有这把公钥。")
        "reject HostKey" in m -> t("你取消了指纹确认，所以没连。")
        else -> t("连不上：%s: %s").format(e::class.simpleName, e.message)
    }
}

/**
 * 一台主机的共享连接。
 *
 * @param session 连上了就非 null
 * @param error   连不上时的人话原因
 * @param retry   **手动重连**。自动重连是指数退避的，最长要等 15 秒；
 *                用户刚把 WiFi 切回来时不该干等 —— 给他一个按钮立刻重试，
 *                顺便把退避重新从 1 秒算起。
 */
class HostSession(
    val session: SshSession?,
    val error: String?,
    val retry: () -> Unit = {},
)

/**
 * 按**主机**持有一条连接，跨界面共用。
 *
 * ⚠️ **必须挂在 tab 切换之上。** 挂在某个页面里的话，切走再切回来
 * 那个页面重建，连接就跟着重来一遍 —— TCP + 握手 + ed25519 认证实测 ~3 秒，
 * 每切一次 tab 等三秒。连接要跟着**主机**活，不跟着界面活。
 *
 * 换主机时把旧的断掉；页面级的用法（工作区、装公钥）仍然各自建各自的，
 * 那些是**要**独立生命周期的。
 */
@Composable
fun rememberHostSession(store: HostStore, keys: KeyManager, host: Host?): HostSession {
    if (host == null) return HostSession(null, null)
    // 15 秒 × 2 = 30 秒才判死。这条是常驻的、大多时候空闲，
    // 用终端那套 4 秒的标准会被手机的调度抖动打死
    val connect = rememberSshConnector(store, keys, host, aliveIntervalMs = 15_000)
    var session by remember(host.id) { mutableStateOf<SshSession?>(null) }
    var error by remember(host.id) { mutableStateOf<String?>(null) }
    // 手动重连 = 换一代，让下面那个 effect 整个重来（退避也跟着归零）
    var generation by remember(host.id) { mutableIntStateOf(0) }

    LaunchedEffect(host.id, generation) {
        var wait = 1_000L
        while (true) {
            val c = connect()
            if (c == null) { error = t("这台主机还没有可用的认证方式"); return@LaunchedEffect }
            val err = runCatching { c.session.connect() }.exceptionOrNull()
            if (err == null) {
                session = c.session; error = null; wait = 1_000L
                // ⚠️ **连上不是终点。** 原来这里直接 return —— 之后连接掉了
                // 就再也没人管：看板一直显示「刷新失败」，而 ssh 还非 null，
                // 连重连按钮都不出现，用户只能杀掉 App 重开。
                // 现在守着它，断了就回到上面重连。
                while (c.session.isAlive) kotlinx.coroutines.delay(3_000)
                session = null
                error = t("连接断了，正在重连…")
                app.yxi.ui.DevMode.log("host", t("连接掉了，自动重连"))
                continue
            }

            // ⚠️ **取消不是失败。** 这是第五处同样的错（见 TROUBLESHOOTING #78）：
            // 界面重组 / 换主机时这个 effect 被取消，挂起点抛 CancellationException，
            // 被 runCatching 一起接住 → explain 兜底返回「连不上：JobCancellationException」，
            // 而 error 是记住的状态，那句话就永远钉在界面上了。
            if (err is kotlinx.coroutines.CancellationException) throw err

            // ⚠️ **失败要自己重试。** 原来失败一次就把错误钉住、再也不动 ——
            // 手机上网络本来就时断时续（切基站、锁屏、地铁），
            // 「连一次不成就永久显示连不上」等于把一次抖动变成一次故障。
            // 指纹变了不重试：那不是网络问题，重试只会一遍遍撞同一堵墙。
            if (c.known.changedDetected) { error = c.explain(err); return@LaunchedEffect }
            error = c.explain(err)
            app.yxi.ui.DevMode.log("host", "连接失败，${wait}ms 后重试")
            kotlinx.coroutines.delay(wait)
            wait = (wait * 2).coerceAtMost(15_000)
        }
    }
    // 换主机 / 界面销毁时收掉，别留着一条没人用的连接
    DisposableEffect(host.id) {
        onDispose { session?.let { s -> runCatching { s.disconnect() } } }
    }
    return HostSession(session, error, retry = { session = null; error = null; generation++ })
}
