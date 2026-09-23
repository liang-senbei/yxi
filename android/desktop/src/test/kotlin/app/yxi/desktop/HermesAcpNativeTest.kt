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
        AcpClient(transport).use { client ->
            val hello = withTimeout(30000) { client.initialize() }
            assertEquals(1, hello.getInt("protocolVersion"))
            assertNotNull(hello.optJSONObject("agentCapabilities"))
            File("/results/hermes-acp-initialize.json").writeText(hello.toString(2))
        }
        withTimeout(5000) { while (ProcessHandle.of(processId).map { it.isAlive }.orElse(false)) delay(20) }
    }
}
