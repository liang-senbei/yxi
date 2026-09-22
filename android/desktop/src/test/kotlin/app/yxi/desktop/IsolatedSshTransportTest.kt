package app.yxi.desktop

import app.yxi.ssh.HostKeys
import app.yxi.ssh.SshSession
import app.yxi.agent.Rewind
import app.yxi.agent.SessionProbe
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs only through dev/isolated-tests/run.sh; no host socket, HOME or credentials are used. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class IsolatedSshTransportTest {
    @Test fun `pinned SSH transport reads and writes only the isolated HOME`() {
        check(File("/.dockerenv").isFile)
        check(File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(System.getenv("HOME") == "/sandbox/home")
        check(System.getenv("CLAUDE_CONFIG_DIR") == "/sandbox/home/.claude")
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "ssh-transport-").toFile()
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
        val home = root.resolve("home").apply { mkdir() }
        val tmuxRoot = home.resolve("tmux").apply { mkdir() }
        val log = root.resolve("sshd.log")
        var server: Process? = null
        var ssh: SshSession? = null
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "isolated-ssh-client").apply { isDaemon = true } }
        fun command(vararg args: String) {
            val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(root.resolve("setup.log")).start()
            try {
                check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Fixture setup failed" }
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        try {
            command("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", root.resolve("host").path)
            command("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", root.resolve("client").path)
            val force = root.resolve("command").apply {
                writeText("#!/bin/sh\nexec /usr/bin/env -i HOME='${home.path}' CLAUDE_CONFIG_DIR='${home.path}/.claude' TMUX_TMPDIR='${tmuxRoot.path}' PATH=/usr/bin:/bin LANG=C.UTF-8 /bin/sh -c \"\$SSH_ORIGINAL_COMMAND\"\n")
                setExecutable(true, true)
            }
            val port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
            val config = root.resolve("sshd_config").apply { writeText("""
                ListenAddress 127.0.0.1
                Port $port
                HostKey ${root.resolve("host").path}
                PidFile ${root.resolve("sshd.pid").path}
                AuthorizedKeysFile ${root.resolve("client.pub").path}
                PubkeyAuthentication yes
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                PermitRootLogin prohibit-password
                StrictModes yes
                UsePAM no
                AllowTcpForwarding no
                AllowAgentForwarding no
                X11Forwarding no
                PermitTunnel no
                ForceCommand ${force.path}
                LogLevel VERBOSE
            """.trimIndent() + "\n") }
            val listener = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", config.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            server = listener
            var ready = false
            repeat(50) {
                if (!ready && listener.isAlive) {
                    ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; true }.getOrDefault(false)
                    if (!ready) Thread.sleep(100)
                }
            }
            assertTrue(ready && listener.isAlive, "Private sshd failed to start: ${log.readText().takeLast(2000)}")
            val expected = root.resolve("host.pub").readText().trim().split(' ')[1]
            val conn = Conn(Host(id = "container", alias = "container", hostname = "127.0.0.1",
                port = port, username = "root", keyPath = root.resolve("client").path), pinnedKeys(expected))
            val client = conn.ssh
            ssh = client
            val request = executor.submit<String> {
                runBlocking {
                    println("isolated-ssh: connecting")
                    client.connect()
                    println("isolated-ssh: connected")
                    client.exec("printf '%s\\n' \"\$HOME\"; printf '%s' '中文🙂' > \"\$HOME/probe.txt\"; cat \"\$HOME/probe.txt\"")
                }
            }
            val output = request.get(25, TimeUnit.SECONDS)
            assertEquals("${home.path}\n中文🙂", output)
            assertEquals("中文🙂", home.resolve("probe.txt").readText())
            val payload = "输入🙂".repeat(50_000).toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            val sid = "11111111-1111-4111-8111-111111111111"
            val echoScript = "import sys,json,hashlib; d=sys.stdin.buffer.read(); " +
                "print(json.dumps({'type':'user','echo':d.decode()})); " +
                "print(json.dumps({'type':'result','session_id':'$sid','result':hashlib.sha256(d).hexdigest()})); " +
                "print('${Rewind.TAG}:rc=0')"
            val streamed = executor.submit<String> { runBlocking {
                runRewindCommand(client, "python3 -c ${app.yxi.ssh.Shell.q(echoScript)}", payload)
            } }.get(25, TimeUnit.SECONDS)
            assertEquals(Rewind.Outcome.Ok(sid, digest), Rewind.parse(streamed))
            assertTrue(streamed.length < 500, "Verbose input echo must not be retained")
            val ownedSocket = "${tmuxRoot.path}/tmux-0/default"
            val tmuxCheck = executor.submit<String> {
                runBlocking {
                    client.exec("tmux -f /dev/null new-session -d -s transport-check 'sleep 60' && " +
                        "tmux display-message -p -t '=transport-check:' '#{socket_path}'")
                }
            }
            assertEquals(ownedSocket, tmuxCheck.get(15, TimeUnit.SECONDS).trim(), "The actual tmux socket must belong to this fixture")
            val closeSession = executor.submit<String> {
                runBlocking {
                    client.exec("tmux -S '$ownedSocket' kill-session -t '=transport-check:' && printf 'owned-session-stopped'")
                }
            }
            assertEquals("owned-session-stopped", closeSession.get(10, TimeUnit.SECONDS))

            // Real controller regression: the pane's current directory can differ from
            // the directory captured before rewind. Resume must retain the captured one.
            val originalProject = root.resolve("original project").apply { mkdir() }
            val changedProject = root.resolve("changed-project").apply { mkdir() }
            val observed = root.resolve("resumed-cwd")
            val executable = root.resolve("claude-resume-probe").apply {
                writeText("#!/bin/sh\npwd > '${observed.path}'\n")
                setExecutable(true, true)
            }
            val resumeCheck = executor.submit<Boolean> {
                runBlocking {
                    client.exec("tmux -S '$ownedSocket' -f /dev/null new-session -d -s cc-resume-check -c '${changedProject.path}' '/bin/bash --noprofile --norc'")
                    val before = SessionProbe.snapshot(client).single { it.name == "cc-resume-check" }
                    assertEquals(changedProject.path, before.cwd)
                    val pane = client.exec("tmux -S '$ownedSocket' display-message -p -t '=cc-resume-check:' '#{pane_id}'").trim()
                    val report = RewindController(conn, RewindDeliveryGate(root.resolve("gate.json")))
                        .relaunch(before.name, Rewind.Capture(executable.path, "", paneId = pane),
                            "11111111-1111-4111-8111-111111111111", before.runtimeId, originalProject.path)
                    report.relaunched
                }
            }
            assertTrue(resumeCheck.get(25, TimeUnit.SECONDS), "Controller must deliver the verified resume")
            repeat(50) { if (!observed.exists()) Thread.sleep(100) }
            assertEquals(originalProject.path, observed.readText().trim())
            executor.submit<String> { runBlocking {
                client.exec("tmux -S '$ownedSocket' kill-session -t '=cc-resume-check:'")
            } }.get(10, TimeUnit.SECONDS)
        } finally {
            server?.toHandle()?.descendants()?.use { children -> children.forEach { it.destroyForcibly() } }
            server?.destroyForcibly()
            ssh?.disconnect()
            executor.shutdownNow()
            if (log.exists()) {
                println("isolated-sshd: ${log.readText().takeLast(2000)}")
                log.copyTo(File("/results/isolated-sshd.log"), overwrite = true)
            }
            // Leave the fixture within the disposable container for diagnosis; no global cleanup commands.
        }
    }

    private fun pinnedKeys(expected: String) = object : HostKeys {
        override var changedDetected = false
        override fun check(host: String?, key: ByteArray?): Int {
            val matches = key != null && Base64.getEncoder().encodeToString(key) == expected
            changedDetected = !matches
            return if (matches) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(key: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "container-pinned-key"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        override fun userInfo() = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = null
            override fun promptPassword(message: String?) = false
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) = Unit
        }
    }
}
