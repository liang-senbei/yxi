package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import kotlin.test.*

class GeminiAcpNativeTest {
    @Test fun `official Gemini npm entry negotiates ACP with isolated native data home`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val home = File("/sandbox/home/.gemini")
        val runtime = LocalRuntimeInstallation("gemini", "official pinned npm", listOf("/opt/gemini/node",
            "/opt/gemini/runtime/node_modules/@google/gemini-cli/dist/index.js"), home.path, "0.34.0")
        val transport = LocalAcpTransport.start(runtime, File("/sandbox/home"))
        val pid = transport.processId
        AcpClient(transport).use { client ->
            val hello = withTimeout(30000) { client.initialize(terminalAuthentication = true) }
            assertEquals(1, hello.getInt("protocolVersion"))
            assertNotNull(hello.optJSONObject("agentCapabilities"))
            assertTrue(hello.getJSONArray("authMethods").length() > 0)
            File("/results/gemini-acp-initialize.json").writeText(hello.toString(2))
            assertFalse(File(home, "oauth_creds.json").exists())
            val denied = assertFailsWith<AcpRpcException> { client.newSession("/sandbox/home") }
            assertEquals(-32000, denied.code)
        }
        withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) }
        LocalAcpTasks(InstructionQueue(File("/sandbox/tmp/gemini-queue.json")), File("/sandbox/tmp/gemini-tasks.json")).use { tasks ->
            tasks.prepare(runtime, "/sandbox/home")
            val denied = assertFailsWith<IllegalStateException> { tasks.create("Unauthenticated Gemini") }
            assertTrue(denied.message.orEmpty().contains("先登录"))
            assertEquals("", tasks.recoverySessionId)
            assertTrue(tasks.registry.records.isEmpty())
            assertNotNull(tasks.initialization)
        }
        assertFalse(File(home, "oauth_creds.json").exists())
    }
}
