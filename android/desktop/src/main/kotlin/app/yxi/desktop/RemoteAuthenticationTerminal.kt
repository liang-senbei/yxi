package app.yxi.desktop

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.Questioner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader

internal class RemoteAuthenticationPlan(val ssh: SshSession, val engine: String, val directory: String, val method: JSONObject) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val channel = java.util.concurrent.atomic.AtomicReference<SshSession.Shell?>()
    internal fun own(value: SshSession.Shell) {
        channel.set(value)
        if (closed.get()) { channel.getAndSet(null)?.close(); error("服务器认证已取消") }
    }
    override fun close() { closed.set(true); channel.getAndSet(null)?.close() }
}

internal class RemoteAuthenticationTerminal private constructor(private val channel: SshSession.Shell) : AuthenticationTerminal {
    private val reader = InputStreamReader(channel.output, Charsets.UTF_8)
    override fun init(questioner: Questioner) = channel.isConnected
    override fun getName() = "服务器运行器认证"
    override fun isConnected() = channel.isConnected
    override fun ready() = reader.ready()
    override fun read(buf: CharArray, offset: Int, length: Int) = reader.read(buf, offset, length)
    override fun write(bytes: ByteArray) { runBlocking { check(channel.write(bytes)) { "服务器终端输入失败" } } }
    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))
    override fun resize(termSize: TermSize) { runBlocking { channel.resize(termSize.columns, termSize.rows) } }
    override fun waitFor(): Int = runBlocking { channel.awaitExitCode() ?: -1 }
    override suspend fun awaitExit(): Int? = channel.awaitExitCode()
    override fun close() = channel.close()
    companion object {
        suspend fun start(plan: RemoteAuthenticationPlan): RemoteAuthenticationTerminal {
            val baseArgs = AcpLaunch.arguments(plan.engine)
            require(plan.directory.startsWith('/') && plan.directory.none { it < ' ' })
            val method = plan.method
            require(method.getString("type") == "terminal" && !method.has("command"))
            require(!method.has("args") || method.opt("args") is JSONArray)
            require(!method.has("env") || method.opt("env") is JSONObject)
            val args = method.optJSONArray("args") ?: JSONArray()
            require(args.length() <= 128)
            (0 until args.length()).forEach { require(args.getString(it).length <= 16384 && '\u0000' !in args.getString(it)) }
            val env = method.optJSONObject("env") ?: JSONObject()
            require(env.length() <= 128)
            env.keys().forEach { key -> require(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}").matches(key) && env.getString(key).length <= 16384 && '\u0000' !in env.getString(key)) }
            val payload = JSONObject().put("args", args).put("env", env).toString() + "\n"
            require(payload.toByteArray().size <= 131072)
            var channel: SshSession.Shell? = null
            try {
                val command = RunnerCatalog.resolveCommand(plan.engine) + "\n[ -f \"\$bin\" ] && [ -x \"\$bin\" ] || exit 127\n" +
                    "exec python3 -c ${Shell.q(bootstrap)} \"\$bin\" ${Shell.q(plan.directory)} " + baseArgs.joinToString(" ") { Shell.q(it) }
                val opened = plan.ssh.openPtyCommand(command, 120, 30); channel = opened
                plan.own(opened)
                withTimeout(10000) { runInterruptible(Dispatchers.IO) {
                    val line = StringBuilder()
                    while (true) {
                        val byte = opened.output.read(); check(byte >= 0 && line.length < 4096) { "服务器认证终端未就绪" }
                        if (byte == 10) { if (line.toString().trimEnd('\r') == "YXI_AUTH_READY") break; line.setLength(0) }
                        else line.append(byte.toChar())
                    }
                } }
                check(opened.write(payload)) { "未能传入服务器认证配置" }
                return RemoteAuthenticationTerminal(opened)
            } catch (e: Exception) { channel?.close(); throw e }
        }
        private val bootstrap = """
import json,os,sys,termios
try:
    original=termios.tcgetattr(0)
    quiet=termios.tcgetattr(0)
    quiet[3] &= ~(termios.ECHO | termios.ECHONL | termios.ICANON)
    quiet[6][termios.VMIN]=1; quiet[6][termios.VTIME]=0
    termios.tcsetattr(0,termios.TCSANOW,quiet)
    print('YXI_AUTH_READY',flush=True)
    raw=sys.stdin.buffer.readline(131073)
    if len(raw)>131072: raise ValueError()
    config=json.loads(raw)
    termios.tcsetattr(0,termios.TCSANOW,original)
    os.chdir(sys.argv[2])
    env=os.environ.copy(); env.update(config['env'])
    os.execvpe(sys.argv[1],[sys.argv[1]]+sys.argv[3:]+config['args'],env)
except Exception:
    print('Authentication terminal startup failed',file=sys.stderr)
    sys.exit(1)
""".trimIndent()
    }
}
