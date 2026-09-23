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
        return resolveCommand(id) + "\nif [ -f \"\$bin\" ] && [ -x \"\$bin\" ]; then printf available; else printf missing; fi"
    }
    fun resolveCommand(id: String): String {
        val command = requireNotNull(find(id)?.command) { "此运行器的启动命令尚未核对" }
        val candidates = listOf(".local/bin/$command") + when (id) {
            "opencode" -> listOf(".opencode/bin/opencode")
            "hermes" -> listOf(".hermes/bin/hermes", ".hermes/hermes-agent/venv/bin/hermes")
            else -> emptyList()
        }
        val paths = candidates.joinToString(" ") { "\"\$HOME/$it\"" }
        return "bin=\$(command -v ${app.yxi.ssh.Shell.q(command)} 2>/dev/null || true)\n" +
            "if [ ! -f \"\$bin\" ] || [ ! -x \"\$bin\" ]; then for candidate in $paths; do " +
            "if [ -f \"\$candidate\" ] && [ -x \"\$candidate\" ]; then bin=\"\$candidate\"; break; fi; done; fi"
    }
}
