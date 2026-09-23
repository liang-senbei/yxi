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
for line in sys.stdin:
    request=json.loads(line)
    method=request.get('method')
    result={'protocolVersion':1,'agentCapabilities':{},'authMethods':[], '_meta':{'pid':os.getpid(),'cwd':os.getcwd(),'args':sys.argv[1:]}} if method=='initialize' else {'sessionId':'fixture-session'}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
""")
            setExecutable(true)
        }
        IsolatedSshBridge(File(root, "ssh"), mapOf("HOME" to home.path), File(root, "unused.sock")).use { bridge ->
            bridge.conn.ssh.connect()
            val index = File(root, "tasks.json")
            RemoteAcpTasks(InstructionQueue(File(root, "queue.json")), index).use { tasks ->
                tasks.prepare(bridge.conn, "grok", directory.path)
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
