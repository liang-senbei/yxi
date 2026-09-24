package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
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
            ClaudeControlClient(conversation, conversation.requestedSessionId).use { client ->
                client.initialize()
                val first = client.prompt("KEEP-control-first", 30000)
                assertEquals(conversation.requestedSessionId, first.getString("session_id"))
                assertFalse(first.getBoolean("is_error")); assertTrue(first.getString("result").contains("answer:KEEP-control-first"))
                val second = client.prompt("FOLLOWUP-control-second", 30000)
                assertFalse(second.getBoolean("is_error")); assertTrue(second.getString("result").contains("answer:FOLLOWUP-control-second"))
                assertEquals(first.getString("session_id"), second.getString("session_id"))
                assertEquals(second.getString("session_id"), client.sessionId)
                File("/results/claude-control-second-turn.json").writeText(second.toString(2))
                for (allow in listOf(true, false)) {
                    val marker = if (allow) "KEEP-control-permission" else "FOLLOWUP-control-permission"
                    val file = File(root, if (allow) "approved-control.txt" else "denied-control.txt")
                    val turn = async { client.prompt(marker, 30000) }
                    withTimeout(10000) { while (client.pendingPermissions().isEmpty() && !turn.isCompleted) delay(20) }
                    assertFalse(file.exists(), "Bash must not execute before approval")
                    val permission = client.pendingPermissions().single()
                    assertEquals("Bash", permission.getJSONObject("request").getString("tool_name"))
                    client.answerPermission(permission.getString("request_id"), allow)
                    val result = turn.await()
                    assertFalse(result.getBoolean("is_error"))
                    assertEquals(allow, file.exists())
                    if (allow) assertEquals("allowed", file.readText())
                    assertTrue(client.pendingPermissions().isEmpty())
                    File("/results/claude-control-permission-$allow.json").writeText(result.toString(2))
                }
            }
            withTimeout(5000) { while (ProcessHandle.of(conversationPid).map { it.isAlive }.orElse(false)) delay(20) }
            val turns = requests.readLines().map(::JSONObject)
            val secondRequest = turns.last { it.getJSONArray("messages").toString().contains("FOLLOWUP-control-second") }
            assertTrue(secondRequest.getJSONArray("messages").toString().contains("KEEP-control-first"))
            val controlled = LocalClaudeControlTransport.start(runtime, home, mapOf("HOME" to home.path, "PATH" to "/usr/bin:/bin",
                "CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-control-token", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1"),
                ClaudeSubscriptionSettings.overlay().apply { getJSONObject("env").put("ANTHROPIC_BASE_URL", endpoint) })
            val controlledPid = controlled.processId
            val disk = File(root, "controller-queue.json")
            withContext(Dispatchers.Swing) {
                val client = ClaudeControlClient(controlled); client.initialize()
                ClaudeTaskController("native-controller", client, InstructionQueue(disk), beforeSend = {
                    assertEquals(endpoint, client.settings().getJSONObject("effective").getJSONObject("env").getString("ANTHROPIC_BASE_URL"))
                }).use { controller ->
                    controller.enqueue("KEEP-controller-durable"); controller.sendNext()
                    assertTrue(controller.messages.any { it.role == "Assistant" && it.text.contains("answer:KEEP-controller-durable") })
                    controller.enqueue("FOLLOWUP-control-permission")
                    val pending = async { controller.sendNext() }
                    withTimeout(10000) { while (controller.pendingApprovals.isEmpty() && !pending.isCompleted) delay(20) }
                    controller.answerPermission(controller.pendingApprovals.keys.single(), false)
                    pending.await()
                    assertTrue(controller.pendingApprovals.isEmpty())
                    assertFalse(File(root, "denied-control.txt").exists())
                    val saved = InstructionQueue(disk).entries
                    assertEquals(2, saved.size)
                    assertTrue(saved.all { it.status == InstructionStatus.Accepted && it.runtimeTurnState == RuntimeTurnState.Completed })
                    assertEquals(2, saved.map { it.runtimeTurnId }.distinct().size)
                    disk.copyTo(File("/results/claude-controller-queue.json"), overwrite = true)
                }
            }
            withTimeout(5000) { while (ProcessHandle.of(controlledPid).map { it.isAlive }.orElse(false)) delay(20) }
        } finally { server.destroyForcibly(); server.waitFor() }
    }
}
