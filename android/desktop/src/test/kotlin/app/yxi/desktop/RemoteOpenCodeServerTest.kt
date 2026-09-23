package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RemoteOpenCodeServerTest {
    @Test fun `real SSH owns remote native service and forwarding without closing other sessions`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "remote-opencode-").toFile()
        val home = root.resolve("home").apply { mkdirs() }
        val bin = home.resolve(".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(bin.resolve("opencode").toPath(), Path.of("/opt/native/claude"))
        val project = home.resolve("project").apply { mkdirs() }
        val environment = mapOf("HOME" to home.path, "XDG_DATA_HOME" to home.resolve(".local/share").path,
            "XDG_CONFIG_HOME" to home.resolve(".config").path, "XDG_CACHE_HOME" to home.resolve(".cache").path)
        IsolatedSshBridge(root.resolve("ssh"), environment, root.resolve("unused.sock"), allowForwarding = true).use { bridge ->
            bridge.conn.ssh.connect()
            val first = RemoteOpenCodeServer.start(bridge.conn.ssh, project.path)
            try {
                val second = RemoteOpenCodeServer.start(bridge.conn.ssh, project.path)
                try {
                    assertNotEquals(first.processId, second.processId)
                    assertTrue(first.alive && second.alive)
                    val created = first.client.create("Remote native fixture")
                    assertEquals(project.canonicalPath, created.getString("directory"))
                    assertEquals(created.getString("id"), first.client.session(created.getString("id")).getString("id"))
                    first.close(); first.close()
                    withTimeout(10000) { while (ProcessHandle.of(first.processId).map { it.isAlive }.orElse(false)) delay(100) }
                    assertTrue(second.client.health().getBoolean("healthy"))
                    assertEquals("workspace-alive", bridge.conn.ssh.exec("printf workspace-alive").trim())
                    bridge.conn.ssh.disconnect()
                    withTimeout(10000) { while (ProcessHandle.of(second.processId).map { it.isAlive }.orElse(false)) delay(100) }
                    assertFalse(second.alive)
                    println("Remote native startup, authenticated forwarding, isolated close, SSH disconnect cleanup passed.")
                } finally { second.close() }
            } finally { first.close() }
        }
    }
}
