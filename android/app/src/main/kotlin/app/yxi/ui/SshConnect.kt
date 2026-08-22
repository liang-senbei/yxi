package app.yxi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import app.yxi.ssh.Host
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
 * 抽出来共用，因为**每个要连 SSH 的界面都需要它**（终端、会话看板、将来的文件模式）。
 * 各自实现一遍的下场我已经踩过：会话看板忘了传 prompt，于是所有连接都被
 * 「reject HostKey」挡掉——而这个失败长得**跟真的中间人攻击一模一样**，极易误判。
 */
class Connector(
    val session: SshSession,
    val known: KnownHosts,
)

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
): () -> Connector? {
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
        {
            val cfg = store.configFor(host, keys)
            if (cfg == null) null
            else {
                val known = KnownHosts(store, host.id, object : TrustPrompt {
                    override fun confirmNewHost(host: String, keyType: String, fingerprint: String): Boolean {
                        val done = CompletableDeferred<Boolean>()
                        // jsch 在自己的线程上同步调用，这里把问题抛给 UI 再阻塞等答复
                        ask = Ask(host, fingerprint) { done.complete(it) }
                        return runBlocking { done.await() }
                    }
                })
                Connector(SshSession(cfg, known), known)
            }
        }
    }
}
