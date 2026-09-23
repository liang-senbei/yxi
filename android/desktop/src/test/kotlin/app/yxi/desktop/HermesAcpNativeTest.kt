package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import kotlin.test.*

class HermesAcpNativeTest {
    @Test fun `pinned official Hermes negotiates ACP in an isolated home`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        check(File("/opt/hermes/bin/hermes").canExecute())
        val runtime = LocalRuntimeInstallation("hermes", "pinned official source", listOf("/opt/hermes/bin/hermes"), "/sandbox/home/.hermes", "0.21.4")
        val transport = LocalAcpTransport.start(runtime, File("/sandbox/home"))
        val processId = transport.processId
        var authentication: org.json.JSONObject? = null
        AcpClient(transport).use { client ->
            val hello = withTimeout(30000) { client.initialize(terminalAuthentication = true) }
            assertEquals(1, hello.getInt("protocolVersion"))
            assertNotNull(hello.optJSONObject("agentCapabilities"))
            File("/results/hermes-acp-initialize.json").writeText(hello.toString(2))
            val methods = hello.getJSONArray("authMethods")
            authentication = (0 until methods.length()).map { methods.getJSONObject(it) }.single { it.optString("type") == "terminal" }
        }
        withTimeout(5000) { while (ProcessHandle.of(processId).map { it.isAlive }.orElse(false)) delay(20) }
        val plan = acpTerminalAuthPlan(runtime, File("/sandbox/home"), checkNotNull(authentication))
        val output = StringBuffer()
        LocalAuthenticationTerminal.start(plan).use { terminal ->
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
                finally { if (!prompt.isCompleted) prompt.completeExceptionally(IllegalStateException("Hermes 未显示配置提示")) }
            }
            try {
                withTimeout(30000) { prompt.await() }
                assertTrue(terminal.isConnected, "Hermes setup must be waiting in an interactive terminal")
            } finally {
                terminal.close()
                withContext(NonCancellable) { withTimeout(5000) { reading.join() } }
                File("/results/hermes-terminal-setup.txt").writeText(output.toString())
            }
        }
    }
}
