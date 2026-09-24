package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import org.json.JSONArray

/** Reads native credential identity without sending a prompt. This does not certify endpoint policy or entitlement. */
internal object ClaudeSubscriptionProbe {
    suspend fun verifyRemote(ssh: SshSession, directory: String) {
        require(directory.startsWith('/') && directory.none { it < ' ' }) { "请输入服务器绝对工作目录" }
        val command = RunnerCatalog.resolveCommand("claude") + "\n[ -f \"\$bin\" ] && [ -x \"\$bin\" ] || exit 127\n" +
            "exec python3 -c ${Shell.q(remoteSupervisor)} \"\$bin\" ${Shell.q(directory)} " +
            Shell.q(ClaudeSubscriptionSettings.overlay().toString()) + " " + Shell.q(JSONArray(ClaudeSubscriptionSettings.removedEnvironmentKeys()).toString())
        val channel = ssh.openExecStream(command)
        try {
            val bytes = withTimeout(17000) {
                try { runInterruptible(Dispatchers.IO) { channel.output.readNBytes(65_537) } }
                catch (e: java.io.InterruptedIOException) { currentCoroutineContext().ensureActive(); throw e }
            }
            check(bytes.size in 1..65_536) { "服务器 Claude 认证检查未返回有效响应，请检查运行器和工作目录" }
            val status = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { error("服务器 Claude 未返回可识别的认证状态") }
            ClaudeSubscriptionSettings.requireOAuthIdentity(status)
            check(withTimeout(2000) { channel.awaitExitCode() } == 0) { "服务器 Claude 未确认认证检查成功" }
        } finally { channel.close() }
    }

    private val remoteSupervisor = """
import json,os,select,signal,subprocess,sys,time
process=None
def stop(*args): raise SystemExit(1)
signal.signal(signal.SIGHUP,stop)
signal.signal(signal.SIGTERM,stop)
try:
    os.chdir(sys.argv[2])
    env=os.environ.copy()
    for key in json.loads(sys.argv[4]): env.pop(key,None)
    env['DISABLE_AUTOUPDATER']='1'
    process=subprocess.Popen([sys.argv[1],'--settings',sys.argv[3],'auth','status','--json'],env=env,
        stdin=subprocess.DEVNULL,stdout=sys.stdout.buffer,stderr=subprocess.DEVNULL,start_new_session=True)
    deadline=time.monotonic()+15
    while process.poll() is None:
        if time.monotonic()>deadline: raise SystemExit(1)
        if select.select([0],[],[],0.1)[0] and not os.read(0,4096): raise SystemExit(1)
    sys.exit(process.returncode)
finally:
    if process is not None and process.poll() is None:
        os.killpg(process.pid,signal.SIGTERM)
        try: process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid,signal.SIGKILL)
            process.wait()
""".trimIndent()
    suspend fun verify(binary: File, directory: File, inherited: Map<String, String>,
        settings: JSONObject = ClaudeSubscriptionSettings.overlay(), cancelled: () -> Boolean = { false }): Unit =
        verify(listOf(binary.absolutePath), directory, inherited, settings, cancelled)

    suspend fun verify(command: List<String>, directory: File, inherited: Map<String, String>,
        settings: JSONObject = ClaudeSubscriptionSettings.overlay(), cancelled: () -> Boolean = { false }): Unit = withContext(Dispatchers.IO) {
        require(command.isNotEmpty() && command.none { '\u0000' in it })
        require(File(command.first()).let { it.isFile && it.canExecute() } && directory.isDirectory)
        currentCoroutineContext().ensureActive()
        if (cancelled()) throw CancellationException("订阅检查已取消")
        val process = ProcessBuilder(command + listOf("--settings", settings.toString(), "auth", "status", "--json"))
            .directory(directory.canonicalFile).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                environment().clear(); environment().putAll(ClaudeSubscriptionSettings.environment(inherited))
                environment()["DISABLE_AUTOUPDATER"] = "1"
            }.start()
        val reading = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(65_537) } }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (cancelled()) throw CancellationException("订阅检查已取消")
                if (reading.isDone) check(reading.get().size <= 65_536) { "Claude 认证检查响应过大，未发送任务" }
                check(System.nanoTime() < deadline) { "Claude 认证检查超时，未发送任务" }
            }
            val bytes = reading.get(2, TimeUnit.SECONDS)
            check(bytes.size in 1..65_536) { "Claude 认证检查响应无效，未发送任务" }
            val status = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { error("Claude 未返回可识别的认证状态，未发送任务") }
            ClaudeSubscriptionSettings.requireOAuthIdentity(status)
            check(process.exitValue() == 0) { "Claude 未确认认证检查成功，未发送任务" }
        } finally { LocalRuntimeDiscovery.stopOwnedProcess(process); reading.cancel(true) }
    }
}
