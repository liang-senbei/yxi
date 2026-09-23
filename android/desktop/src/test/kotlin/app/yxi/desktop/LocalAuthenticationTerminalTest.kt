package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class LocalAuthenticationTerminalTest {
    @Test fun `PTY provides terminal input Unicode output and exact nonzero exit status`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val script = File("/sandbox/tmp/auth-pty.py").apply { writeText("""
import os,sys
assert os.isatty(0) and os.isatty(1)
print('READY', flush=True)
text = input()
print('收到:' + text, flush=True)
sys.exit(7)
""".trimIndent()) }
        val plan = AcpTerminalAuthPlan(listOf("/usr/bin/python3", script.path), "/sandbox/home", System.getenv())
        LocalAuthenticationTerminal.start(plan).use { terminal ->
            val ready = AtomicBoolean()
            val reading = async(Dispatchers.IO) {
                val text = StringBuilder(); val buffer = CharArray(256)
                while (true) {
                    val count = terminal.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                    if (text.contains("READY")) ready.set(true)
                }
                text.toString()
            }
            withTimeout(5000) { while (!ready.get()) delay(10) }
            terminal.write("确认\r")
            assertEquals(7, withTimeout(5000) { terminal.awaitExit() })
            assertTrue(withTimeout(5000) { reading.await() }.contains("收到:确认"))
        }
    }
}
