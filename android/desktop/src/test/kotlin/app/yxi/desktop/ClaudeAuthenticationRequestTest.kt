package app.yxi.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.test.*

class ClaudeAuthenticationRequestTest {
    @Test fun `native Claude request headers identify isolated credential combinations`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(File("/.dockerenv").exists() && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        for ((name, api, oauth) in listOf(Triple("api", true, false), Triple("oauth", false, true), Triple("mixed", true, true), Triple("overlay", true, true), Triple("project-overlay", true, true),
            Triple("permission-baseline", false, true), Triple("permission-overlay", true, true), Triple("stored-overlay", true, false),
            Triple("cloud-BEDROCK-overlay", true, true), Triple("cloud-VERTEX-overlay", true, true), Triple("cloud-FOUNDRY-overlay", true, true))) {
            val root = File("/sandbox/tmp/claude-request-$name").apply { mkdirs() }
            val overlay = name.endsWith("overlay")
            val cloudFlag = name.takeIf { it.startsWith("cloud-") }?.split('-')?.get(1)?.let { "CLAUDE_CODE_USE_$it" }
            val permission = name.startsWith("permission-")
            val directory = if (permission) File(root, "project").apply { mkdirs() } else if (name == "project-overlay" || cloudFlag != null) File(root, "workspace").apply { mkdirs() } else root
            if (permission) File(directory, "retained-tool.txt").writeText("YXI_PERMISSION_CONTENT")
            val prompt = if (permission) "KEEP-container-first" else "KEEP-auth-request"
            val script = File(root, "server.py").apply { writeText(ClaudeAuthenticationRequestTest::class.java.getResource("/rewind/anthropic_stub.py")!!.readText()) }
            val server = ProcessBuilder("python3", script.path, root.path).redirectErrorStream(true).redirectOutput(File(root, "server.log")).start()
            var process: Process? = null
            try {
                val port = File(root, "port")
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!port.exists() && System.nanoTime() < deadline) Thread.sleep(20)
                check(port.exists())
                val stdout = File(root, "stdout.json")
                val endpoint = "http://127.0.0.1:${port.readText().trim()}"
                val settings = File(root, ".claude/settings.json")
                val helperMarker = File(root, "helper-ran")
                val credentials = File(root, ".claude/.credentials.json")
                val storedCredentials = if (name == "stored-overlay") {
                    credentials.parentFile.mkdirs()
                    credentials.writeText(JSONObject().put("claudeAiOauth", JSONObject()
                        .put("accessToken", "synthetic-oauth-request-token").put("refreshToken", "synthetic-refresh-token")
                        .put("expiresAt", 4102444800000L).put("scopes", org.json.JSONArray().put("user:inference").put("user:profile"))
                        .put("subscriptionType", "max")).toString())
                    credentials.setReadable(false, false); credentials.setReadable(true, true)
                    credentials.setWritable(false, false); credentials.setWritable(true, true)
                    credentials.readBytes()
                } else null
                val before = if (overlay) {
                    settings.parentFile.mkdirs()
                    settings.writeText(JSONObject().put("env", JSONObject().put("ANTHROPIC_API_KEY", "sk-ant-yxi-container-test-only")
                        .put("ANTHROPIC_BASE_URL", "http://127.0.0.1:1").apply { cloudFlag?.let { put(it, "1") } })
                        .put("apiKeyHelper", "touch ${helperMarker.path}; printf sk-ant-yxi-container-test-only")
                        .put("effortLevel", "low").toString())
                    settings.readBytes()
                } else null
                val projectFiles = if (name == "project-overlay" || name == "permission-overlay" || cloudFlag != null) listOf("settings.json", "settings.local.json").associate { filename ->
                    val file = File(directory, ".claude/$filename").apply { parentFile.mkdirs() }
                    file.writeText(JSONObject().put("env", JSONObject().put("ANTHROPIC_API_KEY", "sk-ant-yxi-container-test-only")
                        .put("ANTHROPIC_AUTH_TOKEN", "project-conflicting-token").put("ANTHROPIC_BASE_URL", "http://127.0.0.1:1").apply { cloudFlag?.let { put(it, "1") } })
                        .put("permissions", JSONObject().put("deny", org.json.JSONArray().put(if (permission) "Read" else "Bash"))).toString())
                    file to file.readBytes()
                } else emptyMap()
                val overlayArgs = if (overlay) listOf("--settings", ClaudeSubscriptionSettings.overlay().apply {
                    // The test substitutes only the endpoint; production defaults remain official.
                    getJSONObject("env").put("ANTHROPIC_BASE_URL", endpoint)
                }.toString()) else emptyList()
                process = ProcessBuilder(listOf("/opt/native/claude", "-p", prompt, "--output-format", "json", "--max-turns", if (permission) "3" else "1") + overlayArgs)
                    .directory(directory).redirectOutput(stdout).redirectError(File(root, "stderr.txt")).apply {
                        environment().clear()
                        environment().putAll(mapOf("HOME" to root.path, "PATH" to "/usr/bin:/bin", "CLAUDE_CONFIG_DIR" to File(root, ".claude").path,
                            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:${port.readText().trim()}", "DISABLE_AUTOUPDATER" to "1",
                            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1", "DISABLE_TELEMETRY" to "1"))
                        if (api) environment()["ANTHROPIC_API_KEY"] = "sk-ant-yxi-container-test-only"
                        if (oauth) environment()["CLAUDE_CODE_OAUTH_TOKEN"] = "synthetic-oauth-request-token"
                        cloudFlag?.let { environment()[it] = "1" }
                        if (overlay) {
                            val filtered = ClaudeSubscriptionSettings.environment(environment())
                            environment().clear(); environment().putAll(filtered)
                        }
                    }.start()
                check(process.waitFor(40, TimeUnit.SECONDS)) { "$name request timed out" }
                val requests = File(root, "requests.jsonl")
                if (requests.exists()) requests.copyTo(File("/results/claude-request-$name.jsonl"), overwrite = true)
                stdout.copyTo(File("/results/claude-request-$name-output.json"), overwrite = true)
                File(root, "stderr.txt").copyTo(File("/results/claude-request-$name-error.txt"), overwrite = true)
                check(process.exitValue() == 0) { "$name native CLI failed" }
                assertTrue(stdout.readText().contains("answer:$prompt"))
                val messages = requests.readLines().map(::JSONObject).filter { it.getJSONArray("messages").length() > 0 }
                assertTrue(messages.isNotEmpty())
                if (permission) {
                    val content = messages.last().getJSONArray("messages").toString()
                    if (!overlay) assertTrue(content.contains("YXI_PERMISSION_CONTENT"), "Control must actually read the file")
                    else {
                        assertFalse(content.contains("YXI_PERMISSION_CONTENT"))
                        val transcript = messages.last().getJSONArray("messages")
                        val blocks = (0 until transcript.length()).flatMap { i ->
                            val entries = transcript.getJSONObject(i).optJSONArray("content")
                            if (entries == null) emptyList() else (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                        }
                        assertTrue(blocks.any { it.optString("type") == "tool_result" && it.optBoolean("is_error") &&
                            it.optString("content").contains("Read is disabled for this session") }, "Native CLI must explicitly disable the forbidden Read tool")
                    }
                }
                if (name == "api") assertTrue(messages.all { it.getBoolean("fixture_api_header") })
                if (name == "oauth") assertTrue(messages.all { it.getBoolean("fixture_oauth_header") })
                if (name == "mixed") assertTrue(messages.all { it.getBoolean("fixture_api_header") && !it.getBoolean("fixture_oauth_header") })
                if (overlay) {
                    assertTrue(messages.all { !it.getBoolean("fixture_api_header") && it.getBoolean("fixture_oauth_header") })
                    assertContentEquals(before, settings.readBytes())
                    assertFalse(helperMarker.exists())
                    projectFiles.forEach { (file, bytes) -> assertContentEquals(bytes, file.readBytes()) }
                    if (storedCredentials != null) assertContentEquals(storedCredentials, credentials.readBytes())
                }
            } finally {
                process?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
                server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
