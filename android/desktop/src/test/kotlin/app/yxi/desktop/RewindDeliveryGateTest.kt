package app.yxi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RewindDeliveryGateTest {
    @Test fun `interrupted rewind stays blocked after restart and only exact ticket clears it`() {
        val dir = Files.createTempDirectory("yxi-rewind-gate").toFile()
        try {
            val file = dir.resolve("gate.json")
            val first = RewindDeliveryGate(file)
            val ticket = first.begin("host1/task", "runtime1")
            assertTrue(first.blocked("host1/task"))
            assertFalse(first.blocked("host2/task"))
            val restarted = RewindDeliveryGate(file)
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
