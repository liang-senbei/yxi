package app.yxi.desktop

import app.yxi.agent.Lines
import org.json.JSONObject
import kotlin.test.*

class RouteEditorTest {
    private val original = Lines.Line("id", "Original", "https://example.test", token = "token", extra = JSONObject("""{"env":{"ANTHROPIC_DEFAULT_HAIKU_MODEL":"small"},"effortLevel":"high"}"""))
    @Test fun `editing a name preserves token authentication and other model mappings`() {
        val edited = editedRoute(original, "New", original.baseUrl, "", "model-large")
        assertEquals("token", edited.token); assertEquals("", edited.apiKey)
        assertEquals("small", edited.extraEnv().getString("ANTHROPIC_DEFAULT_HAIKU_MODEL"))
        assertEquals("high", edited.extra.getString("effortLevel"))
        assertEquals("model-large", edited.extraEnv().getString("ANTHROPIC_MODEL"))
        assertFalse(original.extra.has("model"))
    }
    @Test fun `codex stores model independently of claude environment`() {
        val edited = editedRoute(Lines.Line("c", "C", agent = Lines.CODEX), "C", "https://example.test/v1/", "secret", "model-x")
        assertEquals("model-x", edited.extra.getString("model")); assertFalse(edited.extra.has("env"))
        assertEquals("https://example.test/v1", edited.baseUrl)
    }
    @Test fun `invalid endpoint and credentials cannot be saved`() {
        listOf("file:///etc/passwd", "https://key@example.test", "https://example.test?key=x", "example.test").forEach {
            assertFails { editedRoute(original, "n", it, "key", "m") }
        }
        assertFails { editedRoute(original.copy(token = ""), "n", original.baseUrl, "", "m") }
    }
    @Test fun `default model survives the shared catalog serialization`() {
        val edited = editedRoute(Lines.Line("id", "n"), "n", "https://example.test", "key", "")
        val readBack = Lines.Line.fromSettings(edited, edited.settingsJson())
        assertTrue(routeCatalogEqual(listOf(edited), listOf(readBack)))
    }
    @Test fun `catalog comparison catches concurrent edits but ignores object key order`() {
        assertFalse(routeCatalogEqual(listOf(original), listOf(original.copy(name = "changed"))))
        val a = original.copy(extra = JSONObject("""{"a":1,"b":2}"""))
        val b = original.copy(extra = JSONObject("""{"b":2,"a":1}"""))
        assertTrue(routeCatalogEqual(listOf(a), listOf(b)))
    }
}
