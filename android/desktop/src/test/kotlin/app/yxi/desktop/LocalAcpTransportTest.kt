package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LocalAcpTransportTest {
    @Test fun `ACP runtimes launch with separate argv and close only their owned process`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        val root = Files.createTempDirectory("acp-process-").toFile()
        val script = File(root, "fake.py").apply { writeText("""
import sys, json, os
for line in sys.stdin:
    request = json.loads(line)
    result = {'protocolVersion': 1, 'authMethods': [], 'fixture': {'args': sys.argv[1:], 'cwd': os.getcwd(), 'geminiHome': os.environ.get('GEMINI_CLI_HOME'), 'hermesHome': os.environ.get('HERMES_HOME')}}
    print(json.dumps({'jsonrpc':'2.0', 'id':request['id'], 'result':result}), flush=True)
""".trimIndent()) }
        val unrelated = ProcessBuilder("/bin/sleep", "60").start()
        try {
            for (engine in listOf("gemini", "hermes", "grok")) {
                val home = File(root, if (engine == "gemini") "space home/.gemini" else engine)
                val runtime = LocalRuntimeInstallation(engine, "fixture", listOf("/usr/bin/python3", script.path), home.path, "1.0.0")
                val transport = LocalAcpTransport.start(runtime, root)
                val pid = transport.processId
                AcpClient(transport).use { client ->
                    val response = client.initialize().getJSONObject("fixture")
                    val args = response.getJSONArray("args").let { values -> (0 until values.length()).map { values.getString(it) } }
                    assertEquals(AcpLaunch.arguments(engine), args)
                    assertEquals(root.canonicalPath, response.getString("cwd"))
                    if (engine == "gemini") assertEquals(home.parent, response.getString("geminiHome"))
                    if (engine == "hermes") assertEquals(home.path, response.getString("hermesHome"))
                }
                repeat(50) { if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(20) }
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
                assertTrue(unrelated.isAlive)
            }
        } finally { unrelated.destroyForcibly(); unrelated.waitFor(); script.delete(); root.delete() }
    }
}
