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
internal suspend fun runRewindCommand(ssh: SshSession, command: String, stdin: ByteArray? = null): String {
    // Print-mode CLIs may also inspect stdin even when a prompt argument exists.
    // Supply EOF explicitly; keep the same tool PATH prefix as SshSession.exec.
    val channel = withContext(NonCancellable) {
        ssh.openExecStream("( export PATH=\"\$HOME/.local/bin:\$PATH\"; $command ) " +
            (if (stdin == null) "</dev/null " else "") + "2>&1")
    }
    try {
        return coroutineScope {
            val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { channel.close() }
            }
            val writer = stdin?.let { bytes -> launch(Dispatchers.IO) {
                check(channel.write(bytes)) { "回退输入传输失败" }
                check(channel.finishInput()) { "无法完成回退输入传输" }
            } }
            try {
                val result = withContext(Dispatchers.IO) {
                    if (stdin == null) readRewindOutput(channel.output) else readStructuredRewindOutput(channel.output)
                }
                writer?.join()
                result
            }
            finally { closer.cancel() }
        }
    } finally { channel.close() }
}

/** Verbose native events can echo large images. Drain them without retaining them as the result. */
internal fun readStructuredRewindOutput(input: InputStream, lineLimit: Int = 32 * 1024 * 1024): String {
    require(lineLimit > 0)
    var result: String? = null
    var receipt: String? = null
    var diagnostic: String? = null
    val line = ByteArrayOutputStream()
    fun accept() {
        val text = line.toString(Charsets.UTF_8.name()).trimEnd('\r')
        line.reset()
        if (text.startsWith(app.yxi.agent.Rewind.TAG + ":rc=")) receipt = text
        else if (text.startsWith("{")) {
            val event = runCatching { org.json.JSONObject(text) }.getOrNull()
            if (event?.optString("type") == "result") {
                check(text.length <= 8 * 1024 * 1024) { "回退结果超过读取上限" }
                result = text
            }
        } else if (text.isNotBlank()) diagnostic = text.take(400)
    }
    val buffered = input.buffered()
    while (true) {
        val byte = buffered.read()
        if (byte < 0) break
        if (byte == 10) accept() else {
            check(line.size() < lineLimit) { "回退事件超过读取上限" }
            line.write(byte)
        }
    }
    if (line.size() > 0) accept()
    return listOfNotNull(result ?: diagnostic, receipt).joinToString("\n")
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
