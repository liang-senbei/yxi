package app.yxi.desktop

import app.yxi.agent.NativeRootVerification
import app.yxi.agent.RewindLiveVerification
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.*

class NativeRootGateTest {
    @Test fun `native root identity survives reload and mixed or changed identities stay blocked`() {
        val root = Files.createTempDirectory("native-root-gate").toFile()
        try {
            val sid = "11111111-1111-4111-8111-111111111111"
            val message = "22222222-2222-4222-8222-222222222222"
            val query = NativeRootVerification.Query(RewindLiveVerification.RuntimeIdentity("cc-demo", "12:\$0:34", "%0",
                "/opt/claude", "56", sid, 1000.0), "/tmp/$sid.jsonl", message, "a".repeat(64))
            val file = root.resolve("gate.json")
            val gate = RewindDeliveryGate(file)
            assertFailsWith<IllegalArgumentException> { gate.beginNativeRoot("task", query.copy(editedTextSha256 = "bad")) }
            assertFalse(file.exists())
            val ticket = gate.beginNativeRoot("task", query)
            val reloaded = RewindDeliveryGate(file)
            assertEquals(query, reloaded.pending("task")?.nativeRoot)
            assertTrue(reloaded.blocked("task"))
            assertFailsWith<IllegalStateException> { reloaded.beginNativeRoot("task", query) }
            assertFailsWith<IllegalStateException> { reloaded.finishVerified(ticket.copy(runtimeId = "other")) }
            val good = file.readText()
            val badRuntime = JSONArray(good)
            badRuntime.getJSONObject(0).getJSONObject("nativeRoot").put("runtimeId", "12:\$1:34")
            file.writeText(badRuntime.toString())
            assertTrue(RewindDeliveryGate(file).blocked("task"))
            assertNull(RewindDeliveryGate(file).pending("task"))
            val mixed = JSONArray(good)
            mixed.getJSONObject(0).put("target", JSONObject().put("session", sid).put("anchor", message).put("message", message))
            file.writeText(mixed.toString())
            assertTrue(RewindDeliveryGate(file).blocked("task"))
            assertNull(RewindDeliveryGate(file).pending("task"))
            file.writeText(good)
            val restored = RewindDeliveryGate(file)
            restored.finishVerified(ticket)
            assertFalse(RewindDeliveryGate(file).blocked("task"))
        } finally { root.deleteRecursively() }
    }
}
