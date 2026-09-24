package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.*

class LocalClaudeSubscriptionTest {
    @TempDir lateinit var root: File
    private class Fixture(val conflict: String) : ClaudeControlTransport {
        override val output = PipedInputStream(65536)
        private val producer = PipedOutputStream(output)
        var closed = false
        val methods = mutableListOf<String>()
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text)
            assertEquals("control_request", request.getString("type"))
            val method = request.getJSONObject("request").getString("subtype"); methods.add(method)
            val value = if (method == "initialize") JSONObject().put("account", JSONObject()
                .put("apiProvider", "firstParty").put("tokenSource", "claude.ai").apply {
                    if (conflict == "identity") put("apiKeySource", "ANTHROPIC_API_KEY")
                }) else JSONObject().put("effective", ClaudeSubscriptionSettings.overlay().apply {
                    if (conflict == "endpoint") getJSONObject("env").put("ANTHROPIC_BASE_URL", "https://other.example")
                })
            producer.write((JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
                .put("request_id", request.getString("request_id")).put("response", value)).toString() + "\n").toByteArray())
            producer.flush(); return true
        }
        override fun close() { closed = true; producer.close(); output.close() }
    }
    @Test fun `prepared connection verifies identity and effective endpoint before handing off`(): Unit = runBlocking {
        val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("fixture"), root.path, "1")
        val fixture = Fixture("")
        val manager = LocalClaudeSubscription { selected, directory ->
            assertSame(runtime, selected); assertEquals(root.canonicalFile, directory)
            ClaudeControlClient(fixture)
        }
        manager.prepare(runtime, root).use { prepared ->
            assertFalse(fixture.closed)
            assertEquals(listOf("initialize", "get_settings"), fixture.methods)
            ClaudeSubscriptionSettings.requireOfficialRoute(prepared.settings)
        }
        assertTrue(fixture.closed)
    }
    @Test fun `identity and endpoint conflicts close the process without any model prompt`(): Unit = runBlocking {
        val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("fixture"), root.path, "1")
        for (conflict in listOf("identity", "endpoint")) {
            val fixture = Fixture(conflict)
            assertFailsWith<IllegalStateException> { LocalClaudeSubscription { _, _ -> ClaudeControlClient(fixture) }.prepare(runtime, root) }
            assertTrue(fixture.closed)
            assertEquals(if (conflict == "identity") listOf("initialize") else listOf("initialize", "get_settings"), fixture.methods)
        }
    }
}
