package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import kotlin.test.*

class RemoteAcpTransportTest {
    @Test fun `SSH ACP channels preserve argv and close only their owned process`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        val root = File("/sandbox/tmp/remote-acp").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val bin = File(home, ".local/bin").apply { mkdirs() }
        val directory = File(home, "project ' with spaces").apply { mkdirs() }
        for (engine in listOf("gemini", "grok", "hermes")) File(bin, engine).apply {
            writeText("""#!/usr/bin/python3
import json,os,sys
if '--setup' in sys.argv:
    assert os.isatty(0) and os.isatty(1)
    assert os.environ['AUTH_TEST_TOKEN'] == 'fixture-secret-' * 400
    print('AUTH_PROMPT',flush=True)
    sys.exit(0 if input() == 'confirm' else 7)
for line in sys.stdin:
    request=json.loads(line)
    method=request.get('method')
    result={'protocolVersion':1,'agentCapabilities':{},'authMethods':[{'id':'setup','name':'Setup','type':'terminal','args':['--setup'],'env':{'AUTH_TEST_TOKEN':'fixture-secret-' * 400}}], '_meta':{'pid':os.getpid(),'cwd':os.getcwd(),'args':sys.argv[1:]}} if method=='initialize' else {'sessionId':'fixture-session'}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
""")
            setExecutable(true)
        }
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path), File(root, "unused.sock"), debug = true).use { bridge ->
            bridge.conn.ssh.connect()
            val exited = bridge.conn.ssh.openPtyCommand("printf terminal-exit; read answer; exit 7")
            try {
                val text = withContext(Dispatchers.IO) {
                    val received = StringBuilder()
                    while (!received.contains("terminal-exit")) {
                        val byte = exited.output.read(); check(byte >= 0) { "PTY closed before its prompt; exit=${exited.exitCode}; connected=${exited.isConnected}" }
                        received.append(byte.toChar())
                    }
                    received.toString()
                }
                assertTrue(text.contains("terminal-exit"))
                assertTrue(exited.write("confirm\n"))
                assertEquals(7, withTimeout(5000) { exited.awaitExitCode() })
            } finally { exited.close() }
            val cancelled = bridge.conn.ssh.openPtyCommand("sleep 30")
            assertNull(cancelled.exitCode)
            cancelled.close()
            assertNull(withTimeout(5000) { cancelled.awaitExitCode() }, "Disconnect without receipt must not mean success")
            val index = File(root, "tasks.json")
            RemoteAcpTasks(InstructionQueue(File(root, "queue.json")), index).use { tasks ->
                tasks.prepare(bridge.conn, "grok", directory.path)
                tasks.authenticateTerminal(bridge.conn, "setup") { plan ->
                    RemoteAuthenticationTerminal.start(plan).use { terminal ->
                        val output = withTimeout(5000) { runInterruptible(Dispatchers.IO) {
                            val text = StringBuilder(); val chars = CharArray(256)
                            while (!text.contains("AUTH_PROMPT")) {
                                val count = terminal.read(chars, 0, chars.size); check(count >= 0 && text.length < 16384)
                                text.append(chars, 0, count)
                            }
                            text.toString()
                        } }
                        assertFalse(output.contains("fixture-secret"), "Authentication environment must not be echoed by the PTY")
                        terminal.write("confirm\n")
                        withTimeout(5000) { terminal.awaitExit() }
                    }
                }
                val record = tasks.create(bridge.conn, "Remote ACP fixture")
                assertEquals("grok", record.engine)
                assertEquals(projectKey(bridge.conn.host, "/"), record.hostKey)
                assertEquals(directory.canonicalPath, record.directory)
                assertEquals(record, LocalCodexTaskRegistry(index).records.single())
                assertEquals("", tasks.recoverySessionId(bridge.conn))
                assertTrue(tasks.controllers.getValue(record.key).ready)
                tasks.disconnect(bridge.conn)
                assertTrue(tasks.controllers.isEmpty())
                assertEquals("still-connected", bridge.conn.ssh.exec("printf still-connected").trim())
            }
            val first = RemoteAcpTransport.connect(bridge.conn.ssh, "gemini", directory.path)
            val second = RemoteAcpTransport.connect(bridge.conn.ssh, "hermes", directory.path)
            try {
                val one = checkNotNull(first.initialization).getJSONObject("_meta")
                val two = checkNotNull(second.initialization).getJSONObject("_meta")
                assertEquals(directory.canonicalPath, one.getString("cwd"))
                assertTrue(one.getJSONArray("args").similar(JSONArray(AcpLaunch.arguments("gemini"))))
                assertTrue(two.getJSONArray("args").similar(JSONArray(AcpLaunch.arguments("hermes"))))
                val firstPid = one.getLong("pid")
                first.close()
                withTimeout(5000) { while (ProcessHandle.of(firstPid).map { it.isAlive }.orElse(false)) delay(20) }
                assertEquals("fixture-session", second.newSession(directory.path).getString("sessionId"))
                assertEquals("connected", bridge.conn.ssh.exec("printf connected").trim())
                val secondPid = two.getLong("pid")
                bridge.conn.ssh.disconnect()
                withTimeout(5000) { while (ProcessHandle.of(secondPid).map { it.isAlive }.orElse(false)) delay(20) }
            } finally { first.close(); second.close() }
        }
    }
}
