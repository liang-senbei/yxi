package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.*

class ClaudeSessionLeaseTest {
    @TempDir lateinit var root: File
    @Test fun `same native identity is exclusive until owner releases and other homes remain independent`() {
        val locks = File(root, "locks"); val home = File(root, "native"); val id = UUID.randomUUID().toString()
        val owner = ClaudeSessionLease.acquire(locks, home, id)
        try {
            assertFailsWith<IllegalStateException> { ClaudeSessionLease.acquire(locks, File(home, "."), id) }
            ClaudeSessionLease.acquire(locks, File(root, "other"), id).close()
            ClaudeSessionLease.acquire(locks, home, UUID.randomUUID().toString()).close()
        } finally { owner.close(); owner.close() }
        ClaudeSessionLease.acquire(locks, home, id).close()
        assertFailsWith<IllegalArgumentException> { ClaudeSessionLease.acquire(locks, home, "invalid") }
    }
}
