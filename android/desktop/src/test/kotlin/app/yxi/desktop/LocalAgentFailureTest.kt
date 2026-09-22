package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.condition.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Controlled CLI protocol failures; real native success/resume is covered separately. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalAgentFailureTest {
    @Test fun `zero exit failures and incomplete output remain truthful after reopening`() = runBlocking {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val home = File(System.getProperty("user.home")); check(home.path == "/sandbox/home")
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "local-outcomes-").toFile()
        val cli = File(home, ".local/bin/codex").apply { parentFile.mkdirs() }
        cli.writeText("""#!/usr/bin/python3
import json, pathlib, sys
prompt = sys.stdin.read()
with pathlib.Path('deliveries.txt').open('a') as f: f.write(prompt + '\n')
print(json.dumps({'type':'thread.started','thread_id':'12345678-1234-1234-1234-123456789abc'}))
if prompt == 'fail': print(json.dumps({'type':'turn.failed','error':{'message':'fixture provider failure'}}))
""")
        check(cli.setExecutable(true))
        val store = LocalAgents(root.resolve("jobs"))
        try {
            for (prompt in listOf("fail", "missing")) {
                withContext(Dispatchers.Swing) { store.start("codex", root.path, prompt) }
                val job = store.jobs.first()
                withTimeout(10000) { while (job.running) delay(25) }
                assertEquals(if (prompt == "fail") "运行器报告失败" else "结果未确认", job.status)
                assertFailsWith<IllegalArgumentException> { store.continueSession(job, "must-not-send") }
            }
            val reopened = LocalAgents(root.resolve("jobs"))
            try {
                assertEquals(2, reopened.jobs.size)
                assertTrue(reopened.jobs.single { it.prompt == "fail" }.output.contains("fixture provider failure"))
                assertTrue(reopened.jobs.single { it.prompt == "missing" }.output.contains("未收到完整"))
                assertTrue(reopened.jobs.none { it.running || it.status == "已完成" })
                assertEquals(listOf("fail", "missing"), root.resolve("deliveries.txt").readLines())
            } finally { reopened.close() }
        } finally { store.close(); root.deleteRecursively() }
    }
}
