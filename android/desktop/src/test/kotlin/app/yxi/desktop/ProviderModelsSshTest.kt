package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class ProviderModelsSshTest {
    @Test fun `selected host requests model catalog through secret stdin transport`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "provider-model-ssh-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val serverScript = """
import http.server,json,pathlib,sys
root=pathlib.Path(sys.argv[1])
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  valid=self.headers.get('Authorization')=='Bearer fixture-private-model-key'
  with (root/'calls').open('a') as f:f.write(json.dumps({'path':self.path,'authenticated':valid})+'\n')
  status=200 if valid else 403
  body={'data':[{'id':'provider/real-model','owned_by':'fixture'}]} if valid else {'error':'fixture-private-model-key'}
  raw=json.dumps(body).encode();self.send_response(status);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
(root/'port').write_text(str(server.server_port));server.serve_forever()
""".trimIndent()
        val server = ProcessBuilder("python3", "-u", "-c", serverScript, root.path)
            .redirectErrorStream(true).redirectOutput(root.resolve("server.log")).start()
        try {
            repeat(100) { if (!root.resolve("port").isFile) Thread.sleep(50) }
            assertTrue(root.resolve("port").isFile && server.isAlive)
            val url = "http://127.0.0.1:" + root.resolve("port").readText()
            IsolatedSshBridge(root.resolve("ssh"), mapOf("HOME" to home.path), root.resolve("unused.sock")).use { bridge ->
                runBlocking { withTimeout(20_000) {
                    bridge.conn.ssh.connect()
                    val models = ProviderModels.fetch(bridge.conn, url, "fixture-private-model-key")
                    assertEquals(listOf(ProviderModels.Model("provider/real-model", "fixture")), models)
                    val error = runCatching { ProviderModels.fetch(bridge.conn, url, "wrong-fixture-key") }.exceptionOrNull()
                    assertNotNull(error)
                    assertFalse(error.message.orEmpty().contains("fixture-private-model-key"))
                    assertFalse(error.message.orEmpty().contains("wrong-fixture-key"))
                    assertTrue(error.message.orEmpty().contains("密钥"))
                } }
            }
            val calls = root.resolve("calls").readLines().map { org.json.JSONObject(it) }
            assertEquals(listOf("/v1/models", "/v1/models"), calls.map { it.getString("path") })
            assertTrue(calls.first().getBoolean("authenticated"))
        } finally {
            server.destroy()
            if (!server.waitFor(3, TimeUnit.SECONDS)) server.destroyForcibly()
            root.deleteRecursively()
        }
    }
}
