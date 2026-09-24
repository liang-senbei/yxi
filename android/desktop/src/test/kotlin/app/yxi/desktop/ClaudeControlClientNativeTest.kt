package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import org.json.JSONObject
import kotlin.test.*

class ClaudeControlClientNativeTest {
    @Test fun `production control client reads native effective settings without model messages`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank() && File("/.dockerenv").exists())
        val root = File("/sandbox/tmp/claude-control-client").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val stub = File(root, "server.py").apply { writeText(ClaudeControlClientNativeTest::class.java.getResource("/rewind/anthropic_stub.py")!!.readText()) }
        val server = ProcessBuilder("python3", stub.path, root.path).redirectErrorStream(true).redirectOutput(File(root, "server.log")).start()
        try {
            val port = File(root, "port")
            withTimeout(5000) { while (!port.exists()) delay(20) }
            val endpoint = "http://127.0.0.1:${port.readText().trim()}"
            val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("/opt/native/claude"), File(home, ".claude").path, "2.1.280")
            val transport = LocalClaudeControlTransport.start(runtime, home, mapOf("HOME" to home.path, "PATH" to "/usr/bin:/bin",
                "CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-control-token", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1"),
                ClaudeSubscriptionSettings.overlay().apply { getJSONObject("env").put("ANTHROPIC_BASE_URL", endpoint) })
            val pid = transport.processId
            ClaudeControlClient(transport).use { client ->
                client.initialize()
                val settings = client.settings()
                assertEquals(endpoint, settings.getJSONObject("effective").getJSONObject("env").getString("ANTHROPIC_BASE_URL"))
                File("/results/claude-client-effective-settings.json").writeText(settings.toString(2))
                assertFailsWith<IllegalStateException> { ClaudeSubscriptionSettings.requireOfficialRoute(settings) }
            }
            withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) }
            var rejectedPid = 0L
            val preparation = LocalClaudeSubscription { selected, directory ->
                val rejected = LocalClaudeControlTransport.start(selected, directory, mapOf("HOME" to home.path, "PATH" to "/usr/bin:/bin",
                    "CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-control-token", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1"),
                    ClaudeSubscriptionSettings.overlay().apply { getJSONObject("env").put("ANTHROPIC_BASE_URL", endpoint) })
                rejectedPid = rejected.processId
                ClaudeControlClient(rejected)
            }
            val failure = assertFailsWith<IllegalStateException> { preparation.prepare(runtime, home) }
            assertTrue(failure.message.orEmpty().contains("官方端点"))
            withTimeout(5000) { while (ProcessHandle.of(rejectedPid).map { it.isAlive }.orElse(false)) delay(20) }
            val requests = File(root, "requests.jsonl")
            if (requests.exists()) assertTrue(requests.readLines().map(::JSONObject).all { it.getJSONArray("messages").length() == 0 })
            val conversation = LocalClaudeControlTransport.start(runtime, home, mapOf("HOME" to home.path, "PATH" to "/usr/bin:/bin",
                "CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-control-token", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1"),
                ClaudeSubscriptionSettings.overlay().apply { getJSONObject("env").put("ANTHROPIC_BASE_URL", endpoint) })
            val conversationPid = conversation.processId
            ClaudeControlClient(conversation).use { client ->
                client.initialize()
                val first = client.prompt("KEEP-control-first", 30000)
                assertFalse(first.getBoolean("is_error")); assertTrue(first.getString("result").contains("answer:KEEP-control-first"))
                val second = client.prompt("FOLLOWUP-control-second", 30000)
                assertFalse(second.getBoolean("is_error")); assertTrue(second.getString("result").contains("answer:FOLLOWUP-control-second"))
                assertEquals(first.getString("session_id"), second.getString("session_id"))
                assertEquals(second.getString("session_id"), client.sessionId)
                File("/results/claude-control-second-turn.json").writeText(second.toString(2))
            }
            withTimeout(5000) { while (ProcessHandle.of(conversationPid).map { it.isAlive }.orElse(false)) delay(20) }
            val turns = requests.readLines().map(::JSONObject)
            val secondRequest = turns.last { it.getJSONArray("messages").toString().contains("FOLLOWUP-control-second") }
            assertTrue(secondRequest.getJSONArray("messages").toString().contains("KEEP-control-first"))
        } finally { server.destroyForcibly(); server.waitFor() }
    }
}
