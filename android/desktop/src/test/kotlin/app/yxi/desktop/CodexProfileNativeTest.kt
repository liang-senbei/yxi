package app.yxi.desktop

import app.yxi.agent.Lines
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class CodexProfileNativeTest {
    @Test fun `real app servers keep profile credentials isolated across resume and configuration change`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        // The test runner's generic ELF mount is named claude; this test mounts the Codex executable there.
        check(File("/opt/native/claude").canExecute())
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "codex-profile-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val configDir = home.resolve(".codex").apply { mkdir() }
        val bin = home.resolve(".local/bin").apply { mkdirs() }
        Files.createSymbolicLink(bin.resolve("codex").toPath(), Path.of("/opt/native/claude"))
        val project = root.resolve("project").apply { mkdir() }
        val global = configDir.resolve("config.toml").apply { writeText("""
model = "fixture-global-model"
approval_policy = "on-request"
sandbox_mode = "read-only"
[projects.${JSONObject.quote(project.path)}]
trust_level = "trusted"
""".trimIndent()) }
        val before = global.readBytes()
        val endpoints = listOf("a", "b").map { root.resolve(it).apply { mkdir() } }
        val servers = endpoints.mapIndexed { i, endpoint ->
            val script = endpoint.resolve("server.py").apply { writeText(CodexProfileNativeTest::class.java
                .getResource("/codex_responses_stub.py")!!.readText()) }
            ProcessBuilder("python3", script.path, endpoint.path, "fixture-codex-key-$i")
                .redirectErrorStream(true).redirectOutput(endpoint.resolve("server.log")).start()
        }
        var workspace: CodexWorkspace? = null
        try {
            repeat(100) { if (endpoints.any { !it.resolve("port").exists() }) Thread.sleep(50) }
            check(endpoints.all { it.resolve("port").exists() } && servers.all { it.isAlive })
            val profiles = endpoints.mapIndexed { index, endpoint -> Lines.Line("profile-$index", "Fixture $index",
                "http://127.0.0.1:${endpoint.resolve("port").readText().trim()}/v1", apiKey = "fixture-codex-key-$index", agent = Lines.CODEX,
                extra = JSONObject().put("model", "fixture-model-$index")) }
            val queue = InstructionQueue(root.resolve("queue.json"))
            val registryFile = root.resolve("tasks.json")
            IsolatedSshBridge(root.resolve("ssh"), mapOf("HOME" to home.path, "CODEX_HOME" to configDir.path,
                "LANG" to "C.UTF-8", "TERM" to "xterm-256color"), root.resolve("unused.sock")).use { bridge ->
                runBlocking { withTimeout(150_000) {
                    bridge.conn.ssh.connect()
                    assertNull(Lines.saveList(bridge.conn.ssh, profiles))
                    val ws = CodexWorkspace(queue, registryFile).also { workspace = it }
                    val a = ws.create(bridge.conn, project.path, "Agent A", profiles[0].id)
                    val b = ws.create(bridge.conn, project.path, "Agent B", profiles[1].id)
                    assertNotEquals(a.threadId, b.threadId)
                    assertNotEquals(a.profileScope, b.profileScope)
                    val controlA = ws.controllers.getValue(a.key).apply { setAutoDispatch(false) }
                    val controlB = ws.controllers.getValue(b.key).apply { setAutoDispatch(false) }
                    assertEquals("fixture-model-0", controlA.configuredModel)
                    assertEquals("fixture-model-1", controlB.configuredModel)
                    suspend fun send(controller: CodexTaskController, text: String) {
                        val item = queue.enqueue(controller.taskKey, text)
                        controller.sendNext(item.id)
                        withTimeout(25000) {
                            while (queue.entries.single { it.id == item.id }.runtimeTurnState == RuntimeTurnState.InProgress || controller.activeTurnId != null) delay(50)
                        }
                        assertEquals(RuntimeTurnState.Completed, queue.entries.single { it.id == item.id }.runtimeTurnState, controller.note)
                    }
                    send(controlA, "CODEX-PROFILE-A")
                    send(controlB, "CODEX-PROFILE-B")
                    fun requests(index: Int) = endpoints[index].resolve("requests.jsonl").readLines().map(::JSONObject)
                    assertTrue(requests(0).all { it.getBoolean("authenticated") && it.getString("model") == "fixture-model-0" })
                    assertTrue(requests(1).all { it.getBoolean("authenticated") && it.getString("model") == "fixture-model-1" })
                    assertFalse(requests(0).any { it.optJSONArray("input").toString().contains("CODEX-PROFILE-B") })
                    val switched = ws.applyCurrentConfiguration(bridge.conn, a, profiles[1].id)
                    assertSame(controlB, ws.controllers.getValue(b.key))
                    assertFalse(switched.autoDispatch)
                    assertEquals(a.threadId, switched.threadId)
                    assertEquals("fixture-model-1", switched.configuredModel)
                    send(switched, "CODEX-PROFILE-A-SWITCHED")
                    val sent = requests(1).last { it.optJSONArray("input").toString().contains("CODEX-PROFILE-A-SWITCHED") }
                    assertTrue(sent.getBoolean("authenticated"))
                    assertTrue(sent.optJSONArray("input").toString().contains("CODEX-PROFILE-A"))
                    assertFalse(sent.optJSONArray("input").toString().contains("CODEX-PROFILE-B"))
                    assertContentEquals(before, global.readBytes())
                    assertFalse(configDir.resolve("auth.json").exists())
                    ws.close()
                    bridge.conn.ssh.disconnect(); bridge.conn.ssh.connect()
                    val reopened = CodexWorkspace(queue, registryFile).also { workspace = it }
                    val savedA = reopened.registry.records.single { it.key == a.key }
                    assertEquals(profiles[1].id, savedA.profileId)
                    assertEquals(a.profileScope, savedA.profileScope)
                    val resumed = reopened.open(bridge.conn, savedA, autoRun = false)
                    assertEquals("fixture-model-1", resumed.configuredModel)
                    assertTrue(resumed.messages.any { it.text.contains("CODEX-PROFILE-A") })
                    send(resumed, "CODEX-PROFILE-A-RECONNECTED")
                    assertTrue(requests(1).last().getBoolean("authenticated"))
                    assertFalse(registryFile.readText().contains("fixture-codex-key"))
                    assertContentEquals(before, global.readBytes())
                    File("/results/codex-profile-proof.json").writeText(JSONObject().put("twoNativeAppServers", true)
                        .put("differentModelsAndKeys", true).put("sameThreadAfterSwitch", true).put("otherAgentUnchanged", true)
                        .put("reconnectKeepsProfile", true).put("globalConfigUnchanged", true).toString(2))
                } }
            }
        } finally {
            workspace?.close()
            servers.forEach { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            endpoints.forEachIndexed { i, endpoint ->
                for (name in listOf("server.log", "requests.jsonl")) endpoint.resolve(name).takeIf { it.isFile }
                    ?.copyTo(File("/results/codex-profile-$i-$name"), overwrite = true)
            }
            root.deleteRecursively()
        }
    }
}
