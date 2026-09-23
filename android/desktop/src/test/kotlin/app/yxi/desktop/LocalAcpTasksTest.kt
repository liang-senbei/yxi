package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.*

class LocalAcpTasksTest {
    @TempDir lateinit var root: File
    private class Fixture(val failCreate: Boolean = false) : AcpTransport {
        var closed = false
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val methods = mutableListOf<String>()
        fun options(model: String) = JSONArray().put(JSONObject().put("id", "model").put("name", "Model").put("category", "model").put("type", "select")
            .put("currentValue", model).put("options", JSONArray().put(JSONObject().put("value", "fixture/a").put("name", "A"))
                .put(JSONObject().put("value", "fixture/b").put("name", "B"))))
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); val method = request.getString("method"); methods.add(method)
            if (method == "session/new" && failCreate) return false
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", 1).put("authMethods", JSONArray().put(JSONObject().put("id", "login").put("name", "Native login")))
                "authenticate" -> JSONObject()
                "session/new" -> JSONObject().put("sessionId", "fixture-session").put("configOptions", options("fixture/a"))
                "session/set_config_option" -> JSONObject().put("configOptions", options(request.getJSONObject("params").getString("value")))
                else -> error("Unexpected method")
            }
            pipe.write((JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString() + "\n").toByteArray()); pipe.flush()
            return true
        }
        override fun close() { closed = true; pipe.close(); output.close() }
    }
    @Test fun `abandoning a preparation closes only its client and not an owned conversation`() = runBlocking(Dispatchers.Swing) {
        val fixtures = mutableListOf<Fixture>()
        val runtime = LocalRuntimeInstallation("hermes", "fixture", listOf("fixture"), root.path, "1")
        LocalAcpTasks(InstructionQueue(File(root, "cancel-queue.json")), File(root, "cancel-index.json")) { _, _ ->
            val fixture = Fixture().also { fixtures.add(it) }
            AcpClient(fixture).also { it.initialize() }
        }.use { tasks ->
            tasks.prepare(runtime, root.path)
            tasks.abandonPreparation()
            assertTrue(fixtures.first().closed)
            assertNull(tasks.initialization)
            tasks.prepare(runtime, root.path)
            val record = tasks.create("Owned")
            tasks.abandonPreparation()
            assertFalse(fixtures.last().closed)
            assertTrue(tasks.controllers.getValue(record.key).ready)
        }
        assertTrue(fixtures.all { it.closed })
    }
    @Test fun `all ACP engines persist owned sessions without automatic login or prompt`() = runBlocking(Dispatchers.Swing) {
        for (engine in listOf("gemini", "grok", "hermes")) {
            val fixture = Fixture()
            val index = File(root, "$engine.json")
            val queue = InstructionQueue(File(root, "$engine-queue.json"))
            LocalAcpTasks(queue, index) { _, _ -> AcpClient(fixture).also { it.initialize() } }.use { tasks ->
                val runtime = LocalRuntimeInstallation(engine, "fixture", listOf("fixture"), root.path, "1")
                tasks.prepare(runtime, root.path)
                assertFalse("authenticate" in fixture.methods)
                tasks.authenticate("login")
                val record = tasks.create("My task")
                assertEquals(engine, record.engine)
                assertEquals("native", record.provider)
                assertEquals("fixture/a", record.model)
                assertEquals(record, LocalCodexTaskRegistry(index).records.single())
                assertTrue(tasks.controllers.getValue(record.key).ready)
                tasks.controllers.getValue(record.key).changeConfig("model", "fixture/b")
                assertEquals("fixture/b", LocalCodexTaskRegistry(index).records.single().model)
                assertEquals(record.key, tasks.registry.records.single().key)
                assertEquals("", tasks.recoverySessionId)
                assertFalse("session/prompt" in fixture.methods)
            }
        }
    }
    @Test fun `unconfirmed native creation survives restart and cannot be retried implicitly`() = runBlocking(Dispatchers.Swing) {
        val index = File(root, "unknown.json")
        val queue = InstructionQueue(File(root, "queue.json"))
        val runtime = LocalRuntimeInstallation("hermes", "fixture", listOf("fixture"), root.path, "1")
        LocalAcpTasks(queue, index) { _, _ -> AcpClient(Fixture(true)).also { it.initialize() } }.use { tasks ->
            tasks.prepare(runtime, root.path)
            assertFailsWith<IllegalStateException> { tasks.create("Unknown") }
            assertTrue(tasks.registry.records.isEmpty())
            assertTrue(tasks.recoverySessionId.isNotBlank())
        }
        LocalAcpTasks(queue, index) { _, _ -> error("Must not launch") }.use { recovered ->
            assertTrue(recovered.recoverySessionId.isNotBlank())
            assertFailsWith<IllegalStateException> { recovered.prepare(runtime, root.path) }
        }
        Unit
    }
}
