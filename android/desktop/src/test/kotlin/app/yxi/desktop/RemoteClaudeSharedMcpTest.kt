package app.yxi.desktop

import app.yxi.ssh.Shell
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RemoteClaudeSharedMcpTest {
    @Test fun `server launch prepares private MCP config and preserves literal argv across retry`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "claude-shared-launch-").toFile()
        val home = root.resolve("home").apply { mkdirs() }
        val project = root.resolve("project with spaces")
        val captured = root.resolve("argv.json")
        home.resolve(".local/bin").mkdirs()
        home.resolve(".local/bin/claude").apply {
            writeText("""#!/usr/bin/python3
import json,os,pathlib,sys,time
if sys.argv[1:3] == ['mcp', 'get']:
    print('No MCP server named "' + sys.argv[3] + '". Run `claude mcp add` to add one.')
    sys.exit(1)
pathlib.Path(os.environ['YXI_ARGV_CAPTURE']).write_text(json.dumps(sys.argv[1:]))
time.sleep(60)
"""); setExecutable(true)
        }
        val socket = root.resolve("tmux.sock")
        IsolatedSshBridge(root.resolve("ssh"), mapOf("HOME" to home.path, "YXI_ARGV_CAPTURE" to captured.path), socket).use { bridge ->
            bridge.conn.ssh.connect()
            try {
                val definition = SharedMcpDefinition(projectKey(bridge.conn.host, "/"), "echo", "fixture", "1", "echo", listOf("/bin/echo", "literal argument"))
                val record = SharedMcpRecord(definition, setOf("claude"), 0)
                val id = DesktopLaunchPlan.newRequestId()
                val prepared = RemoteClaudeSharedMcp.stage(bridge.conn, listOf(record), id)
                assertEquals(prepared, RemoteClaudeSharedMcp.stage(bridge.conn, listOf(record), id))
                val prompt = "literal ${'$'}(touch not-a-command)"
                val plan = DesktopLaunchPlan(project.path, "claude", id, prompt, mcpConfigPath = prepared.path, mcpConfigHash = prepared.hash)
                assertTrue(bridge.conn.ssh.exec(plan.command()).contains(":ok"))
                withTimeout(5000) { while (!captured.isFile) delay(50) }
                val argv = JSONArray(captured.readText())
                assertEquals(listOf("--mcp-config", prepared.path, "--", prompt), (0 until argv.length()).map { argv.getString(it) })
                assertFalse(project.resolve("not-a-command").exists())
                assertTrue(bridge.conn.ssh.exec(plan.command()).contains(":exists"))
                val file = java.io.File(prepared.path)
                assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
                assertTrue(JSONObject(file.readText()).getJSONObject("mcpServers").has("echo"))
                val changed = record.copy(definition = definition.copy(command = listOf("/different")))
                assertFailsWith<IllegalStateException> { RemoteClaudeSharedMcp.stage(bridge.conn, listOf(changed), id) }
            } finally { bridge.conn.ssh.exec("tmux -S ${Shell.q(socket.path)} kill-server 2>/dev/null || true") }
        }
    }
}
