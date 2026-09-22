package app.yxi.desktop

import app.yxi.ssh.HostKeys
import app.yxi.ssh.Shell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Private SSH bridge into an existing container fixture; never exposes host credentials or sockets. */
internal class IsolatedSshBridge(private val root: File, environment: Map<String, String>, socket: File,
    allowForwarding: Boolean = false, additionalAuthorizedKeys: File? = null) : AutoCloseable {
    private var server: Process? = null
    lateinit var conn: Conn
        private set

    init {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(System.getenv("YXI_ISOLATED_TEST_RUN")?.matches(Regex("[0-9a-f-]{36}")) == true)
        check(root.canonicalPath.startsWith("/sandbox/tmp/") && socket.canonicalPath.startsWith("/sandbox/tmp/"))
        check(root.mkdir())
        try {
            fun setup(vararg args: String) {
                val p = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(root.resolve("setup.log")).start()
                try { check(p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) { "SSH fixture setup failed" } }
                finally { if (p.isAlive) p.destroyForcibly() }
            }
            setup("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", root.resolve("host").path)
            setup("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", root.resolve("client").path)
            val bin = root.resolve("bin").apply { mkdir() }
            bin.resolve("tmux").apply {
                writeText("#!/bin/sh\nexec /usr/bin/tmux -S ${Shell.q(socket.path)} \"\$@\"\n")
                setExecutable(true, true)
            }
            val env = environment + ("PATH" to "${bin.path}:/usr/bin:/bin")
            check(env.keys.all { it.matches(Regex("[A-Z_][A-Z0-9_]*")) })
            val forced = root.resolve("command").apply {
                writeText("#!/bin/sh\nexec /usr/bin/env -i " + env.entries.joinToString(" ") {
                    Shell.q("${it.key}=${it.value}")
                } + " /bin/sh -c \"\$SSH_ORIGINAL_COMMAND\"\n")
                setExecutable(true, true)
            }
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val config = root.resolve("sshd_config").apply { writeText("""
                ListenAddress 127.0.0.1
                Port $port
                HostKey ${root.resolve("host").path}
                AuthorizedKeysFile ${root.resolve("client.pub").path} ${additionalAuthorizedKeys?.path.orEmpty()}
                PidFile ${root.resolve("pid").path}
                PubkeyAuthentication yes
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                PermitRootLogin prohibit-password
                StrictModes yes
                UsePAM no
                AllowTcpForwarding ${if (allowForwarding) "yes" else "no"}
                GatewayPorts no
                AllowAgentForwarding no
                X11Forwarding no
                Subsystem sftp /usr/lib/openssh/sftp-server
                ForceCommand ${forced.path}
            """.trimIndent() + "\n") }
            server = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", config.path)
                .redirectErrorStream(true).redirectOutput(root.resolve("sshd.log")).start()
            val expected = root.resolve("host.pub").readText().trim().split(' ')[1]
            val keys = object : HostKeys {
                override var changedDetected = false
                override fun check(host: String?, key: ByteArray?): Int {
                    val ok = key != null && Base64.getEncoder().encodeToString(key) == expected
                    changedDetected = !ok
                    return if (ok) HostKeyRepository.OK else HostKeyRepository.CHANGED
                }
                override fun add(key: HostKey?, ui: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID() = "isolated-bridge-pinned"
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
            conn = Conn(Host("isolated-bridge", "isolated-bridge", "127.0.0.1", port, "root",
                keyPath = root.resolve("client").path), keys)
            repeat(50) {
                if (server?.isAlive == true && root.resolve("pid").exists()) return@repeat
                Thread.sleep(100)
            }
            check(server?.isAlive == true && root.resolve("pid").exists()) { "Private SSH listener failed" }
        } catch (e: Exception) { close(); throw e }
    }

    override fun close() {
        if (::conn.isInitialized) conn.ssh.disconnect()
        server?.toHandle()?.descendants()?.use { it.forEach { child -> child.destroyForcibly() } }
        server?.destroyForcibly()
        server?.waitFor(5, TimeUnit.SECONDS)
        root.resolve("sshd.log").takeIf { it.exists() }?.copyTo(File("/results/native-bridge-sshd.log"), overwrite = true)
    }
}
