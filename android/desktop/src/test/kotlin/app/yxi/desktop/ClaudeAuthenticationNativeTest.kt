package app.yxi.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.test.*

class ClaudeAuthenticationNativeTest {
    @Test fun `native Claude reports mixed credential sources without proving request authentication`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(File("/.dockerenv").exists())
        check(File("/sys/class/net").list()?.toSet() == setOf("lo"))
        check(File("/opt/native/claude").canExecute())
        for ((name, api, oauth) in listOf(Triple("empty", false, false), Triple("api", true, false),
            Triple("oauth", false, true), Triple("oauth-and-api", true, true))) {
            val home = File("/sandbox/tmp/claude-auth-$name").apply { mkdirs() }
            val output = File(home, "stdout.json")
            val error = File(home, "stderr.txt")
            val process = ProcessBuilder("/opt/native/claude", "auth", "status", "--json").directory(home)
                .redirectOutput(output).redirectError(error).apply {
                    environment().clear()
                    environment().putAll(mapOf("HOME" to home.path, "PATH" to "/usr/bin:/bin", "CLAUDE_CONFIG_DIR" to File(home, ".claude").path,
                        "DISABLE_AUTOUPDATER" to "1"))
                    if (api) environment()["ANTHROPIC_API_KEY"] = "synthetic-auth-status-key"
                    if (oauth) environment()["CLAUDE_CODE_OAUTH_TOKEN"] = "synthetic-oauth-status-token"
                }.start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Claude auth status timed out" }
                check(output.length() in 1..65536)
                val status = JSONObject(output.readText())
                assertFalse(output.readText().contains("synthetic-auth-status-key"))
                assertFalse(output.readText().contains("synthetic-oauth-status-token"))
                File("/results/claude-auth-$name.json").writeText(status.toString(2))
                assertEquals(api || oauth, status.getBoolean("loggedIn"))
                assertEquals(if (oauth) "oauth_token" else if (api) "api_key" else "none", status.getString("authMethod"))
                if (api) assertEquals("ANTHROPIC_API_KEY", status.getString("apiKeySource"))
                else assertFalse(status.has("apiKeySource"))
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        }
    }
}
