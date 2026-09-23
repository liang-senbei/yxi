package app.yxi.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RemoteAcpCreationUiTest {
    @Test fun `server creation routes ACP choice to its host preview without executing the draft`() {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val root = File("/sandbox/tmp/acp-create-ui").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val directory = File(home, "project").apply { mkdirs() }
        val marker = File(root, "must-not-start")
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/gemini").apply {
            writeText("""#!/usr/bin/python3
import json,sys
for line in sys.stdin:
    request=json.loads(line)
    method=request.get('method','')
    with open('${marker.path}','a') as log: log.write(method+'\n')
    result={'protocolVersion':1,'agentCapabilities':{},'authMethods':[{'id':'fixture-login','name':'Fixture Login'}]} if method=='initialize' else {'sessionId':'fixture-server-session'} if method=='session/new' else {}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
""")
            setExecutable(true)
        }
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path), File(root, "unused.sock")).use { bridge ->
            runBlocking { bridge.conn.ssh.connect() }
            val state = AppState()
            var failure: Throwable? = null
            var routed = false
            try {
                application(exitProcessOnExit = false) {
                    Window(onCloseRequest = ::exitApplication, state = rememberWindowState(width = 1180.dp, height = 960.dp)) {
                        var creating by remember { mutableStateOf(true) }
                        var sidebar by remember { mutableStateOf(false) }
                        var projectTree by remember { mutableStateOf(false) }
                        YxiTheme { Surface {
                            if (creating) NewSessionDialog(bridge.conn, { creating = false }, initialDirectory = directory.path,
                                initialAgent = "gemini", groupContext = "Do not send this draft automatically", onAcpConversation = { engine, path, prompt ->
                                    state.prepareAcpTask(bridge.conn, engine, path, prompt); routed = true; creating = false
                                }) { failure = AssertionError("ACP must not use the tmux creation path") }
                            else if (projectTree) androidx.compose.foundation.layout.Column { ProjectTree(state, bridge.conn, emptyList(), searching = true, query = "Gemini") }
                            else if (sidebar) AcpTaskRow(state, bridge.conn, state.remoteAcpTasks.tasks(bridge.conn).single())
                            else RemoteAcpPane(state)
                        } }
                        LaunchedEffect(Unit) {
                            try {
                                delay(1400)
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize)), "png", File("/results/server-acp-create.png"))
                                val dialog = java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().firstOrNull { it.isShowing }
                                val locations = if (dialog != null) listOf((dialog.locationOnScreen.x + dialog.width - 100) to (dialog.locationOnScreen.y + dialog.height - 44))
                                    else (200..440 step 16).map { (window.locationOnScreen.x + window.width / 2 + 170) to (window.locationOnScreen.y + window.height / 2 + it) }
                                for ((x, y) in locations) {
                                    if (routed) break
                                    withContext(Dispatchers.IO) { Robot().apply {
                                        mouseMove(x, y); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                    } }
                                    delay(120)
                                }
                                withTimeout(3000) { while (!routed) delay(20) }
                                delay(400)
                                assertEquals(Page.Acp, state.page)
                                assertSame(bridge.conn, state.conn)
                                assertEquals("gemini", state.remoteAcpEngine)
                                assertEquals(directory.path, state.remoteAcpDirectory)
                                assertEquals("Do not send this draft automatically", state.remoteAcpPrompt)
                                assertTrue(state.remoteAcpTasks.registry.records.isEmpty())
                                assertFalse(marker.exists())
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/server-acp-preview.png"))
                                suspend fun click(x: Int, y: Int) = withContext(Dispatchers.IO) { Robot().apply {
                                    mouseMove(window.locationOnScreen.x + x, window.locationOnScreen.y + y)
                                    mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                }; Unit }
                                click(120, 290)
                                withTimeout(5000) { while (state.remoteAcpTasks.initialization == null) delay(20) }
                                delay(300)
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/server-acp-connected.png"))
                                click(120, 342)
                                withTimeout(3000) { while (!marker.readText().contains("authenticate")) delay(20) }
                                delay(150)
                                click(100, 402)
                                withTimeout(5000) { while (state.remoteAcpSelectedKey == null) delay(20) }
                                val record = state.remoteAcpTasks.tasks(bridge.conn).single()
                                assertEquals(record.key, state.remoteAcpSelectedKey)
                                assertEquals("Do not send this draft automatically", state.chatDrafts.getValue(record.key).value.text)
                                assertTrue(state.instructions.entries.none { it.taskKey == record.key })
                                assertFalse(marker.readText().contains("session/prompt"))
                                assertEquals(1, marker.readLines().count { it == "session/new" })
                                val controller = state.remoteAcpTasks.controllers.getValue(record.key)
                                assertEquals(app.yxi.agent.SessionState.Idle, acpTaskState(state, record))
                                assertTrue(state.remoteAcpTasks.tasks(bridge.conn.host.copy(id = "other-host", hostname = "192.0.2.2")).isEmpty())
                                state.navigation.rename(record.key, "服务器 Gemini 会话")
                                state.navigation.togglePin(record.key)
                                state.navigation.setNativeFavorite(record.key, true)
                                assertTrue(state.navigation.pinned(record.key))
                                assertTrue(state.navigation.favorite(record.key))
                                state.page = Page.Workspace; state.remoteAcpSelectedKey = null
                                sidebar = true; delay(500)
                                click(140, 24)
                                withTimeout(2000) { while (state.remoteAcpSelectedKey != record.key) delay(20) }
                                assertEquals(Page.Acp, state.page)
                                assertEquals(record.engine, state.remoteAcpEngine)
                                assertSame(controller, state.remoteAcpTasks.controllers[record.key])
                                assertSame(bridge.conn, state.conn)
                                assertEquals(1, marker.readLines().count { it == "session/new" })
                                assertFalse(marker.readText().contains("session/prompt"))
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/server-acp-sidebar.png"))
                                assertFalse(bridge.conn.groupsLoaded)
                                state.page = Page.Workspace; state.remoteAcpSelectedKey = null
                                projectTree = true; delay(500)
                                click(140, 72)
                                withTimeout(2000) { while (state.remoteAcpSelectedKey != record.key) delay(20) }
                                assertEquals(Page.Acp, state.page)
                                assertSame(controller, state.remoteAcpTasks.controllers[record.key])
                                assertEquals(1, marker.readLines().count { it == "session/new" })
                                delay(500)
                                ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size)), "png", File("/results/server-acp-project-loading.png"))
                                val pending = state.instructions.enqueue(record.key, "Do not send: status recovery fixture")
                                val delivering = state.instructions.beginDelivery(pending.id, pending.revision)
                                assertEquals(app.yxi.agent.SessionState.Working, acpTaskState(state, record))
                                val unknown = state.instructions.markUnknown(delivering.id, delivering.revision, "Fixture connection result unknown")
                                assertEquals(app.yxi.agent.SessionState.NeedsYou, acpTaskState(state, record))
                                state.navigation.setArchived(record.key, true)
                                state.navigation.setMode("待处理")
                                assertTrue(state.navigation.visible(record.key, acpTaskState(state, record)), "Unknown results must remain findable even for archived tasks")
                                state.instructions.resolveManually(unknown.id, unknown.revision)
                                assertEquals(app.yxi.agent.SessionState.Idle, acpTaskState(state, record))
                                assertFalse(state.navigation.visible(record.key, acpTaskState(state, record)))
                                state.navigation.setMode("归档")
                                assertTrue(state.navigation.visible(record.key, acpTaskState(state, record)))
                                state.conns.add(bridge.conn)
                                state.page = Page.Config; state.remoteAcpSelectedKey = null
                                assertFalse(state.openRemoteTask("missing-task"))
                                assertEquals(Page.Config, state.page)
                                assertTrue(state.openRemoteTask(record.key))
                                assertEquals(Page.Acp, state.page)
                                assertEquals("归档", state.navigation.mode)
                                assertEquals(record.key, state.remoteAcpSelectedKey)
                                assertSame(controller, state.remoteAcpTasks.controllers[record.key])
                                assertSame(bridge.conn, state.conn)
                                assertEquals(1, marker.readLines().count { it == "session/new" })
                                assertFalse(marker.readText().contains("session/prompt"))
                            } catch (e: Throwable) { failure = e }
                            finally { exitApplication() }
                        }
                    }
                }
            } finally { state.remoteAcpTasks.close(); state.closeLocalFeatures() }
            failure?.let { throw it }
        }
    }
}
