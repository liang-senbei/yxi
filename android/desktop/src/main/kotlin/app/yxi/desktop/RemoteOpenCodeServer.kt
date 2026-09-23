package app.yxi.desktop

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Dedicated SSH exec channel and forwarding lease; never attaches to an existing server. */
internal class RemoteOpenCodeServer private constructor(private val channel: SshSession.Shell,
    private val forward: SshSession.PreviewForward, val client: OpenCodeClient, val directory: String, val runtimeHome: String, internal val processId: Long) : AutoCloseable {
    private val closed = AtomicBoolean()
    val alive get() = !closed.get() && channel.isConnected && forward.active
    override fun close() { if (closed.compareAndSet(false, true)) { forward.close(); channel.close() } }

    companion object {
        suspend fun start(ssh: SshSession, directory: String): RemoteOpenCodeServer {
            require(directory.startsWith('/') && directory.none { it < ' ' })
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            var channel: SshSession.Shell? = null
            var forward: SshSession.PreviewForward? = null
            try {
                return withTimeout(30_000) {
                    val stream = ssh.openExecStream(RunnerCatalog.resolveCommand("opencode") + "\n" +
                        "[ -f \"\$bin\" ] && [ -x \"\$bin\" ] || exit 127\n" +
                        "exec python3 -c ${Shell.q(supervisor)} \"\$bin\" ${Shell.q(directory)}")
                    channel = stream
                    val port = CompletableDeferred<Int>()
                    val identity = CompletableDeferred<JSONObject>()
                    thread(name = "yxi-remote-opencode-output", isDaemon = true) {
                        try {
                            stream.output.reader(Charsets.UTF_8).use { reader ->
                                val line = StringBuilder(); var overflow = false
                                while (true) {
                                    val ch = reader.read(); if (ch < 0) break
                                    if (ch == 10) {
                                        if (!overflow) {
                                            val text = line.toString().trimEnd('\r')
                                            if (text.startsWith("YXI_OPENCODE_STARTED:")) identity.complete(JSONObject(text.substringAfter(':')))
                                            LocalOpenCodeServer.listeningPort(text)?.let { port.complete(it) }
                                        }
                                        line.setLength(0); overflow = false
                                    } else if (line.length < 4096) line.append(ch.toChar()) else overflow = true
                                }
                            }
                            error("OpenCode 远端服务已退出")
                        } catch (e: Exception) { identity.completeExceptionally(e); port.completeExceptionally(e) }
                    }
                    check(stream.write(JSONObject().put("password", password).toString() + "\n")) { "未能发送服务启动配置" }
                    val owner = identity.await()
                    val cwd = owner.getString("directory")
                    val pid = owner.getLong("pid")
                    check(cwd.startsWith('/') && cwd.none { it < ' ' } && pid > 0)
                    val lease = ssh.forwardPreview(port.await()); forward = lease
                    val client = OpenCodeClient(lease.localPort, password, cwd)
                    val health = client.health()
                    check(stream.isConnected && lease.active && health.optBoolean("healthy") && health.optString("version").isNotBlank()) { "OpenCode 远端健康检查未通过" }
                    RemoteOpenCodeServer(stream, lease, client, cwd, owner.getString("runtimeHome"), pid)
                }
            } catch (e: Exception) { forward?.close(); channel?.close(); throw e }
        }

        private val supervisor = """
import json, os, select, signal, subprocess, sys
process = None
def stop(*args):
    raise SystemExit(0)
signal.signal(signal.SIGHUP, stop)
signal.signal(signal.SIGTERM, stop)
try:
    raw = sys.stdin.buffer.readline(8193)
    if len(raw) > 8192: raise ValueError('invalid startup input')
    config = json.loads(raw)
    password = config['password']
    if not isinstance(password, str) or not 32 <= len(password) <= 128: raise ValueError('invalid startup input')
    os.chdir(sys.argv[2])
    env = os.environ.copy()
    env['OPENCODE_SERVER_USERNAME'] = 'opencode'
    env['OPENCODE_SERVER_PASSWORD'] = password
    process = subprocess.Popen([sys.argv[1], 'serve', '--hostname', '127.0.0.1', '--port', '0', '--no-mdns'],
        stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env, start_new_session=True)
    data_home = env.get('XDG_DATA_HOME') or os.path.join(os.path.expanduser('~'), '.local', 'share')
    print('YXI_OPENCODE_STARTED:' + json.dumps({'pid': process.pid, 'directory': os.getcwd(), 'runtimeHome': os.path.join(data_home, 'opencode')}), flush=True)
    while process.poll() is None:
        readable, _, _ = select.select([0], [], [], 0.2)
        if readable:
            os.read(0, 4096)
            break
finally:
    if process is not None and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try: process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
""".trimIndent()
    }
}
