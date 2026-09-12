package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import kotlin.test.*

class DurableFileTest {
    private fun fixture(block: (File, DurableFile) -> Unit) {
        val dir = Files.createTempDirectory("yxi-store-test").toFile()
        try { val f = File(dir, "hosts.json"); block(f, DurableFile(f) { JSONArray(it) }) }
        finally { dir.deleteRecursively() }
    }

    @Test fun `saved hosts survive a new store instance`() = fixture { f, store ->
        val data = "[{\"id\":\"a\",\"alias\":\"香港\"},{\"id\":\"b\"}]"
        store.write(data)
        assertEquals(data, DurableFile(f) { JSONArray(it) }.read())
    }
    @Test fun `broken current file recovers last valid backup`() = fixture { f, store ->
        store.write("[1]"); store.write("[2]"); f.writeText("broken")
        assertEquals("[1]", store.read())
        assertTrue(store.recovered)
        assertEquals("broken", File(f.parentFile, "hosts.json.damaged").readText())
    }
    @Test fun `unrecoverable data is not overwritten with empty list`() = fixture { f, store ->
        f.writeText("broken")
        assertFails { store.write("[]") }
        assertEquals("broken", f.readText())
    }
    @Test fun `invalid new data leaves current intact`() = fixture { f, store ->
        store.write("[1]")
        assertFails { store.write("invalid") }
        assertEquals("[1]", f.readText())
    }
    @Test fun `migration copy failure keeps source`() = fixture { f, _ ->
        f.writeText("[1]")
        val obstruction = File(f.parentFile, "not-a-directory").apply { writeText("x") }
        assertFails { DurableFile.migrate(f, File(obstruction, "hosts.json")) { JSONArray(it) } }
        assertEquals("[1]", f.readText())
    }
    @Test fun `migration preserves different valid versions`() = fixture { f, _ ->
        f.writeText("[1]")
        val target = File(f.parentFile, "new.json").apply { writeText("[2]") }
        assertFails { DurableFile.migrate(f, target) { JSONArray(it) } }
        assertEquals("[1]", f.readText()); assertEquals("[2]", target.readText())
    }
    @Test fun `successful migration verifies before removing source`() = fixture { f, _ ->
        f.writeText("[1]")
        val target = File(f.parentFile, "new.json")
        DurableFile.migrate(f, target) { JSONArray(it) }
        assertFalse(f.exists()); assertEquals("[1]", target.readText())
    }
}
