package app.yxi.desktop

import kotlin.test.*

class ClaudeSharedMcpTest {
    @Test fun `missing name evidence must identify the requested server and not generic failures`() {
        assertTrue(ClaudeSharedMcp.reportsMissing("echo", "No MCP server named \"echo\". Run `claude mcp add` to add one."))
        assertTrue(ClaudeSharedMcp.reportsMissing("echo", "Error: No MCP server found with name: echo"))
        assertFalse(ClaudeSharedMcp.reportsMissing("echo", "No MCP server named \"other\". Run `claude mcp add` to add one."))
        assertFalse(ClaudeSharedMcp.reportsMissing("echo", "Cannot read configuration: Permission denied"))
        assertFalse(ClaudeSharedMcp.reportsMissing("echo", "MCP server echo failed to connect"))
    }
}
