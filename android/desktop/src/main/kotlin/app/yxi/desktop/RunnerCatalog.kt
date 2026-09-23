package app.yxi.desktop

/** Configuration visibility is separate from verified creation support. */
internal data class RunnerEntry(val id: String, val title: String, val command: String?, val serverCreation: Boolean)
internal object RunnerCatalog {
    val entries = listOf(
        RunnerEntry("claude", "Claude Code", "claude", true),
        RunnerEntry("codex", "Codex", "codex", true),
        RunnerEntry("opencode", "OpenCode", "opencode", false),
        RunnerEntry("gemini", "Gemini", "gemini", false),
        RunnerEntry("grok", "Grok Build", null, false),
        RunnerEntry("hermes", "Hermes", "hermes", false),
    )
    fun find(id: String) = entries.singleOrNull { it.id == id }
    fun probeCommand(id: String): String {
        val command = requireNotNull(find(id)?.command) { "此运行器的启动命令尚未核对" }
        return "p=\$(command -v ${app.yxi.ssh.Shell.q(command)} 2>/dev/null || true); if [ -f \"\$p\" ] && [ -x \"\$p\" ]; then printf available; else printf missing; fi"
    }
}
