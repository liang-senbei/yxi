package app.yxi.desktop

import app.yxi.agent.Rewind
import app.yxi.agent.Model
import app.yxi.ssh.Shell
import org.json.JSONObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real native executable with a loopback protocol stub; requires the dedicated no-network container. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class IsolatedNativeCliTest {
    @Test fun `real CLI persists a conversation and resumes it against the fake endpoint`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val native = File("/opt/native/claude")
        check(native.isFile && native.canExecute()) { "Explicit native executable mount required" }
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "native-cli-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val config = home.resolve(".claude").apply { mkdir() }
        val project = root.resolve("project").apply { mkdir() }
        val script = root.resolve("stub.py")
        javaClass.classLoader.getResourceAsStream("rewind/anthropic_stub.py")!!.use { input -> script.outputStream().use { input.copyTo(it) } }
        val server = ProcessBuilder("python3", script.path, root.path).redirectErrorStream(true).redirectOutput(root.resolve("stub.log")).start()
        val socket = root.resolve("tmux.sock")
        fun isolated(builder: ProcessBuilder): ProcessBuilder = builder.apply {
            environment().apply {
                clear()
                put("HOME", home.path); put("CLAUDE_CONFIG_DIR", config.path)
                put("PATH", "/usr/bin:/bin"); put("LANG", "C.UTF-8"); put("TERM", "xterm-256color")
                put("ANTHROPIC_BASE_URL", "http://127.0.0.1:${root.resolve("port").readText().trim()}")
                put("ANTHROPIC_API_KEY", "sk-ant-yxi-container-test-only")
                put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
                put("DISABLE_TELEMETRY", "1"); put("DISABLE_AUTOUPDATER", "1")
            }
        }
        fun tmux(vararg args: String): String {
            val output = root.resolve("tmux-command.log")
            val p = isolated(ProcessBuilder(listOf("/usr/bin/tmux", "-S", socket.path) + args))
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                check(p.waitFor(10, TimeUnit.SECONDS)) { "Private tmux command timed out" }
                check(p.exitValue() == 0) { output.readText() }
                return output.readText()
            } finally { if (p.isAlive) p.destroyForcibly() }
        }
        fun invoke(label: String, vararg args: String, rewind: Rewind.Plan? = null): JSONObject {
            val output = root.resolve("$label.json")
            val error = root.resolve("$label.err")
            val command = if (rewind == null) listOf(native.path) + args else listOf("/bin/sh", "-c",
                Rewind.command(native.path, project.path, Rewind.Capture(native.path, ""), rewind))
            val builder = isolated(ProcessBuilder(command).directory(project).redirectOutput(output).redirectError(error))
            val process = builder.start()
            process.outputStream.close()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Native CLI timeout: $label" }
                assertEquals(0, process.exitValue(), error.readText().takeLast(1500))
                val raw = output.readText()
                if (rewind != null) {
                    assertEquals(Rewind.Outcome.Ok(rewind.sessionId, "ok"), Rewind.parse(raw), raw.takeLast(1500))
                }
                val result = JSONObject(if (rewind == null) raw else raw.lineSequence().last { it.startsWith("{") })
                assertFalse(result.optBoolean("is_error"), output.readText().takeLast(1000))
                assertEquals("ok", result.getString("result"))
                return result
            } finally {
                process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                if (process.isAlive) process.destroyForcibly()
            }
        }
        try {
            repeat(50) { if (!root.resolve("port").isFile && server.isAlive) Thread.sleep(100) }
            check(root.resolve("port").isFile && server.isAlive) { "Loopback stub failed to start" }
            val first = invoke("first", "-p", "KEEP-container-first", "--output-format", "json")
            val sid = first.getString("session_id")
            val second = invoke("second", "--resume", sid, "-p", "FOLLOWUP-container-second", "--output-format", "json")
            assertEquals(sid, second.getString("session_id"))
            val requests = root.resolve("requests.jsonl").readLines().map(::JSONObject)
            val followup = requests.last { it.getJSONArray("messages").toString().contains("FOLLOWUP-container-second") }
            assertTrue(followup.getBoolean("fake_auth"))
            assertTrue(followup.getJSONArray("messages").toString().contains("KEEP-container-first"))

            // Select the actual persisted parent UUID, never infer a turn from its display index.
            val transcript = config.walkTopDown().single { it.isFile && it.name == "$sid.jsonl" }
            val records = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            val selected = records.last { it.optString("type") == "user" &&
                it.optJSONObject("message")?.toString()?.contains("FOLLOWUP-container-second") == true }
            val anchor = selected.getString("parentUuid")
            invoke("third", "--resume", sid, "-p", "DROP-container-third", "--output-format", "json")
            val edited = invoke("edited", rewind = Rewind.Plan(sid, anchor, selected.getString("uuid"),
                prompt = "EDIT-container-second"))
            assertEquals(sid, edited.getString("session_id"))
            invoke("after-edit", "--resume", sid, "-p", "VERIFY-container-branch", "--output-format", "json")
            val allRequests = root.resolve("requests.jsonl").readLines().map(::JSONObject)
            for (marker in listOf("EDIT-container-second", "VERIFY-container-branch")) {
                val payload = allRequests.last { it.getJSONArray("messages").toString().contains(marker) }
                assertTrue(payload.getBoolean("fake_auth"))
                val messages = payload.getJSONArray("messages").toString()
                assertTrue(messages.contains("KEEP-container-first"), "Retained ancestor missing: $marker")
                assertTrue(messages.contains("EDIT-container-second"), "Edited turn missing: $marker")
                assertFalse(messages.contains("FOLLOWUP-container-second"), "Replaced turn leaked: $marker")
                assertFalse(messages.contains("DROP-container-third"), "Abandoned descendant leaked: $marker")
            }

            // The product uses the CLI's stricter drops-turn guard when editing the last turn.
            val lastTurn = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .last { it.optString("type") == "user" &&
                    it.optJSONObject("message")?.toString()?.contains("VERIFY-container-branch") == true }
            val literal = "SINGLE-container-edit 'quoted' ${'$'}(touch ${root.path}/unexpected)\n中文🙂"
            invoke("single-edit", rewind = Rewind.Plan(sid, lastTurn.getString("parentUuid"),
                lastTurn.getString("uuid"), lastTurn.getString("uuid"), literal))
            invoke("after-single-edit", "--resume", sid, "-p", "VERIFY-container-single", "--output-format", "json")
            val singlePayload = root.resolve("requests.jsonl").readLines().map(::JSONObject).last()
                .getJSONArray("messages").toString()
            assertTrue(singlePayload.contains("SINGLE-container-edit"))
            assertTrue(singlePayload.contains("EDIT-container-second"))
            assertFalse(singlePayload.contains("VERIFY-container-branch"))
            assertFalse(root.resolve("unexpected").exists(), "Prompt must remain literal shell data")

            // Only this disposable project is trusted; no permission-mode bypass is used.
            val preferences = config.resolve(".claude.json")
            val prefs = if (preferences.exists()) JSONObject(preferences.readText()) else JSONObject()
            prefs.put("hasCompletedOnboarding", true).put("lastOnboardingVersion", "2.1.278")
                .put("projects", JSONObject().put(project.path, JSONObject().put("hasTrustDialogAccepted", true)))
                .put("customApiKeyResponses", JSONObject().put("approved",
                    org.json.JSONArray().put("sk-ant-yxi-container-test-only".takeLast(20))).put("rejected", org.json.JSONArray()))
            preferences.writeText(prefs.toString())
            tmux("-f", "/dev/null", "new-session", "-d", "-s", "cc-native-check", "-x", "180", "-y", "50",
                "-c", project.path, "${Shell.q(native.path)} --resume ${Shell.q(sid)}")
            assertEquals(socket.path, tmux("display-message", "-p", "-t", "=cc-native-check:", "#{socket_path}").trim())
            var screen = ""
            var ready = false
            repeat(100) {
                if (!ready) {
                    screen = tmux("capture-pane", "-p", "-t", "=cc-native-check:")
                    ready = Model.borrowable(screen)
                    if (!ready) Thread.sleep(100)
                }
            }
            root.resolve("interactive-screen.txt").writeText(screen)
            assertTrue(ready, "Real resumed CLI must reach an empty input prompt: $screen")
            tmux("send-keys", "-t", "=cc-native-check:", "-l", "--", "INTERACTIVE-container-followup")
            tmux("send-keys", "-t", "=cc-native-check:", "Enter")
            var interactive: JSONObject? = null
            repeat(100) {
                if (interactive == null) {
                    interactive = root.resolve("requests.jsonl").readLines().mapNotNull {
                        runCatching { JSONObject(it) }.getOrNull()
                    }.lastOrNull { it.getJSONArray("messages").toString().contains("INTERACTIVE-container-followup") }
                    if (interactive == null) Thread.sleep(100)
                }
            }
            val interactiveMessages = requireNotNull(interactive) { "Interactive prompt never reached the stub" }
                .getJSONArray("messages").toString()
            assertTrue(interactiveMessages.contains("KEEP-container-first"))
            assertTrue(interactiveMessages.contains("SINGLE-container-edit"))
            assertFalse(interactiveMessages.contains("FOLLOWUP-container-second"))
            assertFalse(interactiveMessages.contains("DROP-container-third"))
            assertFalse(interactiveMessages.contains("VERIFY-container-branch"))
        } finally {
            if (socket.exists()) runCatching { tmux("kill-session", "-t", "=cc-native-check:") }
            server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS)
            root.listFiles().orEmpty().filter { it.isFile && it.name != "stub.py" }.forEach {
                it.copyTo(File("/results/native-${it.name}"), overwrite = true)
            }
        }
    }
}
