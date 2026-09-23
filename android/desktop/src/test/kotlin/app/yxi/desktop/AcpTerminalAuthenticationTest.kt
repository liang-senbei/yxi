package app.yxi.desktop

import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AcpTerminalAuthenticationTest {
    @TempDir lateinit var root: File
    @Test fun `terminal auth preserves configured invocation and home with separate arguments`() {
        val runtime = LocalRuntimeInstallation("hermes", "fixture", listOf("/usr/bin/python3", "/folder with spaces/hermes.py"), root.path, "1")
        val method = JSONObject("""{"type":"terminal","id":"setup","args":["--setup"],"env":{"ACP_INTERACTIVE_LOGIN":"1"}}""")
        val plan = acpTerminalAuthPlan(runtime, root, method, mapOf("PATH" to "/bin", "HERMES_HOME" to "/wrong"))
        assertEquals(runtime.command + listOf("acp", "--setup"), plan.command)
        assertEquals(root.path, plan.environment["HERMES_HOME"])
        assertEquals("1", plan.environment["ACP_INTERACTIVE_LOGIN"])
        assertEquals(root.canonicalPath, plan.directory)
        assertFailsWith<IllegalArgumentException> { acpTerminalAuthPlan(runtime, root, method.put("command", "other-program")) }
    }
}
