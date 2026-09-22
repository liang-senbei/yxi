package app.yxi.desktop

import app.yxi.ssh.Shell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class DeviceLinkIsolationTest {
    @Test fun `real SSH reverse login verifies keys and closing link keeps workspace alive`() = runBlocking {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "device-link-").toFile()
        val homeA = root.resolve("home-a").apply { mkdir() }
        val homeB = root.resolve("home-b").apply { mkdir() }
        val device = UUID.randomUUID().toString().replace("-", "")
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val secret = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString("UTF-8")
        val pub = "ssh-ed25519 " + Base64.getEncoder().encodeToString(pair.publicKeyBlob)
        pair.dispose()
        try {
            IsolatedSshBridge(root.resolve("ssh-a"), mapOf("HOME" to homeA.path), root.resolve("a.sock"),
                allowForwarding = true, additionalAuthorizedKeys = homeA.resolve(".ssh/authorized_keys")).use { a ->
                IsolatedSshBridge(root.resolve("ssh-b"), mapOf("HOME" to homeB.path), root.resolve("b.sock")).use { b ->
                    a.conn.ssh.connect()
                    val request = JSONObject().put("action", "prepare").put("device", device).put("publicKey", pub)
                    val prepared = LinkProtocol.result(a.conn.ssh.exec(LinkProtocol.remoteCommand(request)))
                    LinkProtocol.result(a.conn.ssh.exec(LinkProtocol.remoteCommand(request)))
                    assertEquals(1, homeA.resolve(".ssh/authorized_keys").readLines().count { it.startsWith(pub) })
                    root.resolve("ssh-b/client.pub").appendText("\n" + prepared.getString("publicKey") + "\n")
                    val transport = a.conn.ssh.independentLink(secret)
                    transport.connect()
                    val local = ServerSocket(0).use { it.localPort }
                    val reverse = ServerSocket(0).use { it.localPort }
                    val secondLocal = ServerSocket(0).use { it.localPort }
                    val lease = transport.forwardLink(reverse, local, b.conn.host.port)
                    try {
                        val verified = LinkProtocol.result(transport.exec(LinkProtocol.remoteCommand(JSONObject().put("action", "verify").put("device", device)
                            .put("reversePort", reverse).put("username", "root").put("hostKey", root.resolve("ssh-b/host.pub").readText()))))
                        assertTrue(verified.getBoolean("verified"))
                        ServerSocket(5901, 1, InetAddress.getLoopbackAddress()).use { vnc ->
                            val responder = thread { vnc.accept().use { it.getOutputStream().write("fixture-vnc\n".toByteArray()) } }
                            Socket().use { client ->
                                client.connect(InetSocketAddress("127.0.0.1", local), 2000); client.soTimeout = 3000
                                assertEquals("fixture-vnc", client.getInputStream().bufferedReader().readLine())
                            }
                            responder.join(3000); assertFalse(responder.isAlive)
                        }
                        assertFails { transport.forwardLink(reverse, secondLocal, b.conn.host.port) }
                        ServerSocket(secondLocal).close() // Failed reverse request must release its local listener.
                    } finally { lease.close(); lease.close(); transport.disconnect() }
                    ServerSocket(local).close()
                    assertEquals("workspace-alive", a.conn.ssh.exec("printf workspace-alive").trim())
                    assertTrue(homeA.resolve(".ssh/yxi-link-$device").isFile)
                    assertFalse(homeB.resolve(".ssh/yxi-link-$device").exists(), "Server private key must never be copied to the local machine")
                }
            }
        } finally { root.deleteRecursively() }
    }
}
