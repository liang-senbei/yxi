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
        override val output = PipedInputStream(65536)
        private val pipe = PipedOutputStream(output)
        val methods = mutableListOf<String>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); val method = request.getString("method"); methods.add(method)
            if (method == "session/new" && failCreate) return false
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", 1).put("authMethods", JSONArray().put(JSONObject().put("id", "login").put("name", "Native login")))
                "authenticate" -> JSONObject()
                "session/new" -> JSONObject().put("sessionId", "fixture-session")
                else -> error("Unexpected method")
            }
            pipe.write((JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString() + "\n").toByteArray()); pipe.flush()
            return true
        }
        override fun close() { pipe.close(); output.close() }
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
                assertEquals(record, LocalCodexTaskRegistry(index).records.single())
                assertTrue(tasks.controllers.getValue(record.key).ready)
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
    }
}
