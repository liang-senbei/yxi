package app.yxi.desktop

import app.yxi.ssh.SshSession
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Own one exec channel; never hold the host-wide command mutex while the model runs. */
internal suspend fun runRewindCommand(ssh: SshSession, command: String): String {
    val channel = withContext(NonCancellable) { ssh.openExecStream("( $command ) 2>&1") }
    try {
        return coroutineScope {
            val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { channel.close() }
            }
            try { withContext(Dispatchers.IO) { readRewindOutput(channel.output) } }
            finally { closer.cancel() }
        }
    } finally { channel.close() }
}

/** Drain all bytes so a large reply cannot block the remote process; retain bounded memory. */
internal fun readRewindOutput(input: InputStream, limit: Int = 8 * 1024 * 1024): String {
    require(limit > 0)
    val out = ByteArrayOutputStream(minOf(limit, 65536))
    val buffer = ByteArray(8192)
    var truncated = false
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        val retained = minOf(count, limit - out.size())
        out.write(buffer, 0, retained)
        if (retained < count) truncated = true
    }
    check(!truncated) { "回退结果超过读取上限，请核对恢复状态" }
    return out.toString(Charsets.UTF_8.name())
}
