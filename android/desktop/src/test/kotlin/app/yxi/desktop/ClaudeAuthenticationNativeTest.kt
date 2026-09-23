package app.yxi.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.test.*

class ClaudeAuthenticationNativeTest {
    @Test fun `native Claude distinguishes missing login from an explicit API credential`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(File("/.dockerenv").exists())
        check(File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(File("/opt/native/claude").canExecute())
        for (api in listOf(false, true)) {
            val home = File("/sandbox/tmp/claude-auth-${if (api) "api" else "empty"}").apply { mkdirs() }
            val output = File(home, "stdout.json")
            val error = File(home, "stderr.txt")
            val process = ProcessBuilder("/opt/native/claude", "auth", "status", "--json").directory(home)
                .redirectOutput(output).redirectError(error).apply {
                    environment().clear()
                    environment().putAll(mapOf("HOME" to home.path, "PATH" to "/usr/bin:/bin", "CLAUDE_CONFIG_DIR" to File(home, ".claude").path,
                        "DISABLE_AUTOUPDATER" to "1"))
                    if (api) environment()["ANTHROPIC_API_KEY"] = "synthetic-auth-status-key"
                }.start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Claude auth status timed out" }
                check(output.length() in 1..65536)
                val status = JSONObject(output.readText())
                assertFalse(output.readText().contains("synthetic-auth-status-key"))
                assertEquals(api, status.getBoolean("loggedIn"))
                if (api) assertEquals("api_key", status.getString("authMethod"))
                File("/results/claude-auth-${if (api) "api" else "empty"}.json").writeText(status.toString(2))
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        }
    }
}
