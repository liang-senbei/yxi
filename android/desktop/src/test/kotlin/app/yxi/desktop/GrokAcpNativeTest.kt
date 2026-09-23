package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class GrokAcpNativeTest {
    @Test fun `official Grok executable negotiates ACP with an isolated unauthenticated home`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val home = File("/sandbox/home/.grok")
        val runtime = LocalRuntimeInstallation("grok", "official test cache", listOf("/opt/native/claude"), home.path, "1.0.41")
        val transport = LocalAcpTransport.start(runtime, File("/sandbox/home"))
        val processId = transport.processId
        AcpClient(transport).use { client ->
            val hello = withTimeout(30000) { client.initialize() }
            assertEquals(1, hello.getInt("protocolVersion"))
            assertNotNull(hello.optJSONObject("agentCapabilities"))
            assertNotNull(hello.optJSONArray("authMethods"))
            File("/results/grok-acp-initialize.json").writeText(hello.toString(2))
            assertFalse(File(home, "auth.json").exists(), "Protocol initialization must not synthesize a login")
            val session = withTimeout(30000) { client.newSession("/sandbox/home") }
            assertTrue(session.getString("sessionId").isNotBlank())
            File("/results/grok-acp-session.json").writeText(session.toString(2))
        }
        withTimeout(5000) { while (ProcessHandle.of(processId).map { it.isAlive }.orElse(false)) delay(20) }
        assertFalse(File(home, "auth.json").exists())
    }
}
