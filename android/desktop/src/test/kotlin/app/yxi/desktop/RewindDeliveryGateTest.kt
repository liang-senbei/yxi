package app.yxi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import app.yxi.agent.RewindLiveVerification

class RewindDeliveryGateTest {
    @Test fun `resume verification survives restart and stale tickets cannot clear it`() {
        val dir = Files.createTempDirectory("yxi-rewind-verification").toFile()
        try {
            val file = dir.resolve("gate.json")
            val gate = RewindDeliveryGate(file)
            val target = RewindDeliveryGate.Target("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
            val initial = gate.begin("host/task", "1:\$2:3", target)
            val query = RewindLiveVerification.Query("cc-demo", initial.runtimeId, "%1", "/opt/claude", "123",
                target.sessionId, target.anchorUuid, target.messageUuid, "/tmp/${target.sessionId}.jsonl", 1234.5)
            assertFailsWith<IllegalArgumentException> { gate.prepareVerification(initial, query.copy(runtimeId = "1:\$3:4")) }
            val otherSession = "44444444-4444-4444-8444-444444444444"
            assertFailsWith<IllegalArgumentException> { gate.prepareVerification(initial,
                query.copy(sessionId = otherSession, transcriptPath = "/tmp/$otherSession.jsonl")) }
            assertEquals(initial, gate.pending(initial.taskKey))
            val prepared = gate.prepareVerification(initial, query)
            val validDisk = file.readText()
            val corrupted = org.json.JSONArray(validDisk)
            corrupted.getJSONObject(0).getJSONObject("verification")
                .put("sessionId", otherSession).put("transcriptPath", "/tmp/$otherSession.jsonl")
            file.writeText(corrupted.toString())
            assertTrue(RewindDeliveryGate(file).blocked(initial.taskKey))
            assertEquals(null, RewindDeliveryGate(file).pending(initial.taskKey))
            file.writeText(validDisk)
            val restarted = RewindDeliveryGate(file)
            assertEquals(prepared, restarted.pending(initial.taskKey))
            assertTrue(restarted.blocked(initial.taskKey))
            assertFailsWith<IllegalStateException> { restarted.finishVerified(initial) }
            restarted.finishVerified(prepared)
            assertFalse(restarted.blocked(initial.taskKey))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `interrupted rewind stays blocked after restart and only exact ticket clears it`() {
        val dir = Files.createTempDirectory("yxi-rewind-gate").toFile()
        try {
            val file = dir.resolve("gate.json")
            val first = RewindDeliveryGate(file)
            val target = RewindDeliveryGate.Target("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
            val ticket = first.begin("host1/task", "runtime1", target)
            assertTrue(first.blocked("host1/task"))
            assertFalse(first.blocked("host2/task"))
            val restarted = RewindDeliveryGate(file)
            assertTrue(restarted.pending("host1/task")?.target == target)
            assertTrue(restarted.blocked("host1/task"))
            assertFailsWith<IllegalStateException> { restarted.begin("host1/task", "runtime2") }
            assertFailsWith<IllegalStateException> { restarted.finishVerified(ticket.copy(runtimeId = "runtime2")) }
            restarted.finishVerified(ticket)
            assertFalse(RewindDeliveryGate(file).blocked("host1/task"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `unreadable history cannot silently resume sending`() {
        val dir = Files.createTempDirectory("yxi-rewind-gate-corrupt").toFile()
        try {
            val file = dir.resolve("gate.json").apply { writeText("broken") }
            val gate = RewindDeliveryGate(file)
            assertTrue(gate.blocked("any-task"))
            assertFailsWith<IllegalStateException> { gate.begin("any-task", "runtime") }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `old empty backup never clears uncertain state even across two restarts`() {
        val dir = Files.createTempDirectory("yxi-rewind-gate-backup").toFile()
        try {
            val file = dir.resolve("gate.json").apply { writeText("broken") }
            dir.resolve("gate.json.bak").writeText("[]")
            repeat(2) { assertTrue(RewindDeliveryGate(file).blocked("any-task")) }
            assertTrue(file.readText() == "broken")
            file.delete()
            repeat(2) { assertTrue(RewindDeliveryGate(file).blocked("any-task")) }
        } finally { dir.deleteRecursively() }
    }
}
