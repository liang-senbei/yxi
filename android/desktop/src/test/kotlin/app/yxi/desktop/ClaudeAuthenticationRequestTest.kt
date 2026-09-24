package app.yxi.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.test.*

class ClaudeAuthenticationRequestTest {
    @Test fun `native Claude request headers identify isolated credential combinations`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(File("/.dockerenv").exists() && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        for ((name, api, oauth) in listOf(Triple("api", true, false), Triple("oauth", false, true), Triple("mixed", true, true))) {
            val root = File("/sandbox/tmp/claude-request-$name").apply { mkdirs() }
            val script = File(root, "server.py").apply { writeText(ClaudeAuthenticationRequestTest::class.java.getResource("/rewind/anthropic_stub.py")!!.readText()) }
            val server = ProcessBuilder("python3", script.path, root.path).redirectErrorStream(true).redirectOutput(File(root, "server.log")).start()
            var process: Process? = null
            try {
                val port = File(root, "port")
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!port.exists() && System.nanoTime() < deadline) Thread.sleep(20)
                check(port.exists())
                val stdout = File(root, "stdout.json")
                process = ProcessBuilder("/opt/native/claude", "-p", "KEEP-auth-request", "--output-format", "json", "--max-turns", "1")
                    .directory(root).redirectOutput(stdout).redirectError(File(root, "stderr.txt")).apply {
                        environment().clear()
                        environment().putAll(mapOf("HOME" to root.path, "PATH" to "/usr/bin:/bin", "CLAUDE_CONFIG_DIR" to File(root, ".claude").path,
                            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:${port.readText().trim()}", "DISABLE_AUTOUPDATER" to "1",
                            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1", "DISABLE_TELEMETRY" to "1"))
                        if (api) environment()["ANTHROPIC_API_KEY"] = "sk-ant-yxi-container-test-only"
                        if (oauth) environment()["CLAUDE_CODE_OAUTH_TOKEN"] = "synthetic-oauth-request-token"
                    }.start()
                check(process.waitFor(40, TimeUnit.SECONDS)) { "$name request timed out" }
                val requests = File(root, "requests.jsonl")
                if (requests.exists()) requests.copyTo(File("/results/claude-request-$name.jsonl"), overwrite = true)
                stdout.copyTo(File("/results/claude-request-$name-output.json"), overwrite = true)
                File(root, "stderr.txt").copyTo(File("/results/claude-request-$name-error.txt"), overwrite = true)
                check(process.exitValue() == 0) { "$name native CLI failed" }
                assertTrue(stdout.readText().contains("answer:KEEP-auth-request"))
                val messages = requests.readLines().map(::JSONObject).filter { it.getJSONArray("messages").length() > 0 }
                assertTrue(messages.isNotEmpty())
                if (name == "api") assertTrue(messages.all { it.getBoolean("fixture_api_header") })
                if (name == "oauth") assertTrue(messages.all { it.getBoolean("fixture_oauth_header") })
            } finally {
                process?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
                server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
