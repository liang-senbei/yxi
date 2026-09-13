package app.yxi.desktop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.*

class ServiceControlsFixtureTest {
    @Test fun `service UI saves starts previews and stops an isolated HTTP service`() {
        val directory = System.getenv("YXI_SERVICE_UI_FIXTURE")
        assumeTrue(directory != null)
        val fixture = File(directory!!)
        val sshPort = fixture.resolve("port").readText().trim().toInt()
        val publicKey = fixture.resolve("host.pub").readText().trim().split(' ').take(2).joinToString(" ")
        Store.knownHostsFile.writeText("[127.0.0.1]:$sshPort $publicKey\n")
        val host = Host("service-ui", "Service test", "127.0.0.1", sshPort, "root", fixture.resolve("client").path)
        val conn = Conn(host, FileHostKeys())
        val state = AppState()
        val cwd = fixture.resolve("home/project").path
        fixture.resolve("home/project/serve.py").writeText("import http.server,sys\nclass H(http.server.BaseHTTPRequestHandler):\n def do_GET(self):\n  page=b'<title>Service preview</title><h1>UI_DEV_SERVICE_READY</h1>' if self.path=='/app' else b'health'\n  self.send_response(200 if self.path in ('/app','/health/ready') else 503)\n  self.send_header('Content-Type','text/html')\n  self.send_header('Content-Length',str(len(page)))\n  self.end_headers()\n  self.wfile.write(page)\nhttp.server.HTTPServer(('127.0.0.1',int(sys.argv[1])),H).serve_forever()\n")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        fixture.resolve("service-port").writeText(port.toString())
        val project = projectKey(host, cwd)
        state.projectPreviews.save(project, "http://localhost:$port/app", null)
        val session = Session("fixture", 1, false, cwd, 0, SessionState.Idle, "", 0.0)
        val observedReady = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watcher.launch { while (isActive) {
            val snapshot = state.serviceControllers[project]?.snapshot
            if (snapshot?.optString("readiness") == "ready" && snapshot.optString("probePath") == "/health/ready") observedReady.set(true)
            delay(100)
        } }
        try {
            runBlocking { conn.ssh.connect() }; conn.status = Conn.Status.Connected
            runBlocking {
                val probeRoot = cwd + "/probe"
                val probe = PreviewServiceController(projectKey(host, probeRoot), probeRoot)
                probe.start(conn, ServiceSettings(cwd, "exec sleep 60", fixture.resolve("http-port").readText().trim().toInt()))
                assertTrue(probe.error.contains("端口"))
                probe.refresh(conn)
                assertTrue(probe.error.contains("端口")) // Polling must not erase a failed action's explanation.
                val polling = launch { probe.refresh(conn) }
                yield()
                assertTrue(probe.busy)
                probe.start(conn, ServiceSettings(cwd, "exec sleep 60", fixture.resolve("http-port").readText().trim().toInt()))
                polling.join()
                assertTrue(probe.error.contains("端口"))
            }
            application(exitProcessOnExit = false) {
                val scope = rememberCoroutineScope()
                Window(onCloseRequest = { scope.launch {
                    state.browsers.values.forEach { it.close() }
                    check(BrowserRuntime.shutdown()); exitApplication()
                } }, title = "Yxi service fixture", state = rememberWindowState(width = 700.dp, height = 820.dp)) {
                    YxiTheme { BrowserPane(state, conn, session) }
                }
            }
            assertEquals(port, state.projectServices.get(project)!!.port)
            assertTrue(observedReady.get(), "Owned HTTP listener was never confirmed ready")
            assertEquals("python3 serve.py $port", state.projectServices.get(project)!!.command)
            assertEquals("/health/ready", state.projectServices.get(project)!!.readinessPath)
            val status = runBlocking { PreviewServicePlan.result(conn.ssh.exec(PreviewServicePlan.statusCommand(project))) }
            assertEquals("missing", status.getString("state"))
            runBlocking { withTimeout(5000) {
                while (runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false)) delay(100)
            } }
            assertTrue(state.serviceControllers.values.none { it.mutating })
            assertTrue(state.serviceEditors.isEmpty())
        } finally { watcher.cancel(); conn.ssh.disconnect() }
    }
}
