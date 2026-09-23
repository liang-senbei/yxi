package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LocalOpenCodeServerTest {
    private fun fixture(mode: String, block: suspend (LocalRuntimeInstallation, File) -> Unit) = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank()) { "Process tests require the isolated container" }
        val dir = Files.createTempDirectory("opencode-owned-").toFile()
        val script = File(dir, "runner.py")
        script.writeText("""
import os, sys, time, json, base64
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
Path('pid').write_text(str(os.getpid()))
assert sys.argv[2:] == ['serve', '--hostname', '127.0.0.1', '--port', '0', '--no-mdns']
assert os.environ['OPENCODE_SERVER_USERNAME'] == 'opencode'
assert len(os.environ['OPENCODE_SERVER_PASSWORD']) > 32
if sys.argv[1] == 'timeout':
    time.sleep(60)
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        expected = 'Basic ' + base64.b64encode(('opencode:' + os.environ['OPENCODE_SERVER_PASSWORD']).encode()).decode()
        if self.headers.get('Authorization') != expected:
            self.send_response(401); self.end_headers(); return
        body = json.dumps({'healthy': True, 'version': 'fixture'}).encode()
        self.send_response(200); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
server = HTTPServer(('127.0.0.1', 0), Handler)
print('opencode server listening on http://127.0.0.1:' + str(server.server_port), flush=True)
server.serve_forever()
""".trimIndent())
        try {
            block(LocalRuntimeInstallation("opencode", "fixture", listOf("/usr/bin/python3", script.path, mode), dir.path, "fixture"), dir)
        } finally {
            // Exact fixture files only; never native data directories.
            script.delete(); File(dir, "pid").delete(); dir.delete()
        }
    }

    @Test fun `owned server confirms health and close terminates only its process`() = fixture("ready") { runtime, dir ->
        val unrelated = ProcessBuilder("/bin/sleep", "60").start()
        try {
            val server = LocalOpenCodeServer.start(runtime, dir)
            val pid = File(dir, "pid").readText().toLong()
            try {
                assertTrue(server.alive)
                assertTrue(server.client.health().getBoolean("healthy"))
            } finally { server.close(); server.close() }
            repeat(50) { if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(20) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertTrue(unrelated.isAlive)
        } finally { unrelated.destroyForcibly(); unrelated.waitFor() }
    }

    @Test fun `startup timeout reaps the owned process`() = fixture("timeout") { runtime, dir ->
        assertFailsWith<TimeoutCancellationException> { LocalOpenCodeServer.start(runtime, dir, startupTimeoutMillis = 1000) }
        val pid = File(dir, "pid").readText().toLong()
        repeat(50) { if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(20) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun `startup output accepts only exact loopback server announcements`() {
        assertEquals(1234, LocalOpenCodeServer.listeningPort("opencode server listening on http://127.0.0.1:1234"))
        assertNull(LocalOpenCodeServer.listeningPort("opencode server listening on http://0.0.0.0:1234"))
        assertNull(LocalOpenCodeServer.listeningPort("opencode server listening on http://127.0.0.1:0"))
        assertNull(LocalOpenCodeServer.listeningPort("opencode server listening on http://127.0.0.1:1234/evil"))
    }
}
