package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/** Runs only with an explicit native binary mounted inside the isolated test container. */
class OpenCodeNativeTest {
    @Test fun `native server creates and rereads an empty session without model inference`() = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(System.getProperty("user.home") == "/sandbox/home")
        val binary = File("/opt/native/claude") // The runner's generic read-only native binary mount.
        check(binary.isFile && binary.canExecute())
        val project = File("/sandbox/home/opencode-fixture").apply { mkdirs() }
        val runtime = LocalRuntimeInstallation("opencode", "isolated-native", listOf(binary.path), "/sandbox/home/.local/share/opencode", "native-test")
        val environment = System.getenv().toMutableMap().apply {
            this["HOME"] = "/sandbox/home"
            this["XDG_DATA_HOME"] = "/sandbox/home/.local/share"
            this["XDG_CONFIG_HOME"] = "/sandbox/home/.config"
            this["XDG_CACHE_HOME"] = "/sandbox/home/.cache"
        }
        LocalOpenCodeServer.start(runtime, project, environment).use { server ->
            val health = server.client.health()
            assertTrue(health.getBoolean("healthy"))
            println("Native OpenCode version: " + health.getString("version"))
            val created = server.client.create("Yxi native empty session")
            val id = created.getString("id")
            assertEquals(project.canonicalPath, created.getString("directory"))
            assertEquals(id, server.client.session(id).getString("id"))
            val sessions = server.client.sessions()
            assertTrue((0 until sessions.length()).any { sessions.getJSONObject(it).getString("id") == id })
            assertEquals(0, server.client.messages(id).length())
            assertTrue(server.client.permissions(id).isEmpty())
            assertTrue(server.client.questions(id).isEmpty())
            val models = server.client.availableModels()
            assertTrue(models.all { it.providerId.isNotBlank() && it.modelId.isNotBlank() })
            assertFalse(server.client.status().has(id), "An empty session must not be treated as a running model turn")
            println("Created and reread empty native session; no prompt sent.")
        }
    }
}
