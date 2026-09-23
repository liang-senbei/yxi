package app.yxi.desktop

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession

/** Owns one exec channel and its child process group; never attaches to an existing agent. */
internal class RemoteAcpTransport private constructor(private val channel: SshSession.Shell) : AcpTransport {
    override val output get() = channel.output
    override suspend fun write(text: String) = channel.write(text)
    override fun close() = channel.close()
    companion object {
        suspend fun connect(ssh: SshSession, engine: String, directory: String): AcpClient {
            val arguments = AcpLaunch.arguments(engine)
            require(directory.startsWith('/') && directory.none { it < ' ' })
            check(ssh.exec(RunnerCatalog.probeCommand(engine)).trim() == "available") { "服务器未找到可执行的 ${LocalRuntimeDiscovery.title(engine)}" }
            val command = RunnerCatalog.resolveCommand(engine) + "\n[ -f \"\$bin\" ] && [ -x \"\$bin\" ] || exit 127\n" +
                "exec python3 -c ${Shell.q(supervisor)} \"\$bin\" ${Shell.q(directory)} " + arguments.joinToString(" ") { Shell.q(it) }
            val client = AcpClient(RemoteAcpTransport(ssh.openExecStream(command)))
            try { client.initialize(); return client } catch (e: Exception) { client.close(); throw e }
        }
        private val supervisor = """
import os, select, signal, subprocess, sys
process = None
def stop(*args):
    raise SystemExit(0)
signal.signal(signal.SIGHUP, stop)
signal.signal(signal.SIGTERM, stop)
try:
    os.chdir(sys.argv[2])
    process = subprocess.Popen([sys.argv[1]] + sys.argv[3:], stdin=subprocess.PIPE,
        stdout=sys.stdout.buffer, stderr=subprocess.DEVNULL, start_new_session=True)
    while process.poll() is None:
        readable, _, _ = select.select([0], [], [], 0.2)
        if readable:
            data = os.read(0, 65536)
            if not data: break
            process.stdin.write(data)
            process.stdin.flush()
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
