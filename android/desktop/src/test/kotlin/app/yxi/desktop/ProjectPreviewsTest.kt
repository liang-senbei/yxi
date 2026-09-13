package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ProjectPreviewsTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("project-previews").toFile()
        try { block(dir.resolve("previews.json")) } finally { dir.deleteRecursively() }
    }
    @Test fun `project and endpoint identity isolate restored addresses`() = fixture { file ->
        val host = Host("hk", "HK", "hk.example")
        val key = projectKey(host, "/work/app")
        val presets = ProjectPreviews(file)
        assertEquals("http://localhost:3000/", presets.save(key, "3000", null))
        val restored = ProjectPreviews(file)
        assertEquals("http://localhost:3000/", restored.address(projectKey(host.copy(alias = "Renamed"), "/work/./app/")))
        assertNull(restored.address(projectKey(host, "/other/app")))
        assertNull(restored.address(projectKey(host.copy(port = 2222), "/work/app")))
        assertNull(restored.address(projectKey(host.copy(username = "another"), "/work/app")))
    }
    @Test fun `stale editor cannot replace a newer setting and removal preserves other projects`() = fixture { file ->
        val presets = ProjectPreviews(file)
        val original = presets.save("a", "3000", null)
        val next = presets.save("a", "4000", original)
        presets.save("b", "https://example.com/page", null)
        assertFails { presets.save("a", "5000", original) }
        assertEquals(next, presets.address("a"))
        presets.remove("a", next)
        val restored = ProjectPreviews(file)
        assertNull(restored.address("a")); assertEquals("https://example.com/page", restored.address("b"))
    }
    @Test fun `failed storage preserves previous value and invalid URLs are never stored`() = fixture { file ->
        val presets = ProjectPreviews(file)
        val original = presets.save("a", "3000", null)
        assertFails { presets.save("a", "file:///secret", original) }
        assertFails { presets.save("a", "http://user:password@example.com/", original) }
        file.writeText("broken")
        assertFails { presets.save("a", "4000", original) }
        assertEquals(original, presets.address("a")); assertEquals("broken", file.readText())
        assertFails { ProjectPreviews(file).save("a", "4000", null) }
    }
}
