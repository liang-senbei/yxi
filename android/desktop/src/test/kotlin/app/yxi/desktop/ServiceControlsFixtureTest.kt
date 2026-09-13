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
        fixture.resolve("home/project/index.html").writeText("<title>Service preview</title><h1>UI_DEV_SERVICE_READY</h1>")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        fixture.resolve("service-port").writeText(port.toString())
        val project = projectKey(host, cwd)
        state.projectPreviews.save(project, port.toString(), null)
        val session = Session("fixture", 1, false, cwd, 0, SessionState.Idle, "", 0.0)
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
            assertEquals("python3 -m http.server $port --bind 127.0.0.1", state.projectServices.get(project)!!.command)
            val status = runBlocking { PreviewServicePlan.result(conn.ssh.exec(PreviewServicePlan.statusCommand(project))) }
            assertEquals("missing", status.getString("state"))
            runBlocking { withTimeout(5000) {
                while (runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false)) delay(100)
            } }
            assertTrue(state.serviceControllers.values.none { it.mutating })
            assertTrue(state.serviceEditors.isEmpty())
        } finally { conn.ssh.disconnect() }
    }
}
