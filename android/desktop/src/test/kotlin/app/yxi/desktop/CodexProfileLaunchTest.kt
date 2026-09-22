package app.yxi.desktop

import app.yxi.agent.Lines
import org.json.JSONObject
import kotlin.test.*

class CodexProfileLaunchTest {
    @Test fun `provider arguments contain no key and keep per-agent scope stable`() {
        val line = Lines.Line("p", "供应商", "https://example.invalid/v1", apiKey = "fixture-secret-key", agent = Lines.CODEX,
            extra = JSONObject().put("model", "provider/glm:variant").put("model_reasoning_effort", "high"))
        val (payload, overrides) = CodexProfileLaunch.configuration(line, "a".repeat(32))
        assertEquals(line.apiKey, payload.getString("key"))
        assertFalse(payload.getJSONArray("args").toString().contains(line.apiKey))
        assertEquals("yxi_agent_" + "a".repeat(32), overrides.provider)
        assertEquals("provider/glm:variant", overrides.model)
        val (_, second) = CodexProfileLaunch.configuration(line.copy(id = "another", apiKey = "other-secret"), "a".repeat(32))
        assertEquals(overrides.provider, second.provider)
    }

    @Test fun `credential-bearing urls and invalid runtime cannot reach launch arguments`() {
        val line = Lines.Line("p", "fixture", "https://example.invalid/v1", apiKey = "fixture-secret", agent = Lines.CODEX)
        for (url in listOf("https://user:pass@example.invalid", "https://example.invalid?key=fixture-secret", "file:///tmp/provider")) {
            assertFailsWith<IllegalArgumentException> { CodexProfileLaunch.configuration(line.copy(baseUrl = url), "a".repeat(32)) }
        }
        assertFailsWith<IllegalArgumentException> { CodexProfileLaunch.configuration(line.copy(agent = Lines.CLAUDE), "a".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { CodexProfileLaunch.configuration(line.copy(name = "fixture-secret"), "a".repeat(32)) }
    }
}
