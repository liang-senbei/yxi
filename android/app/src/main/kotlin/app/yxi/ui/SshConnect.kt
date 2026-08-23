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
): Connect {
    var ask by remember { mutableStateOf<Ask?>(null) }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { a.answer(false); ask = null },
            title = { Text("第一次连这台主机") },
            text = {
                Text(
                    a.host + "\n\n指纹\n" + a.fingerprint +
                        "\n\n请核对它跟服务器上 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub " +
                        "的输出一致。不一致就别连。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton({ a.answer(true); ask = null }) { Text("指纹对得上，连") } },
            dismissButton = { TextButton({ a.answer(false); ask = null }) { Text("取消") } },
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
                return Connector(SshSession(cfg, known), known)
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
    if (known.changedDetected) return "⚠️ 主机指纹变了，已拒绝连接。真是重装了就把这台主机删掉重加。"
    var c: Throwable? = e
    while (c != null) {
        when (c) {
            is java.net.UnknownHostException -> {
                val h = c.message.orEmpty().substringBefore(':').trim()
                val bad = app.yxi.ssh.HostInput.suspiciousChar(h)
                return "地址解析不了：「$h」\n" +
                    // ⚠️ 只说「解析不了」等于没说 —— 用户看着那个地址觉得它是对的。
                    // 全角句点和半角句点长得几乎一样，必须把那个字符指出来。
                    if (bad != null) "里面有个连不上的字符 $bad —— 多半是中文输入法打的，删掉用英文键盘重打。"
                    else "这一栏要填 IP 或真实域名。手机上没有 ~/.ssh/config，" +
                        "SSH 别名（station 之类）在这儿用不了；也别带 http:// 或路径。"
            }
            is java.net.SocketTimeoutException, is java.net.ConnectException ->
                return "连不上 ${session.hostLabel}：${c.message}\n检查 IP、端口，以及服务器是否开着。"
            else -> Unit
        }
        c = c.cause.takeIf { it !== c }
    }
    val m = e.message.orEmpty()
    return when {
        "Auth fail" in m || "Auth cancel" in m -> "认证被拒：密码不对，或这台机器的 authorized_keys 里没有这把公钥。"
        "reject HostKey" in m -> "你取消了指纹确认，所以没连。"
        else -> "连不上：${e::class.simpleName}: ${e.message}"
    }
}
