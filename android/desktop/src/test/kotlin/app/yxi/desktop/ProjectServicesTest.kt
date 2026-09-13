package app.yxi.desktop

import java.nio.file.Files
import kotlin.test.*

class ProjectServicesTest {
    @Test fun `service settings survive reopen and reject stale configuration edits`() {
        val root = Files.createTempDirectory("service-settings").toFile()
        try {
            val file = root.resolve("services.json")
            val store = ProjectServices(file, null)
            val project = "a".repeat(64)
            val config = ServiceSettings("/project", "npm run dev", 3000)
            store.save(project, config, null)
            assertEquals(config, ProjectServices(file, null).get(project))
            assertNull(store.get("b".repeat(64)))
            val revised = config.copy(port = 4000)
            store.save(project, revised, config)
            assertFails { store.save(project, config, config) }
            assertEquals(revised, store.get(project))
            val health = revised.copy(readinessPath = "/health/ready?check=one")
            store.save(project, health, revised)
            assertEquals(health, ProjectServices(file, null).get(project))
            assertEquals(revised.plan(project).signature, health.plan(project).signature)
        } finally { root.deleteRecursively() }
    }
    @Test fun `readiness paths preserve escapes and cannot select another origin`() {
        assertEquals("/health%2Fready?q=a%2Bb", normalizeReadinessPath("/health%2Fready?q=a%2Bb"))
        assertEquals("/%E5%81%A5%E5%BA%B7", normalizeReadinessPath("/健康"))
        listOf("https://example.com/", "//example.com/", "/health#fragment", "/health\r\nInjected: yes", "relative").forEach {
            assertFails { normalizeReadinessPath(it) }
        }
    }
    @Test fun `legacy service settings default to root and upgrade without losing command`() {
        val root = Files.createTempDirectory("legacy-service").toFile()
        try {
            val file = root.resolve("services.json")
            val key = "a".repeat(64)
            file.writeText("""{"version":1,"projects":{"$key":{"directory":"/project","command":"npm run dev","port":3000}}}""")
            val store = ProjectServices(file, null)
            val previous = store.get(key)!!
            assertEquals("/", previous.queryPath)
            store.save(key, previous.copy(readinessPath = "/health"), previous)
            assertEquals("npm run dev", ProjectServices(file, null).get(key)!!.command)
            assertEquals(2, org.json.JSONObject(file.readText()).getInt("version"))
        } finally { root.deleteRecursively() }
    }
    @Test fun `editor preserves unsaved changes and invalid input cannot produce a plan`() {
        val editor = ServiceEditor("a".repeat(64), "/project", null, 3000)
        assertFalse(editor.dirty)
        editor.command = "npm run dev"
        assertTrue(editor.dirty)
        editor.port = "not-a-port"
        assertFails { editor.value() }
        assertEquals("npm run dev", editor.command)
    }
}
