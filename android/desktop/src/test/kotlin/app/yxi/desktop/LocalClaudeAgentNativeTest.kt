package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalClaudeAgentNativeTest {
    @Test fun `real local Claude accepts stdin and uses existing native provider configuration`() = runBlocking {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val home = File(System.getProperty("user.home")); check(home.canonicalPath == "/sandbox/home")
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "local-claude-").toFile()
        val bin = File(home, ".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "claude").toPath(), Path.of("/opt/native/claude"))
        val script = root.resolve("stub.py").apply { writeText(LocalClaudeAgentNativeTest::class.java.getResource("/rewind/anthropic_stub.py")!!.readText()) }
        val server = ProcessBuilder("python3", script.path, root.path).redirectErrorStream(true).redirectOutput(root.resolve("stub.log")).start()
        val agents = LocalAgents(root.resolve("jobs"))
        try {
            withTimeout(10000) { while (!root.resolve("port").isFile) delay(50) }
            val settings = File(home, ".claude").apply { mkdirs() }.resolve("settings.json")
            settings.writeText(JSONObject().put("env", JSONObject()
                .put("ANTHROPIC_BASE_URL", "http://127.0.0.1:${root.resolve("port").readText().trim()}")
                .put("ANTHROPIC_API_KEY", "sk-ant-yxi-container-test-only")
                .put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1").put("DISABLE_TELEMETRY", "1").put("DISABLE_AUTOUPDATER", "1")).toString())
            val before = settings.readBytes()
            withContext(Dispatchers.Swing) { agents.start("claude", root.path, "KEEP-local-claude 请回复") }
            withTimeout(45000) { while (agents.jobs.single().running) delay(100) }
            val job = agents.jobs.single()
            assertEquals("已完成", job.status, job.output)
            assertTrue(job.output.contains("answer:KEEP-local-claude"), job.output)
            assertContentEquals(before, settings.readBytes())
            val requests = root.resolve("requests.jsonl").readLines().map(::JSONObject)
            assertTrue(requests.any { it.getBoolean("fake_auth") && it.getJSONArray("messages").toString().contains("KEEP-local-claude") })
            assertNotNull(job.sessionId)
            val reopened = LocalAgents(root.resolve("jobs"))
            try {
                val saved = reopened.jobs.single()
                assertEquals(job.sessionId, saved.sessionId)
                withContext(Dispatchers.Swing) { reopened.continueSession(saved, "FOLLOWUP-local-claude") }
                val followup = reopened.jobs.first()
                withTimeout(45000) { while (followup.running) delay(100) }
                assertEquals("已完成", followup.status, followup.output)
                assertEquals(job.sessionId, followup.sessionId)
                val continued = root.resolve("requests.jsonl").readLines().map(::JSONObject).last {
                    it.getJSONArray("messages").toString().contains("FOLLOWUP-local-claude")
                }
                assertTrue(continued.getJSONArray("messages").toString().contains("KEEP-local-claude"))
            } finally { reopened.close() }
        } finally { agents.close(); server.destroyForcibly(); server.waitFor(); root.deleteRecursively() }
    }
}
