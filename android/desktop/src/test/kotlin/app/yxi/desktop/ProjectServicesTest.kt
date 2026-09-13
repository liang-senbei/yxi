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
