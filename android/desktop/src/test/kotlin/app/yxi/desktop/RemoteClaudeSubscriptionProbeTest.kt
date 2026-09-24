package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RemoteClaudeSubscriptionProbeTest {
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
