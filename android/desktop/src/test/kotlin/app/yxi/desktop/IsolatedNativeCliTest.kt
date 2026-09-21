package app.yxi.desktop

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
        fun invoke(label: String, vararg args: String): JSONObject {
            val output = root.resolve("$label.json")
            val error = root.resolve("$label.err")
            val port = root.resolve("port").readText().trim()
            val builder = ProcessBuilder(listOf(native.path) + args).directory(project).redirectOutput(output).redirectError(error)
            builder.environment().apply {
                clear()
                put("HOME", home.path); put("CLAUDE_CONFIG_DIR", config.path)
                put("PATH", "/usr/bin:/bin"); put("LANG", "C.UTF-8"); put("TERM", "dumb")
                put("ANTHROPIC_BASE_URL", "http://127.0.0.1:$port")
                put("ANTHROPIC_API_KEY", "sk-ant-yxi-container-test-only")
                put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
                put("DISABLE_TELEMETRY", "1"); put("DISABLE_AUTOUPDATER", "1")
            }
            val process = builder.start()
            process.outputStream.close()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Native CLI timeout: $label" }
                assertEquals(0, process.exitValue(), error.readText().takeLast(1500))
                val result = JSONObject(output.readText())
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
        } finally {
            server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS)
            root.listFiles().orEmpty().filter { it.isFile && it.name != "stub.py" }.forEach {
                it.copyTo(File("/results/native-${it.name}"), overwrite = true)
            }
        }
    }
}
