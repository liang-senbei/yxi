package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RemoteClaudeSubscriptionProbeTest {
    @Test fun `remote cancelled or invalid checks close only their owned processes`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        val root = File("/sandbox/tmp/remote-claude-probe-failures").apply { mkdirs() }
        val unrelated = ProcessBuilder("/bin/sleep", "60").start()
        try {
            for (mode in listOf("cancel", "large", "malformed", "nonzero")) {
                val home = File(root, mode).apply { mkdirs() }
                val bin = File(home, ".local/bin").apply { mkdirs() }
                val pidFile = File(home, "probe.pid")
                File(bin, "claude").apply {
                    writeText("""#!/usr/bin/python3
import json,os,pathlib,sys,time
pathlib.Path('${pidFile.path}').write_text(str(os.getpid()))
mode='$mode'
if mode=='large':
    print('x'*70000,flush=True)
    time.sleep(60)
elif mode=='cancel': time.sleep(60)
elif mode=='malformed': print('synthetic-private-response')
else:
    print(json.dumps({'loggedIn':True,'authMethod':'oauth_token','apiProvider':'firstParty'}))
    sys.exit(7)
""")
                    setExecutable(true)
                }
                IsolatedSshBridge(File(home, "ssh"), mapOf("HOME" to home.path), File(home, "unused.sock")).use { bridge ->
                    bridge.conn.ssh.connect()
                    if (mode == "cancel") {
                        val pending = launch { ClaudeSubscriptionProbe.verifyRemote(bridge.conn.ssh, home.path) }
                        withTimeout(5000) { while (!pidFile.exists()) delay(20) }
                        withTimeout(5000) { pending.cancelAndJoin() }
                        assertTrue(pending.isCancelled)
                    } else {
                        val error = assertFailsWith<IllegalStateException> {
                            withTimeout(5000) { ClaudeSubscriptionProbe.verifyRemote(bridge.conn.ssh, home.path) }
                        }
                        assertFalse(error is CancellationException)
                        assertFalse(error.message.orEmpty().contains("synthetic-private"))
                    }
                    val pid = pidFile.readText().toLong()
                    withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) }
                    assertEquals("alive", bridge.conn.ssh.exec("printf alive").trim())
                    assertTrue(unrelated.isAlive)
                }
            }
        } finally { unrelated.destroyForcibly(); unrelated.waitFor() }
    }
    @Test fun `real remote Claude identity check keeps authentication and SSH scoped to its host`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(File("/opt/native/claude").canExecute())
        for (loggedIn in listOf(false, true)) {
            val root = File("/sandbox/tmp/remote-claude-identity-$loggedIn").apply { mkdirs() }
            val home = File(root, "home").apply { mkdirs() }
            val bin = File(home, ".local/bin").apply { mkdirs() }
            Files.createSymbolicLink(File(bin, "claude").toPath(), File("/opt/native/claude").toPath())
            val environment = mutableMapOf("HOME" to home.path, "ANTHROPIC_API_KEY" to "fixture-conflicting-api", "CLAUDE_CONFIG_DIR" to File(home, ".claude").path)
            if (loggedIn) environment["CLAUDE_CODE_OAUTH_TOKEN"] = "synthetic-oauth-token"
            IsolatedSshBridge(File(root, "ssh"), environment, File(root, "unused.sock")).use { bridge ->
                bridge.conn.ssh.connect()
                if (loggedIn) ClaudeSubscriptionProbe.verifyRemote(bridge.conn.ssh, home.path)
                else assertFailsWith<IllegalStateException> { ClaudeSubscriptionProbe.verifyRemote(bridge.conn.ssh, home.path) }
                assertEquals("intact", bridge.conn.ssh.exec("test \"\$ANTHROPIC_API_KEY\" = fixture-conflicting-api && printf intact").trim())
                assertFalse(File(home, ".claude/.credentials.json").exists())
            }
        }
    }
}
