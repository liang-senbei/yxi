package app.yxi.desktop

import kotlin.test.*

class ClaudeProcessOccupancyTest {
    private val runtime = LocalRuntimeInstallation("claude", "fixture", listOf("/fixture/claude-version"), "/fixture/home", "fixture")
    private val id = "a67c5e3a-51c1-4507-a22a-46f85e07a267"
    @Test fun `only explicit native session arguments identify an owner`() {
        for (args in listOf(listOf("--resume", id), listOf("-r", id), listOf("--session-id", id), listOf("--resume=$id"), listOf("--session-id=$id"))) {
            assertTrue(ClaudeProcessOccupancy.claimsSession("/bin/claude", args, runtime, id))
            assertTrue(ClaudeProcessOccupancy.claimsSession("/fixture/claude-version", args, runtime, id))
            assertTrue(ClaudeProcessOccupancy.claimsSession("/bin/node", listOf("/npm/@anthropic-ai/claude-code/cli.js") + args, runtime, id))
        }
        assertFalse(ClaudeProcessOccupancy.claimsSession("/bin/bash", listOf("-c", "claude --resume $id"), runtime, id))
        assertFalse(ClaudeProcessOccupancy.claimsSession("/bin/node", listOf("/unrelated/cli.js", "--resume", id), runtime, id))
        assertFalse(ClaudeProcessOccupancy.claimsSession("/bin/claude", listOf("--", "--resume", id), runtime, id))
        assertFalse(ClaudeProcessOccupancy.claimsSession("/bin/claude", listOf("--resume", "other"), runtime, id))
        assertFalse(ClaudeProcessOccupancy.claimsSession("/bin/claude", listOf("--continue"), runtime, id))
        assertFalse(ClaudeProcessOccupancy.claimsSession("", emptyList(), runtime, id))
        val nodeRuntime = runtime.copy(command = listOf("/bin/node", "/npm/@anthropic-ai/claude-code/cli.js"))
        assertFalse(ClaudeProcessOccupancy.claimsSession("/bin/node", listOf("/unrelated/cli.js", "--resume", id), nodeRuntime, id))
    }
}
