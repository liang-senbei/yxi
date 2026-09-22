package app.yxi.desktop

import app.yxi.agent.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class ConversationRouteApplyTest {
    @Test fun `two real native agents in one project retain separate routes and original histories`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val native = File("/opt/native/claude").also { check(it.isFile && it.canExecute()) }
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "route-apply-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val config = home.resolve(".claude").apply { mkdir() }
        val project = root.resolve("project").apply { mkdir() }
        val socket = root.resolve("tmux.sock")
        val endpoints = listOf("a", "b").map { root.resolve(it).apply { mkdir() } }
        val originalStub = javaClass.getResource("/rewind/anthropic_stub.py")!!.readText()
        val servers = endpoints.mapIndexed { index, endpoint ->
            val script = endpoint.resolve("stub.py").apply { writeText(originalStub
                .replace("sk-ant-yxi-container-test-only", "fixture-key-$index")
                .replace("\"messages\": body.get(\"messages\", []),", "\"messages\": body.get(\"messages\", []), \"model\": body.get(\"model\"),")) }
            ProcessBuilder("python3", script.path, endpoint.path).redirectErrorStream(true)
                .redirectOutput(endpoint.resolve("server.log")).start()
        }
        fun environment() = mapOf("HOME" to home.path, "CLAUDE_CONFIG_DIR" to config.path,
            "PATH" to "/usr/bin:/bin", "LANG" to "C.UTF-8", "TERM" to "xterm-256color",
            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:${endpoints[0].resolve("port").readText().trim()}",
            "ANTHROPIC_API_KEY" to "fixture-key-0", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "DISABLE_TELEMETRY" to "1", "DISABLE_AUTOUPDATER" to "1")
        fun run(vararg args: String): String {
            val out = root.resolve("command-${System.nanoTime()}.log")
            val builder = ProcessBuilder(*args).directory(project).redirectErrorStream(true).redirectOutput(out)
            builder.environment().apply { clear(); putAll(environment()) }
            val process = builder.start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Fixture command timed out" }
                check(process.exitValue() == 0) { out.readText().takeLast(3000) }
                return out.readText()
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        fun tm(vararg args: String) = run("tmux", "-S", socket.path, *args)
        fun screen(name: String) = tm("capture-pane", "-p", "-t", "=$name:")
        fun ready(name: String) {
            repeat(150) {
                val text = screen(name)
                if (Model.borrowable(text) && PermissionMode.fromScreen(text) == PermissionMode.Manual) {
                    Thread.sleep(350); return
                }
                Thread.sleep(100)
            }
            error("Native prompt not ready: ${screen(name)}")
        }
        fun request(endpoint: File, marker: String): JSONObject? = endpoint.resolve("requests.jsonl")
            .takeIf { it.exists() }?.readLines()?.map(::JSONObject)?.lastOrNull {
                !it.getString("path").contains("count_tokens") && it.getJSONArray("messages").toString().contains(marker)
            }
        try {
            repeat(100) { if (endpoints.any { !it.resolve("port").exists() }) Thread.sleep(50) }
            check(endpoints.all { it.resolve("port").exists() } && servers.all { it.isAlive })
            config.resolve(".claude.json").writeText(JSONObject().put("hasCompletedOnboarding", true)
                .put("lastOnboardingVersion", "2.1.278")
                .put("projects", JSONObject().put(project.path, JSONObject().put("hasTrustDialogAccepted", true)))
                .put("customApiKeyResponses", JSONObject().put("approved", JSONArray().put("fixture-key-0").put("fixture-key-1"))
                    .put("rejected", JSONArray())).toString())
            val global = config.resolve("settings.json")
            global.writeText(JSONObject().put("env", JSONObject().put("ANTHROPIC_BASE_URL", environment().getValue("ANTHROPIC_BASE_URL"))
                .put("ANTHROPIC_API_KEY", "fixture-key-0").put("ANTHROPIC_DEFAULT_OPUS_MODEL", "old-mapping")).toString())
            val beforeGlobal = global.readBytes()
            val ids = listOf("a", "b").associateWith { label ->
                JSONObject(run(native.path, "-p", "ROOT-agent-$label", "--output-format", "json")).getString("session_id")
            }
            for ((label, sid) in ids) {
                tm("-f", "/dev/null", "new-session", "-d", "-s", "cc-route-$label", "-x", "180", "-y", "50", "-c", project.path,
                    "exec ${Shell.q(native.path)} --resume ${Shell.q(sid)} --permission-mode manual")
                ready("cc-route-$label")
            }
            val bPid = tm("display-message", "-p", "-t", "=cc-route-b:", "#{pane_pid}").trim()
            IsolatedSshBridge(root.resolve("ssh"), environment(), socket).use { bridge ->
                runBlocking { withTimeout(90_000) {
                    bridge.conn.ssh.connect()
                    val a = SessionProbe.snapshot(bridge.conn.ssh).single { it.name == "cc-route-a" }
                    val line = Lines.Line("route-b", "Fixture B", "http://127.0.0.1:${endpoints[1].resolve("port").readText().trim()}",
                        apiKey = "fixture-key-1", extra = JSONObject().put("env", JSONObject().put("ANTHROPIC_MODEL", "fixture-route-b")))
                    val receipt = ConversationRouteApply.apply(bridge.conn, a, line)
                    assertEquals(ids.getValue("a"), receipt.sessionId)
                    assertTrue(receipt.processIdentity.matches(Regex("[0-9a-f-]+:[0-9]+:[0-9]+")))
                    val privateSettings = JSONObject(File(receipt.settingsPath).readText())
                    assertEquals("", privateSettings.getJSONObject("env").getString("ANTHROPIC_DEFAULT_OPUS_MODEL"))
                    assertContentEquals(beforeGlobal, global.readBytes())
                    assertEquals(bPid, tm("display-message", "-p", "-t", "=cc-route-b:", "#{pane_pid}").trim())
                    ready("cc-route-a")
                    for (label in listOf("a", "b")) {
                        tm("send-keys", "-t", "=cc-route-$label:", "-l", "--", "VERIFY-agent-$label")
                        tm("send-keys", "-t", "=cc-route-$label:", "Enter")
                    }
                    repeat(150) { if (request(endpoints[1], "VERIFY-agent-a") == null || request(endpoints[0], "VERIFY-agent-b") == null) delay(100) }
                    val sentA = requireNotNull(request(endpoints[1], "VERIFY-agent-a"))
                    val sentB = requireNotNull(request(endpoints[0], "VERIFY-agent-b"))
                    assertTrue(sentA.getBoolean("fake_auth")); assertTrue(sentB.getBoolean("fake_auth"))
                    assertEquals("fixture-route-b", sentA.getString("model"))
                    assertTrue(sentA.getJSONArray("messages").toString().contains("ROOT-agent-a"))
                    assertFalse(sentA.getJSONArray("messages").toString().contains("ROOT-agent-b"))
                    assertNull(request(endpoints[0], "VERIFY-agent-a"))
                    assertNull(request(endpoints[1], "VERIFY-agent-b"))
                    assertContentEquals(beforeGlobal, global.readBytes())
                    File("/results/route-isolation-proof.json").writeText(JSONObject().put("originalSessionPreserved", true)
                        .put("otherProcessUnchanged", true).put("separateEndpointsAndKeys", true)
                        .put("globalSettingsUnchanged", true).put("model", sentA.getString("model")).toString(2))
                } }
            }
        } finally {
            if (socket.exists()) runCatching { tm("kill-server") } // private disposable socket inside network-none container only
            servers.forEach { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            endpoints.forEachIndexed { i, endpoint -> endpoint.resolve("server.log").takeIf { it.exists() }
                ?.copyTo(File("/results/route-stub-$i.log"), overwrite = true) }
            root.deleteRecursively()
        }
    }
}
