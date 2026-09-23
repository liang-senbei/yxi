package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class HermesRemoteAuthenticationTest {
    @Test fun `official Hermes offers its provider setup through server authentication`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        check(File("/opt/hermes/bin/hermes").canExecute())
        val root = File("/sandbox/tmp/hermes-remote-auth").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val bin = File(home, ".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "hermes").toPath(), File("/opt/hermes/bin/hermes").toPath())
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path, "HERMES_HOME" to File(home, ".hermes").path), File(root, "unused.sock")).use { bridge ->
            bridge.conn.ssh.connect()
            val method = RemoteAcpTransport.connect(bridge.conn.ssh, "hermes", home.path, terminalAuthentication = true).use { client ->
                val methods = checkNotNull(client.initialization).getJSONArray("authMethods")
                (0 until methods.length()).map { methods.getJSONObject(it) }.single { it.optString("type") == "terminal" }
            }
            assertEquals("hermes-setup", method.getString("id"))
            val output = StringBuffer()
            RemoteAuthenticationPlan(bridge.conn.ssh, "hermes", home.path, method).use { plan ->
                RemoteAuthenticationTerminal.start(plan).use { terminal ->
                    val prompt = CompletableDeferred<Unit>()
                    val reading = launch(Dispatchers.IO) {
                        try {
                            val buffer = CharArray(1024)
                            while (true) {
                                val count = terminal.read(buffer, 0, buffer.size)
                                if (count < 0) break
                                output.append(buffer, 0, count)
                                check(output.length <= 65536)
                                val plain = output.toString().replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
                                if (Regex("(?is)(select|choose)[^\\n]{0,100}(provider|model)").containsMatchIn(plain)) prompt.complete(Unit)
                            }
                        } catch (e: Exception) { if (terminal.isConnected) prompt.completeExceptionally(e) }
                        finally { if (!prompt.isCompleted) prompt.completeExceptionally(IllegalStateException("远端 Hermes 未显示配置提示")) }
                    }
                    try {
                        withTimeout(30000) { prompt.await() }
                        assertTrue(terminal.isConnected)
                        assertFalse(output.contains("YXI_AUTH_"))
                    } finally {
                        plan.close()
                        withContext(NonCancellable) { withTimeout(5000) { reading.join() } }
                        File("/results/hermes-remote-terminal-setup.txt").writeText(output.toString())
                    }
                    assertNull(withTimeout(5000) { terminal.awaitExit() }, "Closing setup must not report successful authentication")
                }
            }
            assertEquals("alive", bridge.conn.ssh.exec("printf alive").trim())
        }
    }
}
