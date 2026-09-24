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
    @Test fun `independent process exclusion survives until normal or forced process exit`() {
        val source = File(root, "LeaseHolder.java").apply { writeText("""
            import java.nio.channels.*;
            import java.nio.file.*;
            class LeaseHolder {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                         FileLock lock = channel.lock()) {
                        System.out.println("READY"); System.out.flush();
                        System.in.read();
                    }
                }
            }
        """.trimIndent()) }
        val javaExecutable = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        for (forced in listOf(false, true)) {
            val directory = File(root, "locks-$forced"); val home = File(root, "native"); val id = UUID.randomUUID().toString()
            ClaudeSessionLease.acquire(directory, home, id).close()
            val lockFile = directory.listFiles()!!.single()
            val process = ProcessBuilder(javaExecutable.path, "--source", "17", source.path, lockFile.path)
                .redirectError(File(root, "holder-$forced.stderr")).start()
            try {
                val ready = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
                assertEquals("READY", ready.get(15, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(process.isAlive)
                assertFailsWith<IllegalStateException> { ClaudeSessionLease.acquire(directory, home, id) }
                if (forced) process.destroyForcibly() else { process.outputStream.write(10); process.outputStream.flush() }
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                if (!forced) assertEquals(0, process.exitValue())
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                var attempts = 0
                while (true) {
                    try { ClaudeSessionLease.acquire(directory, home, id).close(); break }
                    catch (e: IllegalStateException) {
                        if (System.nanoTime() >= deadline) throw AssertionError("Lock remained held after exit; forced=$forced", e)
                        attempts++; Thread.sleep(20)
                    }
                }
                println("Owner exit forced=$forced; lock release retries=$attempts")
                assertTrue(lockFile.exists(), "Keep the shared lock inode after release")
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                process.inputStream.close(); process.outputStream.close(); process.errorStream.close()
            }
        }
    }

}
